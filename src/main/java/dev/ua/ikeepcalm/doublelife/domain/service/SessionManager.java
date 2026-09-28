package dev.ua.ikeepcalm.doublelife.domain.service;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.audit.SessionAuditor;
import dev.ua.ikeepcalm.doublelife.audit.SessionAuditor.AdminModeResult;
import dev.ua.ikeepcalm.doublelife.audit.SessionAuditor.ExitDetails;
import dev.ua.ikeepcalm.doublelife.audit.SessionAuditor.PermissionChange;
import dev.ua.ikeepcalm.doublelife.domain.model.RiskAssessment;
import dev.ua.ikeepcalm.doublelife.domain.model.source.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.domain.model.PlayerState;
import dev.ua.ikeepcalm.doublelife.util.ComponentUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.configuration.file.YamlConfiguration;

public class SessionManager {

    private final DoubleLife plugin;
    private final Map<UUID, SessionData> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> sessionTimers = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private List<SessionData> pendingSessions = new ArrayList<>();
    /** Set while the plugin is disabling: LuckPerms saves are awaited and no async tasks are scheduled. */
    private volatile boolean shuttingDown = false;

    private static final String SESSIONS_FOLDER = "sessions";
    private static final long SHUTDOWN_SAVE_TIMEOUT_SECONDS = 5L;

    public SessionManager(DoubleLife plugin) {
        this.plugin = plugin;
        loadPendingSessionsFromFile();
    }

    private SessionAuditor audit() {
        return plugin.getSessionAuditor();
    }

    public boolean canStartSession(Player player) {
        return canStartSession(player, DoubleLifeMode.DEFAULT);
    }

    public boolean canStartSession(Player player, DoubleLifeMode mode) {
        if (hasActiveSession(player)) {
            return false;
        }

        Long cooldownEnd = cooldowns.get(player.getUniqueId());
        if (cooldownEnd != null && System.currentTimeMillis() < cooldownEnd) {
            return false;
        }

        // Check basic permission first
        if (!player.hasPermission("doublelife.use")) {
            return false;
        }

        // Check turbo-specific permission for turbo mode
        if (mode == DoubleLifeMode.TURBO && !player.hasPermission("doublelife.turbo")) {
            return false;
        }

        return true;
    }

    public boolean hasTurboPermission(Player player) {
        return player.hasPermission("doublelife.turbo");
    }

    public void startSession(Player player, DoubleLifeMode mode) {
        startSession(player, mode, "command");
    }

    /** {@code trigger} (command or gui) is recorded on the entered audit row. */
    public void startSession(Player player, DoubleLifeMode mode, String trigger) {
        if (!canStartSession(player, mode)) {
            return;
        }

        PlayerState savedState = PlayerState.capture(player);
        SessionData session = new SessionData(player.getUniqueId(), savedState, mode);
        activeSessions.put(player.getUniqueId(), session);

        // Clear inventory and execute entry commands for both modes
        player.getInventory().clear();
        executeEntryCommands(player, session);

        AdminModeResult grant = null;
        if (mode == DoubleLifeMode.TURBO) {
            grant = applyAdminMode(player, session);
            // Send immediate Discord notification for turbo mode activation
            plugin.getWebhookUtil().sendTurboModeActivation(player.getName());
        }

        startTimer(player, session);
        createBossBar(player, mode);

        String modeMessage = mode == DoubleLifeMode.TURBO ? "session.turbo-start-success" : "session.default-start-success";
        player.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage(modeMessage, player)));
        plugin.getLogger().info(plugin.getLangConfig().getMessage("log.session-started", player.getName(), mode.getDisplayName()));
        audit().entered(player, session, trigger, plugin.getPluginConfig().getMaxDuration(),
                plugin.getPluginConfig().getEntryCommands(), grant);
    }

    public void startSession(Player player) {
        startSession(player, DoubleLifeMode.DEFAULT);
    }

    public void endSession(Player player) {
        endSession(player, "manual");
    }

    /**
     * Ends the session: restores the snapshot, removes TURBO nodes and op, writes the report.
     * {@code reason} is manual, gui, expired, offline_expiry, quit or shutdown.
     */
    public void endSession(Player player, String reason) {
        SessionData session = activeSessions.remove(player.getUniqueId());
        if (session != null) {
            finishSession(player, session, reason);
        }
    }

    /** Ends a session already removed from the active map; returns whether the snapshot was restored. */
    private boolean finishSession(Player player, SessionData session, String reason) {
        session.end();
        if (plugin.getActivityListener() != null) {
            plugin.getActivityListener().flushBlocks(player.getUniqueId(), session);
        }
        plugin.getActivityAuditor().flushSession(session);
        Map<String, Object> before = audit().captureBeforeRestore(player);
        boolean restoreOk = restorePlayerState(player, session);

        List<String> permsRemoved = List.of();
        boolean deopApplied = false;
        if (session.getMode() == DoubleLifeMode.TURBO) {
            boolean wasOp = player.isOp();
            permsRemoved = removeAdminModeSafely(player, session);
            deopApplied = wasOp && !player.isOp();
        }

        BukkitTask timer = sessionTimers.remove(player.getUniqueId());
        if (timer != null) {
            timer.cancel();
        }

        BossBar bossBar = bossBars.remove(player.getUniqueId());
        if (bossBar != null) {
            player.hideBossBar(bossBar);
        }

        RiskAssessment risk = analyzeSafely(session);
        plugin.getSessionReporter().report(session, player.getName(), risk);

        long cooldownDuration = plugin.getPluginConfig().getCooldownDuration() * 1000L;
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldownDuration);

        player.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage("session.end-success", player)));
        plugin.getLogger().info(plugin.getLangConfig().getMessage("log.session-ended", player.getName()));
        audit().exited(player, session, new ExitDetails(reason, restoreOk, permsRemoved, deopApplied, before), risk);
        return restoreOk;
    }

    private List<String> removeAdminModeSafely(Player player, SessionData session) {
        try {
            return removeAdminMode(player, session);
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Failed to remove admin mode for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
    }

    private RiskAssessment analyzeSafely(SessionData session) {
        try {
            return plugin.getSessionReporter().analyze(session);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Risk analysis failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Called from PlayerQuitEvent, before the server saves the player: records the quit and
     * ends the session so the snapshot and the removal of elevated permissions reach playerdata.
     */
    public void handleQuit(Player player) {
        SessionData session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        long totalAllowed = session.getTotalAllowedMinutes(plugin.getPluginConfig().getMaxDuration());
        audit().quitDuringSession(player, session, Math.max(0, totalAllowed - session.getDuration().toMinutes()));
        endSession(player, "quit");
    }

    /**
     * Plugin disable: ends every active session synchronously (each one isolated so a failure
     * cannot skip the rest), then persists only the sessions whose snapshot could not be
     * restored so they resume, and restore, on the next join.
     */
    public void shutdown() {
        shuttingDown = true;
        endAllSessions("shutdown");
        saveSessionsOnShutdown();
        plugin.getActivityAuditor().flushAll();
    }

    public void endAllSessions(String reason) {
        Set<UUID> sessionIds = new HashSet<>(activeSessions.keySet());
        for (UUID playerId : sessionIds) {
            Player player = Bukkit.getPlayer(playerId);
            SessionData session = player == null ? null : activeSessions.remove(playerId);
            if (session == null) {
                continue;
            }
            boolean restored = false;
            try {
                restored = finishSession(player, session, reason);
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Failed to end session for " + player.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
            if (!restored) {
                session.reopen();
                activeSessions.put(playerId, session);
            }
        }
    }

    public void saveSessionsOnShutdown() {
        if (activeSessions.isEmpty()) {
            return;
        }

        File sessionsFolder = new File(plugin.getDataFolder(), SESSIONS_FOLDER);
        if (!sessionsFolder.exists()) {
            sessionsFolder.mkdirs();
        }

        int savedCount = 0;
        for (Map.Entry<UUID, SessionData> entry : activeSessions.entrySet()) {
            try {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player == null) continue;

                SessionData session = entry.getValue();
                saveSessionToYaml(session, player.getName());
                savedCount++;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to save session for player " + entry.getKey() + ": " + e.getMessage());
            }
        }

        plugin.getLogger().info("Saved " + savedCount + " active sessions to individual YAML files");
    }

    private void saveSessionToYaml(SessionData session, String playerName) {
        try {
            File sessionsFolder = new File(plugin.getDataFolder(), SESSIONS_FOLDER);
            File sessionFile = new File(sessionsFolder, playerName + ".yml");

            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("session", session);
            yaml.save(sessionFile);

            plugin.getLogger().info("Saved session for " + playerName +
                " with " + (session.getSavedState() != null ? "preserved" : "MISSING") + " player state");

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save session YAML for " + playerName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadPendingSessionsFromFile() {
        File sessionsFolder = new File(plugin.getDataFolder(), SESSIONS_FOLDER);
        if (!sessionsFolder.exists()) {
            return;
        }

        File[] sessionFiles = sessionsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (sessionFiles == null || sessionFiles.length == 0) {
            return;
        }

        int loadedCount = 0;
        for (File sessionFile : sessionFiles) {
            try {
                SessionData session = loadSessionFromYaml(sessionFile);
                if (session != null) {
                    pendingSessions.add(session);
                    loadedCount++;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load session file " + sessionFile.getName() + ": " + e.getMessage());
                // Delete corrupted file
                sessionFile.delete();
            }
        }

        if (loadedCount > 0) {
            plugin.getLogger().info("Loaded " + loadedCount + " pending sessions from YAML files");
        }
    }

    private SessionData loadSessionFromYaml(File sessionFile) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(sessionFile);

            Object sessionObj = yaml.get("session");
            SessionData session = null;

            if (sessionObj instanceof SessionData) {
                session = (SessionData) sessionObj;
            } else if (sessionObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sessionMap = (Map<String, Object>) sessionObj;
                session = SessionData.deserialize(sessionMap);
            }

            if (session == null) {
                plugin.getLogger().warning("Failed to deserialize session from " + sessionFile.getName());
                sessionFile.delete();
                return null;
            }

            plugin.getLogger().info("Successfully loaded session from " + sessionFile.getName() +
                " with " + (session.getSavedState() != null ? "preserved" : "MISSING") + " player state");

            sessionFile.delete();

            return session;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load session YAML from " + sessionFile.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public void restoreSessionForPlayer(Player player) {
        if (pendingSessions.isEmpty()) {
            return;
        }

        UUID playerId = player.getUniqueId();
        SessionData sessionToRestore = null;

        for (SessionData session : pendingSessions) {
            if (session.getPlayerId().equals(playerId)) {
                sessionToRestore = session;
                break;
            }
        }

        if (sessionToRestore == null) {
            return;
        }

        long elapsedMinutes = sessionToRestore.getDuration().toMinutes();
        try {
            if (hasActiveSession(player)) {
                plugin.getLogger().warning("Player " + player.getName() + " already has an active session, skipping restoration");
                pendingSessions.remove(sessionToRestore);
                audit().resumed(player, sessionToRestore, "skipped_active", List.of(), elapsedMinutes);
                return;
            }

            long baseDuration = plugin.getPluginConfig().getMaxDuration();
            long totalAllowedMinutes = sessionToRestore.getTotalAllowedMinutes(baseDuration);
            if (elapsedMinutes >= totalAllowedMinutes) {
                plugin.getLogger().info("Session for " + player.getName() + " has expired, not restoring");
                player.sendMessage(ComponentUtil.warning(plugin.getLangConfig().getMessage("session.expired-during-restart", player)));
                pendingSessions.remove(sessionToRestore);
                audit().resumed(player, sessionToRestore, "expired", List.of(), elapsedMinutes);
                return;
            }

            activeSessions.put(playerId, sessionToRestore);

            List<String> reapplied = List.of();
            if (sessionToRestore.getMode() == DoubleLifeMode.TURBO) {
                reapplied = applyAdminMode(player, sessionToRestore).nodes();
            }

            startTimer(player, sessionToRestore);
            createBossBar(player, sessionToRestore.getMode());

            player.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage("session.restored-after-restart", player)));

            plugin.getLogger().info("Restored " + sessionToRestore.getMode().getDisplayName() + " session for " + player.getName());

            pendingSessions.remove(sessionToRestore);
            audit().resumed(player, sessionToRestore, "resumed", reapplied, elapsedMinutes);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to restore session for player " + player.getName() + ": " + e.getMessage());
            pendingSessions.remove(sessionToRestore);
            audit().resumed(player, sessionToRestore, "failed", List.of(), elapsedMinutes);
        }
    }

    /** A configured node: {@code -key} is a LuckPerms negation (value false) of {@code key}. */
    private record ConfiguredNode(String configured, String key, boolean value) {
        static ConfiguredNode parse(String configured) {
            if (configured.startsWith("-") && configured.length() > 1) {
                return new ConfiguredNode(configured, configured.substring(1), false);
            }
            return new ConfiguredNode(configured, configured, true);
        }
    }

    private AdminModeResult applyAdminMode(Player player, SessionData session) {
        plugin.getLogger().info("Applying admin mode for " + player.getName());
        UUID subjectId = player.getUniqueId();
        UUID sessionId = session.getSessionId();
        List<String> permissions = plugin.getPluginConfig().getTemporaryPermissions();
        Duration lifetime = Duration.ofMinutes(plugin.getPluginConfig().getMaxDuration());
        String expiry = Instant.now().plus(lifetime).toString();
        User user = plugin.getLuckPerms().getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            plugin.getLogger().info("User was not found in LuckPerms, withdrawing...");
            permissions.forEach(permission -> emitPermission(subjectId, sessionId, true,
                    ConfiguredNode.parse(permission), expiry, "not_attempted", "lp_user_not_loaded"));
            return new AdminModeResult(false, List.of());
        }

        List<Consumer<String>> rows = new ArrayList<>();
        for (String permission : permissions) {
            ConfiguredNode configured = ConfiguredNode.parse(permission);
            Node node = PermissionNode.builder(configured.key())
                    .value(configured.value())
                    .expiry(lifetime)
                    .build();
            DataMutateResult result = user.data().add(node);
            rows.add(state -> emitPermission(subjectId, sessionId, true, configured, expiry, result.name(), state));
            plugin.getLogger().info("Adding permission " + permission + " to " + player.getName());
        }

        onSaved(plugin.getLuckPerms().getUserManager().saveUser(user), state -> rows.forEach(row -> row.accept(state)));
        plugin.getLogger().info("Saved nodes!");
        return new AdminModeResult(true, List.copyOf(permissions));
    }

    /** Removes the configured nodes and op; returns the configured entries that were present. */
    private List<String> removeAdminMode(Player player, SessionData session) {
        List<String> permissions = plugin.getPluginConfig().getTemporaryPermissions();
        UUID subjectId = player.getUniqueId();
        UUID sessionId = session.getSessionId();
        User user = plugin.getLuckPerms().getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            permissions.forEach(permission -> emitPermission(subjectId, sessionId, false,
                    ConfiguredNode.parse(permission), null, "not_attempted", "lp_user_not_loaded"));
            return List.of();
        }

        NodeMap nodeMap = user.getData(DataType.NORMAL);
        List<String> removed = new ArrayList<>();
        List<Consumer<String>> rows = new ArrayList<>();

        for (String permission : permissions) {
            ConfiguredNode configured = ConfiguredNode.parse(permission);
            boolean present = clearNodes(user, configured);
            if (present) removed.add(permission);
            rows.add(state -> emitPermission(subjectId, sessionId, false, configured, null, present ? "removed" : "absent", state));

            if (nodeMap.remove(Node.builder(permission).build()) == DataMutateResult.SUCCESS) {
                plugin.getLogger().info(plugin.getLangConfig().getMessage("permissions.removed") + ": " + permission + " for " + player.getName());
            }
        }

        boolean wasOp = player.isOp();
        player.setOp(false);
        audit().opRevoked(player, "session_end", wasOp, session.getSessionId());

        onSaved(plugin.getLuckPerms().getUserManager().saveUser(user), state -> rows.forEach(row -> row.accept(state)));
        return removed;
    }

    /**
     * Clears nodes with the configured literal key (as before, which also removes literal
     * {@code -key} nodes written by older versions) and, for a negation, the temporary
     * {@code key=false} node this plugin adds. Permanent grants of {@code key} are untouched.
     */
    private static boolean clearNodes(User user, ConfiguredNode configured) {
        Predicate<Node> literal = n -> n.getKey().equals(configured.configured());
        Predicate<Node> negation = n -> !configured.value() && n.getKey().equals(configured.key())
                && !n.getValue() && n.hasExpiry();
        Predicate<Node> ours = literal.or(negation);
        boolean present = user.getNodes().stream().anyMatch(ours);
        user.data().clear(ours::test);
        return present;
    }

    /** Plain values only: may run on a LuckPerms thread. */
    private void emitPermission(UUID subjectId, UUID sessionId, boolean grant, ConfiguredNode node,
                                String expiry, String lpResult, String saveState) {
        audit().permissionChanged(new PermissionChange(subjectId, sessionId, grant,
                node.key(), node.value(), expiry, lpResult, saveState));
    }

    /**
     * Reports the LuckPerms save outcome as saved, failed or pending. During shutdown the save
     * is awaited (bounded) on the main thread so rows are emitted before the audit client closes;
     * otherwise the callback runs on the LuckPerms thread with plain captured values only.
     */
    private void onSaved(CompletableFuture<Void> save, Consumer<String> report) {
        if (!shuttingDown) {
            save.whenComplete((ignored, failure) -> report.accept(failure == null ? "saved" : "failed"));
            return;
        }
        try {
            save.get(SHUTDOWN_SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            report.accept("saved");
        } catch (TimeoutException e) {
            report.accept("pending");
        } catch (ExecutionException e) {
            report.accept("failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            report.accept("pending");
        }
    }

    /** Restores the snapshot; returns false (and logs) instead of throwing so cleanup always runs. */
    private boolean restorePlayerState(Player player, SessionData session) {
        PlayerState state = session.getSavedState();
        if (state == null) {
            plugin.getLogger().warning("Cannot restore player state for " + player.getName() + " - saved state is null!");
            return false;
        }
        plugin.getLogger().info("Restoring player state for " + player.getName());
        try {
            plugin.getActivityAuditor().withSystemContext(player, "session_restore", () -> state.restore(player));
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Failed to restore player state for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
        plugin.getLogger().info("Successfully restored player state for " + player.getName());
        savePlayerData(player);
        return true;
    }

    /**
     * Writes the restored state to playerdata immediately, so a crash before the next autosave
     * cannot bring back the session inventory.
     */
    private void savePlayerData(Player player) {
        try {
            player.saveData();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Failed to save player data for " + player.getName() + ": " + e.getMessage());
        }
    }

    private void startTimer(Player player, SessionData session) {
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            updateBossBar(player, session);

            long maxDuration = plugin.getPluginConfig().getMaxDuration();
            long totalAllowedMinutes = session.getTotalAllowedMinutes(maxDuration);
            if (session.getDuration().toMinutes() >= totalAllowedMinutes) {
                endSession(player, player.isOnline() ? "expired" : "offline_expiry");
            }
        }, 0L, 20L);

        sessionTimers.put(player.getUniqueId(), task);
    }

    private void createBossBar(Player player, DoubleLifeMode mode) {
        String titleKey = mode == DoubleLifeMode.TURBO ? "bossbar.turbo-active-title" : "bossbar.default-active-title";
        BossBar.Color color = mode == DoubleLifeMode.TURBO ? BossBar.Color.YELLOW : BossBar.Color.BLUE;

        BossBar bossBar = BossBar.bossBar(
                ComponentUtil.gradient(plugin.getLangConfig().getMessage(titleKey), "#FFD700", "#FF6B35"),
                1.0f,
                color,
                BossBar.Overlay.PROGRESS
        );

        player.showBossBar(bossBar);
        bossBars.put(player.getUniqueId(), bossBar);
    }

    private void updateBossBar(Player player, SessionData session) {
        BossBar bossBar = bossBars.get(player.getUniqueId());
        if (bossBar == null) return;

        Duration duration = session.getDuration();
        long baseDuration = plugin.getPluginConfig().getMaxDuration();
        long totalAllowedMinutes = session.getTotalAllowedMinutes(baseDuration);
        float progress = 1.0f - (float) duration.toMinutes() / totalAllowedMinutes;

        long remainingMinutes = totalAllowedMinutes - duration.toMinutes();
        String titleText = plugin.getLangConfig().getMessage("bossbar.remaining-time", remainingMinutes);
        Component title = ComponentUtil.gradient(titleText, "#FFD700", "#FF6B35");

        bossBar.name(title);
        bossBar.progress(Math.max(0, Math.min(1, progress)));

        if (remainingMinutes <= 1) {
            bossBar.color(BossBar.Color.RED);
        } else if (remainingMinutes <= 5) {
            bossBar.color(BossBar.Color.PINK);
        }
    }

    private void executeEntryCommands(Player player, SessionData session) {
        List<String> commands = plugin.getPluginConfig().getEntryCommands();
        plugin.getActivityAuditor().withSystemContext(player, "entry_command", () -> {
            for (String command : commands) {
                String processedCommand = command.replace("{player}", player.getName());
                boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand);
                audit().entryCommand(player, session, processedCommand, dispatched);
            }
        });
    }

    public boolean hasActiveSession(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    public SessionData getSession(Player player) {
        return activeSessions.get(player.getUniqueId());
    }

    public long getRemainingCooldown(Player player) {
        Long cooldownEnd = cooldowns.get(player.getUniqueId());
        if (cooldownEnd == null) return 0;

        long remaining = cooldownEnd - System.currentTimeMillis();
        return Math.max(0, remaining / 1000);
    }

    public boolean prolongSession(Player player, int additionalMinutes) {
        SessionData session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }

        long baseDuration = plugin.getPluginConfig().getMaxDuration();
        long currentTotalAllowed = session.getTotalAllowedMinutes(baseDuration);
        long newTotalAllowed = currentTotalAllowed + additionalMinutes;

        if (newTotalAllowed > baseDuration * 2) {
            audit().extended(player, session, additionalMinutes, currentTotalAllowed, false);
            return false;
        }

        long extensionMillis = additionalMinutes * 60L * 1000L;
        session.extendSession(extensionMillis);

        updateBossBar(player, session);

        player.sendMessage(ComponentUtil.success(
            plugin.getLangConfig().getMessage("session.extended-success", player, additionalMinutes)
        ));
        plugin.getLogger().info("Extended session for " + player.getName() + " by " + additionalMinutes + " minutes");
        audit().extended(player, session, additionalMinutes, newTotalAllowed, true);

        return true;
    }
}
