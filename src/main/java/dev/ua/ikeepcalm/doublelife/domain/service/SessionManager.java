package dev.ua.ikeepcalm.doublelife.domain.service;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.audit.SessionAuditor;
import dev.ua.ikeepcalm.doublelife.audit.SessionAuditor.AdminModeRemoval;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
    /** Sessions whose snapshot is not restored yet: loaded ones to resume, and ended ones whose restore failed. */
    private List<SessionData> pendingSessions = new ArrayList<>();
    /** Loaded session files by session id; each stays tracked until that session is restored and its file removed. */
    private final Map<UUID, SessionFile> sessionFiles = new ConcurrentHashMap<>();
    /** Session id to the player its snapshot was applied to when the playerdata save failed; only the save is retried. */
    private final Map<UUID, Player> unsavedRestores = new ConcurrentHashMap<>();
    /** Session files that could not be loaded; kept for staff and never overwritten, loading is retried next start. */
    private final Set<File> unreadableFiles = ConcurrentHashMap.newKeySet();
    /** Set while the plugin is disabling: LuckPerms saves are awaited and no async tasks are scheduled. */
    private volatile boolean shuttingDown = false;

    private static final String SESSIONS_FOLDER = "sessions";
    /** Key of the marker written over a restored session's file that could not be deleted. */
    private static final String RESTORED_KEY = "restoredSession";
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
        retryRestoredFileCleanup();
        // A pending session holds the player's original state and a restored file not yet removed could replay;
        // a new session would snapshot the session state or leave two files.
        if (hasActiveSession(player) || hasUnsettledSession(player.getUniqueId())) {
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
     * {@code reason} is manual, gui, expired, offline_expiry, quit, shutdown or restore_retry.
     */
    public void endSession(Player player, String reason) {
        SessionData session = activeSessions.remove(player.getUniqueId());
        if (session != null) {
            endOrKeepPending(player, session, reason);
        }
    }

    /**
     * Ends the session (see {@link #finishSession}). If ending throws, the timer, boss bar and TURBO
     * nodes are removed here, so a session left pending never keeps its privileges.
     */
    private void endOrKeepPending(Player player, SessionData session, String reason) {
        try {
            finishSession(player, session, reason);
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Failed to end session for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            stopTimerAndBossBar(player);
            if (session.getMode() == DoubleLifeMode.TURBO) {
                removeAdminModeSafely(player, session);
            }
        }
    }

    /**
     * Ends a session already removed from the active map. It stays pending, ended so it is never
     * resumed, until its snapshot is restored and saved to playerdata; the next join retries the restore.
     */
    private void finishSession(Player player, SessionData session, String reason) {
        session.end();
        if (!pendingSessions.contains(session)) {
            pendingSessions.add(session);
        }
        if (plugin.getActivityListener() != null) {
            plugin.getActivityListener().flushBlocks(player.getUniqueId(), session);
        }
        plugin.getActivityAuditor().flushSession(session);
        Map<String, Object> before = audit().captureBeforeRestore(player);
        boolean restoreOk = restorePlayerState(player, session);
        if (restoreOk) {
            settleSession(session);
        }

        AdminModeRemoval removal = null;
        boolean deopApplied = false;
        if (session.getMode() == DoubleLifeMode.TURBO) {
            boolean wasOp = player.isOp();
            removal = removeAdminModeSafely(player, session);
            deopApplied = wasOp && !player.isOp();
        }

        stopTimerAndBossBar(player);

        RiskAssessment risk = analyzeSafely(session);
        plugin.getSessionReporter().report(session, player.getName(), risk);

        long cooldownDuration = plugin.getPluginConfig().getCooldownDuration() * 1000L;
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldownDuration);

        if (restoreOk) {
            player.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage("session.end-success", player)));
        } else {
            player.sendMessage(ComponentUtil.warning("Session ended, but your original state could not be restored yet. It will be retried automatically."));
            plugin.getLogger().warning("Session for " + player.getName() + " ended without restoring its snapshot; kept pending for a retry");
        }
        plugin.getLogger().info(plugin.getLangConfig().getMessage("log.session-ended", player.getName()));
        audit().exited(player, session, new ExitDetails(reason, restoreOk, removal, deopApplied, before), risk);
    }

    private void stopTimerAndBossBar(Player player) {
        BukkitTask timer = sessionTimers.remove(player.getUniqueId());
        if (timer != null) {
            timer.cancel();
        }

        BossBar bossBar = bossBars.remove(player.getUniqueId());
        if (bossBar != null) {
            player.hideBossBar(bossBar);
        }
    }

    /** The snapshot is restored and saved: the session is never restored again and its file is removed. */
    private void settleSession(SessionData session) {
        UUID sessionId = session.getSessionId();
        pendingSessions.remove(session);
        unsavedRestores.remove(sessionId);
        SessionFile loaded = sessionFiles.get(sessionId);
        if (loaded == null) {
            return;
        }
        if (removeSessionFile(loaded.file(), sessionId)) {
            sessionFiles.remove(sessionId);
        } else {
            // Still tracked, so the player cannot start a session; removal is retried, the restore never is.
            sessionFiles.put(sessionId, loaded.asRestored());
            plugin.getLogger().severe("Failed to delete or mark restored session file " + loaded.file().getName()
                + "; retrying, but if it remains at the next start it restores an old snapshot");
        }
    }

    /**
     * Deletes a restored session's file. If that fails, overwrites it with a marker that loading
     * discards, so it cannot restore an old snapshot after a restart. False if both fail.
     */
    private boolean removeSessionFile(File file, UUID sessionId) {
        if (!file.exists() || file.delete()) {
            return true;
        }
        try {
            Files.writeString(file.toPath(), RESTORED_KEY + ": " + sessionId + "\n", StandardCharsets.UTF_8);
            plugin.getLogger().warning("Could not delete restored session file " + file.getName() + "; marked it as restored");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Retries removing the files of restored sessions; their snapshot is never restored again. */
    private void retryRestoredFileCleanup() {
        sessionFiles.forEach((sessionId, loaded) -> {
            if (loaded.restored() && removeSessionFile(loaded.file(), sessionId)) {
                sessionFiles.remove(sessionId);
            }
        });
    }

    /**
     * Retries the playerdata save of a snapshot already applied to this player, so it is not applied
     * again. If the save fails again the session stays pending and the next join restores it again.
     */
    private void retryUnsavedRestores(Player player) {
        for (SessionData session : List.copyOf(pendingSessions)) {
            if (!session.getPlayerId().equals(player.getUniqueId())) {
                continue;
            }
            Player applied = unsavedRestores.remove(session.getSessionId());
            if (applied == player && savePlayerData(player)) {
                settleSession(session);
            }
        }
    }

    /** Null lpUserLoaded when removal threw before LuckPerms was consulted. */
    private AdminModeRemoval removeAdminModeSafely(Player player, SessionData session) {
        try {
            return removeAdminMode(player, session);
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Failed to remove admin mode for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return new AdminModeRemoval(null, List.of());
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
     * A restored snapshot whose playerdata save failed gets one more save attempt.
     */
    public void handleQuit(Player player) {
        retryUnsavedRestores(player);
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
     * cannot skip the rest) and retries failed playerdata saves and file removals. It then persists
     * the sessions whose snapshot could not be restored so the next join restores them.
     */
    public void shutdown() {
        shuttingDown = true;
        endAllSessions("shutdown");
        for (Player player : List.copyOf(unsavedRestores.values())) {
            if (player.isOnline()) {
                retryUnsavedRestores(player);
            }
        }
        retryRestoredFileCleanup();
        sessionFiles.values().stream().filter(SessionFile::restored).forEach(loaded ->
                plugin.getLogger().severe("Restored session file " + loaded.file().getName()
                    + " could not be removed; delete it before the next start or it restores an old snapshot"));
        saveSessionsOnShutdown();
        plugin.getActivityAuditor().flushAll();
    }

    public void endAllSessions(String reason) {
        Set<UUID> sessionIds = new HashSet<>(activeSessions.keySet());
        for (UUID playerId : sessionIds) {
            Player player = Bukkit.getPlayer(playerId);
            SessionData session = player == null ? null : activeSessions.remove(playerId);
            if (session != null) {
                endOrKeepPending(player, session, reason);
            }
        }
    }

    /**
     * Writes active sessions, to resume, and ended sessions whose snapshot was not restored, to restore
     * on the next join. A pending session that was never touched keeps the file it was loaded from.
     */
    public void saveSessionsOnShutdown() {
        List<SessionData> toSave = new ArrayList<>(activeSessions.values());
        pendingSessions.stream().filter(session -> !session.isActive()).forEach(toSave::add);
        if (toSave.isEmpty()) {
            return;
        }

        File sessionsFolder = new File(plugin.getDataFolder(), SESSIONS_FOLDER);
        if (!sessionsFolder.exists()) {
            sessionsFolder.mkdirs();
        }

        int savedCount = 0;
        for (SessionData session : toSave) {
            try {
                Player player = Bukkit.getPlayer(session.getPlayerId());
                String playerName = player == null ? null : player.getName();
                if (saveSessionToYaml(session, sessionFileFor(session, sessionsFolder, playerName))) {
                    savedCount++;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to save session for player " + session.getPlayerId() + ": " + e.getMessage());
            }
        }

        plugin.getLogger().info("Saved " + savedCount + " sessions to individual YAML files");
    }

    /**
     * A loaded session reuses the file it was loaded from, even after a rename, so a player never
     * has two files. Another session uses the player name, or the UUID if the player is offline
     * or a loaded or unreadable file has that name, or the UUID and session id if that is taken too.
     */
    private File sessionFileFor(SessionData session, File sessionsFolder, String playerName) {
        SessionFile loaded = sessionFiles.get(session.getSessionId());
        if (loaded != null) {
            return loaded.file();
        }
        if (playerName != null) {
            File byName = new File(sessionsFolder, playerName + ".yml");
            if (!isFileTaken(byName)) {
                return byName;
            }
        }
        File byUuid = new File(sessionsFolder, session.getPlayerId() + ".yml");
        if (!isFileTaken(byUuid)) {
            return byUuid;
        }
        return new File(sessionsFolder, session.getPlayerId() + "-" + session.getSessionId() + ".yml");
    }

    private boolean isFileTaken(File file) {
        return unreadableFiles.contains(file)
                || sessionFiles.values().stream().anyMatch(other -> other.file().equals(file));
    }

    /**
     * Writes the whole session to a temporary file, then moves it over the target (atomically where
     * the file system supports it), so a failed write leaves any existing file intact.
     */
    private boolean saveSessionToYaml(SessionData session, File sessionFile) {
        try {
            File tempFile = new File(sessionFile.getParentFile(), sessionFile.getName() + ".tmp");

            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("session", session);
            yaml.save(tempFile);
            try {
                Files.move(tempFile.toPath(), sessionFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile.toPath(), sessionFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            plugin.getLogger().info("Saved session to " + sessionFile.getName() +
                " with " + (session.getSavedState() != null ? "preserved" : "MISSING") + " player state");
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save session YAML " + sessionFile.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void loadPendingSessionsFromFile() {
        File sessionsFolder = new File(plugin.getDataFolder(), SESSIONS_FOLDER);
        if (!sessionsFolder.exists()) {
            return;
        }

        File[] files = sessionsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            return;
        }

        int loadedCount = 0;
        for (File sessionFile : files) {
            try {
                SessionData session = loadSessionFromYaml(sessionFile);
                if (session != null) {
                    pendingSessions.add(session);
                    sessionFiles.put(session.getSessionId(), new SessionFile(session.getPlayerId(), sessionFile, false));
                    loadedCount++;
                }
            } catch (Exception e) {
                keepUnreadableFile(sessionFile, e.getMessage());
            }
        }

        if (loadedCount > 0) {
            plugin.getLogger().info("Loaded " + loadedCount + " pending sessions from YAML files");
        }
    }

    private SessionData loadSessionFromYaml(File sessionFile) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(sessionFile);
            if (yaml.isSet(RESTORED_KEY)) {
                plugin.getLogger().info("Removing session file " + sessionFile.getName() + " whose snapshot was already restored");
                sessionFile.delete();
                return null;
            }

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
                keepUnreadableFile(sessionFile, "no session could be deserialized");
                return null;
            }

            plugin.getLogger().info("Successfully loaded session from " + sessionFile.getName() +
                " with " + (session.getSavedState() != null ? "preserved" : "MISSING") + " player state");

            return session;
        } catch (Exception e) {
            e.printStackTrace();
            keepUnreadableFile(sessionFile, e.getMessage());
            return null;
        }
    }

    /** The file may hold the only copy of a player's original state, so it is kept for staff to recover. */
    private void keepUnreadableFile(File sessionFile, String reason) {
        unreadableFiles.add(sessionFile);
        plugin.getLogger().severe("Failed to load session file " + sessionFile.getName() + " (" + reason + "); kept it, "
            + "it may hold a player's original state: fix or restore it manually, loading is retried on the next start");
    }

    /**
     * On join: resumes a pending session, or restores the snapshot of one that expired or whose end
     * failed. A failure leaves the session pending, without session privileges, for the next join.
     */
    public void restoreSessionForPlayer(Player player) {
        retryRestoredFileCleanup();
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
        List<String> reapplied = List.of();
        try {
            if (hasActiveSession(player)) {
                plugin.getLogger().warning("Player " + player.getName() + " already has an active session, keeping the saved session pending");
                audit().resumed(player, sessionToRestore, "skipped_active", List.of(), elapsedMinutes);
                return;
            }

            if (!sessionToRestore.isActive()) {
                // Its end did not restore the snapshot: it is restored again, never resumed.
                plugin.getLogger().info("Retrying the restore of an ended session for " + player.getName());
                endOrKeepPending(player, sessionToRestore, "restore_retry");
                return;
            }

            long baseDuration = plugin.getPluginConfig().getMaxDuration();
            long totalAllowedMinutes = sessionToRestore.getTotalAllowedMinutes(baseDuration);
            if (elapsedMinutes >= totalAllowedMinutes) {
                plugin.getLogger().info("Session for " + player.getName() + " has expired, restoring saved state");
                player.sendMessage(ComponentUtil.warning(plugin.getLangConfig().getMessage("session.expired-during-restart", player)));
                audit().resumed(player, sessionToRestore, "expired", List.of(), elapsedMinutes);
                // Never activated: ending it restores the snapshot, or keeps it pending for the next join.
                endOrKeepPending(player, sessionToRestore, "expired");
                return;
            }

            activeSessions.put(playerId, sessionToRestore);

            if (sessionToRestore.getMode() == DoubleLifeMode.TURBO) {
                reapplied = applyAdminMode(player, sessionToRestore).nodes();
            }

            startTimer(player, sessionToRestore);
            createBossBar(player, sessionToRestore.getMode());

            pendingSessions.remove(sessionToRestore);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to restore session for player " + player.getName() + ": " + e.getMessage());
            audit().resumed(player, sessionToRestore, "failed", List.of(), elapsedMinutes);
            if (activeSessions.remove(playerId, sessionToRestore)) {
                // Partly resumed: ending it undoes what was applied and restores the snapshot, or keeps it pending.
                endOrKeepPending(player, sessionToRestore, "restore_retry");
            }
            return;
        }

        player.sendMessage(ComponentUtil.success(plugin.getLangConfig().getMessage("session.restored-after-restart", player)));

        plugin.getLogger().info("Restored " + sessionToRestore.getMode().getDisplayName() + " session for " + player.getName());

        audit().resumed(player, sessionToRestore, "resumed", reapplied, elapsedMinutes);
    }

    /** {@code restored}: the snapshot was restored and saved, and only the file removal is left. */
    private record SessionFile(UUID playerId, File file, boolean restored) {
        SessionFile asRestored() {
            return new SessionFile(playerId, file, true);
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

    /** Removes the configured nodes and op; returns whether LuckPerms had the user and the entries that were present. */
    private AdminModeRemoval removeAdminMode(Player player, SessionData session) {
        List<String> permissions = plugin.getPluginConfig().getTemporaryPermissions();
        UUID subjectId = player.getUniqueId();
        UUID sessionId = session.getSessionId();
        User user = plugin.getLuckPerms().getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            permissions.forEach(permission -> emitPermission(subjectId, sessionId, false,
                    ConfiguredNode.parse(permission), null, "not_attempted", "lp_user_not_loaded"));
            return new AdminModeRemoval(false, List.of());
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
        return new AdminModeRemoval(true, List.copyOf(removed));
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

    /**
     * Restores the snapshot and saves it to playerdata; returns false (and logs) instead of throwing
     * so cleanup always runs.
     */
    private boolean restorePlayerState(Player player, SessionData session) {
        unsavedRestores.remove(session.getSessionId());
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
        if (!savePlayerData(player)) {
            // Applied but not on disk: the session stays pending, and quit or shutdown retries only the save.
            unsavedRestores.put(session.getSessionId(), player);
            return false;
        }
        return true;
    }

    /**
     * Writes the restored state to playerdata immediately, so a crash before the next autosave
     * cannot bring back the session inventory. False if the save failed.
     */
    private boolean savePlayerData(Player player) {
        try {
            player.saveData();
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Failed to save player data for " + player.getName() + ": " + e.getMessage());
            return false;
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

    /** True while the player has a pending session, a resumed one, or a restored one whose file is not removed yet. */
    private boolean hasUnsettledSession(UUID playerId) {
        return pendingSessions.stream().anyMatch(session -> session.getPlayerId().equals(playerId))
                || sessionFiles.values().stream().anyMatch(loaded -> loaded.playerId().equals(playerId));
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
