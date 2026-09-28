package dev.ua.ikeepcalm.doublelife.audit;

import dev.ua.ikeepcalm.doublelife.config.PluginConfig;
import dev.ua.ikeepcalm.doublelife.domain.model.RiskAssessment;
import dev.ua.ikeepcalm.doublelife.domain.model.PlayerState;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.domain.model.source.DoubleLifeMode;
import dev.ua.ikeepcalm.doublelife.domain.model.source.RiskLevel;
import dev.ua.ikeepcalm.doublelife.util.CommandNames;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Emits the session lifecycle, permission and op rows. All methods run on the main thread;
 * {@link #permissionChanged} is the only one that may be called from a LuckPerms thread and
 * therefore takes plain values only.
 */
public final class SessionAuditor {

    public static final String ENTERED = "doublelife.staff.admin_mode.entered";
    public static final String EXITED = "doublelife.staff.admin_mode.exited";
    public static final String RESUMED = "doublelife.staff.admin_mode.resumed";
    public static final String QUIT = "doublelife.staff.admin_mode.quit_during_session";
    public static final String EXTENDED = "doublelife.staff.admin_mode.extended";
    public static final String ACTION = "doublelife.staff.admin_mode.action";
    public static final String PERMISSION_GRANTED = "doublelife.staff.permission.granted";
    public static final String PERMISSION_REVOKED = "doublelife.staff.permission.revoked";
    public static final String OP_REVOKED = "doublelife.staff.op.revoked";
    public static final String OP_BLOCKED = "doublelife.staff.op.blocked";
    public static final String RESTRICTED_BLOCKED = "doublelife.staff.command.restricted_blocked";
    public static final String CONFIG_RELOADED = "doublelife.system.config.reloaded";

    private final AuditEmitter emitter;

    public SessionAuditor(AuditEmitter emitter) {
        this.emitter = emitter;
    }

    /** Base builder for a row that belongs to a session: actor, correlation, business id and mode. */
    static AuditEmitter.Builder sessionRow(String event, Player player, SessionData session) {
        return AuditEmitter.row(event)
                .actor(player.getUniqueId())
                .correlation(session.getSessionId())
                .business(session.getSessionId().toString())
                .put("session_id", session.getSessionId())
                .put("mode", session.getMode().name());
    }

    /** Emitted once the snapshot is taken, the inventory cleared and admin mode applied. */
    public void entered(Player player, SessionData session, String trigger, long maxMinutes,
                        List<String> entryCommands, AdminModeResult grant) {
        ItemStack[] snapshot = snapshotContents(session);
        emitter.emit(sessionRow(ENTERED, player, session)
                .outcome(AuditOutcome.COMMITTED)
                .risk(session.getMode() == DoubleLifeMode.TURBO ? AuditRisk.HIGH : AuditRisk.NORMAL)
                .put("trigger", trigger)
                .put("max_minutes", maxMinutes)
                .put("entry_commands", String.join(" | ", entryCommands))
                .put("granted_nodes", grant == null ? "" : String.join(",", grant.nodes()))
                .put("lp_user_loaded", grant == null ? null : grant.lpUserLoaded())
                .put("gamemode", player.getGameMode().name())
                .putAll(AuditValues.location(player.getLocation()))
                .putAll(AuditValues.digest(snapshot, "snapshot_"))
                .putAll(AuditValues.firstTracked(snapshot))
                .build());
    }

    /**
     * Emitted after the snapshot was restored and admin mode removed.
     * {@code before} carries the inventory digest and location captured just before the restore.
     */
    public void exited(Player player, SessionData session, ExitDetails details, RiskAssessment risk) {
        ItemStack[] after = AuditValues.contents(player.getInventory());
        emitter.emit(sessionRow(EXITED, player, session)
                .outcome(details.restoreOk() ? AuditOutcome.COMMITTED : AuditOutcome.FAILED)
                .risk(risk != null && risk.getLevel() == RiskLevel.HIGH ? AuditRisk.HIGH : AuditRisk.NORMAL)
                .reason(details.restoreOk() ? null : "restore_failed")
                .put("reason", details.reason())
                .put("player_online", player.isOnline())
                .put("duration_s", session.getDuration().getSeconds())
                .put("extension_min", session.getExtensionMinutes())
                .put("activity_count", session.getActivities().size())
                .put("restore_ok", details.restoreOk())
                .put("perms_removed", String.join(",", details.permsRemoved()))
                .put("deop_applied", details.deopApplied())
                .put("risk_level", risk == null ? null : risk.getLevel().name())
                .put("risk_score", risk == null ? null : risk.getScore())
                .putAll(details.before())
                .putAll(AuditValues.digest(after, "after_"))
                .build());
    }

    /** Captures the pre-restore inventory digest, tracked item and location for {@link #exited}. */
    public Map<String, Object> captureBeforeRestore(Player player) {
        ItemStack[] before = AuditValues.contents(player.getInventory());
        Map<String, Object> values = new LinkedHashMap<>();
        values.putAll(AuditValues.location(player.getLocation()));
        values.put("gamemode_before", player.getGameMode().name());
        values.putAll(AuditValues.digest(before, "before_"));
        values.putAll(AuditValues.firstTracked(before));
        return values;
    }

    /** {@code outcome} is resumed, expired, skipped_active or failed. */
    public void resumed(Player player, SessionData session, String outcome, List<String> reappliedNodes, long elapsedMinutes) {
        AuditOutcome auditOutcome = switch (outcome) {
            case "resumed" -> AuditOutcome.COMMITTED;
            case "failed" -> AuditOutcome.FAILED;
            default -> AuditOutcome.CANCELLED;
        };
        emitter.emit(sessionRow(RESUMED, player, session)
                .outcome(auditOutcome)
                .reason(auditOutcome == AuditOutcome.COMMITTED ? null : outcome)
                .put("outcome", outcome)
                .put("reapplied_nodes", String.join(",", reappliedNodes))
                .put("elapsed_min", elapsedMinutes)
                .put("inventory_cleared", false)
                .put("restored_activity_count", session.getActivities().size())
                .putAll(AuditValues.location(player.getLocation()))
                .build());
    }

    /** Emitted from PlayerQuitEvent before the session is ended and the snapshot restored. */
    public void quitDuringSession(Player player, SessionData session, long minutesRemaining) {
        ItemStack[] contents = AuditValues.contents(player.getInventory());
        emitter.emit(sessionRow(QUIT, player, session)
                .risk(AuditRisk.HIGH)
                .put("gamemode", player.getGameMode().name())
                .put("minutes_remaining", minutesRemaining)
                .putAll(AuditValues.location(player.getLocation()))
                .putAll(AuditValues.digest(contents, ""))
                .putAll(AuditValues.firstTracked(contents))
                .build());
    }

    public void extended(Player player, SessionData session, int addedMinutes, long newTotalMinutes, boolean applied) {
        emitter.emit(sessionRow(EXTENDED, player, session)
                .outcome(applied ? AuditOutcome.COMMITTED : AuditOutcome.DENIED)
                .reason(applied ? null : "cap_exceeded")
                .put("added_min", addedMinutes)
                .put("new_total_min", newTotalMinutes)
                .put("lp_expiry_updated", false)
                .build());
    }

    /** A console-dispatched entry command run on the player's behalf at session start. */
    public void entryCommand(Player player, SessionData session, String command, boolean dispatched) {
        emitter.emit(sessionRow(ACTION, player, session)
                .outcome(dispatched ? AuditOutcome.OBSERVED : AuditOutcome.FAILED)
                .risk(AuditRisk.HIGH)
                .put("kind", "command")
                .put("dispatcher", "console")
                .put("command", command)
                .put("dispatch_result", dispatched)
                .putAll(AuditValues.location(player.getLocation()))
                .build());
    }

    /**
     * One LuckPerms node grant or revoke. Called after the LuckPerms save completed (or failed),
     * possibly off the main thread: every argument is a plain value.
     */
    public void permissionChanged(PermissionChange change) {
        boolean grant = change.grant();
        AuditOutcome outcome = switch (change.saveState()) {
            case "saved" -> AuditOutcome.COMMITTED;
            case "pending" -> AuditOutcome.ATTEMPTED;
            default -> AuditOutcome.FAILED;
        };
        emitter.emit(AuditEmitter.row(grant ? PERMISSION_GRANTED : PERMISSION_REVOKED)
                .outcome(outcome)
                .risk(AuditRisk.HIGH)
                .subject(change.subjectId())
                .correlation(change.sessionId())
                .business(change.sessionId() == null ? null : change.sessionId().toString())
                .reason(outcome == AuditOutcome.COMMITTED ? null : change.saveState())
                .put("session_id", change.sessionId())
                .put("actor_type", "system")
                .put("action", grant ? "grant" : "revoke")
                .put("node", change.node())
                .put("value", change.value())
                .put("expiry", change.expiry())
                .put("lp_result", change.lpResult())
                .build());
    }

    /** {@code reason} is whitelist or session_end. */
    public void opRevoked(Player player, String reason, boolean wasOp, UUID sessionId) {
        emitter.emit(AuditEmitter.row(OP_REVOKED)
                .outcome(wasOp ? AuditOutcome.COMMITTED : AuditOutcome.OBSERVED)
                .risk(wasOp ? AuditRisk.HIGH : AuditRisk.NORMAL)
                .subject(player.getUniqueId())
                .correlation(sessionId)
                .business(sessionId == null ? player.getUniqueId().toString() : sessionId.toString())
                .put("session_id", sessionId)
                .put("reason", reason)
                .put("was_op", wasOp)
                .putAll(AuditValues.location(player.getLocation()))
                .build());
    }

    public void opBlocked(Player issuer, String targetName, String rawCommand) {
        emitter.emit(AuditEmitter.row(OP_BLOCKED)
                .outcome(AuditOutcome.DENIED)
                .risk(AuditRisk.HIGH)
                .actor(issuer.getUniqueId())
                .target(resolveUuid(targetName))
                .reason("not_whitelisted")
                .put("target_name", targetName)
                .put("command", rawCommand)
                .putAll(AuditValues.location(issuer.getLocation()))
                .build());
    }

    public void restrictedCommandBlocked(Player player, CommandNames.Parsed command, String rawCommand, String group) {
        emitter.emit(AuditEmitter.row(RESTRICTED_BLOCKED)
                .outcome(AuditOutcome.DENIED)
                .actor(player.getUniqueId())
                .reason("session_required")
                .put("command_name", command.label())
                .put("command", rawCommand)
                .put("group", group)
                .putAll(AuditValues.location(player.getLocation()))
                .build());
    }

    public void configReloaded(CommandSender sender, PluginConfig config) {
        boolean console = !(sender instanceof Player);
        emitter.emit(AuditEmitter.row(CONFIG_RELOADED)
                .outcome(AuditOutcome.COMMITTED)
                .actor(sender instanceof Player player ? player.getUniqueId() : null)
                .put("actor_type", console ? "console" : "player")
                .put("actor_name", console ? sender.getName() : null)
                .put("op_whitelist_enabled", config.isOpWhitelistEnabled())
                .put("temporary_permissions", String.join(",", config.getTemporaryPermissions()))
                .put("entry_commands", String.join(" | ", config.getEntryCommands()))
                .put("max_minutes", config.getMaxDuration())
                .build());
    }

    /** Storage, armour and off-hand stacks held by the start snapshot (runtime copy). */
    private static ItemStack[] snapshotContents(SessionData session) {
        PlayerState state = session.getSavedState();
        List<ItemStack> items = new ArrayList<>();
        if (state != null) {
            addNonNull(items, state.getInventory());
            addNonNull(items, state.getArmor());
            addNonNull(items, new ItemStack[]{state.getOffHand()});
        }
        return items.toArray(new ItemStack[0]);
    }

    private static void addNonNull(List<ItemStack> into, ItemStack[] items) {
        if (items == null) return;
        for (ItemStack item : items) {
            if (item != null) into.add(item);
        }
    }

    /** Resolves a typed player name to a UUID without a blocking profile lookup. */
    static UUID resolveUuid(String name) {
        if (name == null || name.isBlank() || name.startsWith("@")) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    /** Result of applying TURBO nodes: whether LuckPerms had the user loaded, and the node labels. */
    public record AdminModeResult(boolean lpUserLoaded, List<String> nodes) {
    }

    /** Plain-value description of a finished session end, collected by SessionManager. */
    public record ExitDetails(String reason, boolean restoreOk, List<String> permsRemoved,
                              boolean deopApplied, Map<String, Object> before) {
    }

    /** {@code saveState} is saved, pending (save still running at shutdown), failed or lp_user_not_loaded. */
    public record PermissionChange(UUID subjectId, UUID sessionId, boolean grant, String node, boolean value,
                                   String expiry, String lpResult, String saveState) {
    }
}
