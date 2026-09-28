package dev.ua.ikeepcalm.doublelife.audit;

import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.util.CommandNames;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditPrivacy;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Emits in-session activity rows (commands, item movement, gamemode, teleports, containers,
 * blocks) the moment they happen, so a crash or {@code /stop} cannot erase them.
 * Main thread only.
 */
public final class ActivityAuditor {

    public static final String ITEM_OUT = "doublelife.staff.admin_mode.item_out";
    public static final String ITEM_IN = "doublelife.staff.admin_mode.item_in";
    public static final String GAMEMODE = "doublelife.staff.gamemode.changed";
    public static final String TELEPORT = "doublelife.staff.admin_mode.teleport";
    public static final String CONTAINER_OPENED = "doublelife.staff.admin_mode.container_opened";
    public static final String BLOCKS = "doublelife.staff.admin_mode.blocks";

    private static final Set<String> SENSITIVE_COMMANDS = Set.of(
            "give", "i", "item", "lp", "luckperms", "perm", "perms", "permission", "permissions",
            "op", "deop", "co", "coreprotect", "core", "tp", "teleport", "tphere", "tpall", "gamemode", "gm",
            "gmc", "gms", "gmsp", "stop", "restart", "reload", "rl", "plugman", "sudo", "eco", "economy",
            "invsee", "ec", "enderchest", "enchant", "effect", "whitelist", "ban", "pardon", "execute", "coi");
    private static final Set<String> CHAT_COMMANDS = Set.of(
            "msg", "tell", "w", "whisper", "r", "reply", "m", "t", "pm", "me", "say", "mail", "helpop", "ac");
    private static final int MAX_TRACKED_DROPS = 2_048;

    private final AuditEmitter emitter;
    private final Supplier<List<Pattern>> sensitivePatterns;
    private final ItemBatcher batcher;
    /** Dropped Item entity UUID to the session drop it came from, so pickups by others are attributed. */
    private final Map<UUID, ItemMove> trackedDrops = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, ItemMove> eldest) {
            return size() > MAX_TRACKED_DROPS;
        }
    };
    /** Session id to tracked item UUIDs already reported as creative spawns (one row per item per session). */
    private final Map<UUID, Set<String>> reportedCreative = new HashMap<>();
    /** Player UUID to the DoubleLife-internal operation currently changing that player's state. */
    private final Map<UUID, String> systemContext = new ConcurrentHashMap<>();

    /** The most recent command dispatched by a player or the console; matched to COMMAND gamemode changes. */
    private CommandSource lastCommand;
    public ActivityAuditor(AuditEmitter emitter, Supplier<List<Pattern>> sensitivePatterns) {
        this.emitter = emitter;
        this.sensitivePatterns = sensitivePatterns;
        this.batcher = new ItemBatcher(this::emitBatch);
    }

    // ---------------------------------------------------------------- context

    /** Runs {@code action} while gamemode rows for the player are attributed to {@code context}. */
    public void withSystemContext(Player player, String context, Runnable action) {
        systemContext.put(player.getUniqueId(), context);
        try {
            action.run();
        } finally {
            systemContext.remove(player.getUniqueId());
        }
    }

    public void flushStale() {
        batcher.flushStale();
    }

    /** Emits every pending batch for the session; called before the exited row. */
    public void flushSession(SessionData session) {
        batcher.flushSession(session.getSessionId());
        reportedCreative.remove(session.getSessionId());
    }

    public void flushAll() {
        batcher.flushAll();
    }

    // ---------------------------------------------------------------- commands

    public void command(Player player, SessionData session, String raw, boolean cancelled) {
        CommandNames.Parsed parsed = CommandNames.parse(raw);
    /**
     * Remembers who issued the command being dispatched. Bukkit does not tell a
     * {@link PlayerGameModeChangeEvent} with cause COMMAND who ran the command, but the command
     * runs in the same tick right after this is recorded, so the change is attributed to it.
     * Only the label is kept, never the arguments.
     */
    public void noteCommand(UUID sourceId, String dispatcher, UUID sourceSessionId, String message) {
        String text = message == null ? "" : message.strip();
        int space = text.indexOf(' ');
        String label = space < 0 ? text : text.substring(0, space);
        lastCommand = new CommandSource(sourceId, dispatcher, sourceSessionId, label, Bukkit.getCurrentTick());
    }

    /** The command dispatched in the current tick, or null. */
    private CommandSource currentCommand() {
        CommandSource source = lastCommand;
        return source != null && source.tick() == Bukkit.getCurrentTick() ? source : null;
    }

    private record CommandSource(UUID sourceId, String dispatcher, UUID sessionId, String label, int tick) {
    }

        boolean sensitive = isSensitive(parsed, raw);
        UUID target = resolveTarget(player, parsed);
        boolean chat = parsed.names().stream().anyMatch(CHAT_COMMANDS::contains);
        emitter.emit(SessionAuditor.sessionRow(SessionAuditor.ACTION, player, session)
                .outcome(cancelled ? AuditOutcome.DENIED : AuditOutcome.ATTEMPTED)
                .risk(sensitive ? AuditRisk.HIGH : AuditRisk.NORMAL)
                .privacy(chat ? AuditPrivacy.CHAT_CONTENT : AuditPrivacy.STAFF_RESTRICTED)
                .target(target)
                .reason(cancelled ? "cancelled" : null)
                .put("kind", "command")
                .put("dispatcher", "player")
                .put("command", raw)
                .put("command_name", parsed.label())
                .put("cancelled", cancelled)
                .put("sensitive", sensitive)
                .putAll(AuditValues.location(player.getLocation()))
                .build());
        if (!cancelled && parsed.is("give") && givesToOther(parsed, target)) {
            giveToOther(player, session, parsed, raw, target);
        }
    }

    /** /give naming another online player, or a selector other than @s (which may include others). */
    private static boolean givesToOther(CommandNames.Parsed parsed, UUID target) {
        String first = parsed.firstArg();
        return target != null || (first != null && first.startsWith("@") && !first.startsWith("@s"));
    }

    private void giveToOther(Player player, SessionData session, CommandNames.Parsed parsed, String raw, UUID target) {
        emitter.emit(SessionAuditor.sessionRow(ITEM_OUT, player, session)
                .outcome(AuditOutcome.ATTEMPTED)
                .risk(AuditRisk.HIGH)
                .target(target)
                .put("kind", "give_command")
                .put("target_arg", parsed.firstArg())
                .put("command", raw)
                .put("material", parsed.args().size() > 1 ? parsed.args().get(1) : null)
                .put("amount", parsed.args().size() > 2 ? parsed.args().get(2) : "1")
                .putAll(AuditValues.location(player.getLocation()))
                .build());
    }

    private boolean isSensitive(CommandNames.Parsed parsed, String raw) {
        if (parsed.names().stream().anyMatch(SENSITIVE_COMMANDS::contains)) {
            return true;
        }
        for (Pattern pattern : sensitivePatterns.get()) {
            if (pattern.matcher(raw).find()) return true;
        }
        return false;
    }

    /** The first argument (of the first four) naming an online player other than the issuer. */
    private static UUID resolveTarget(Player issuer, CommandNames.Parsed parsed) {
        for (int i = 0; i < Math.min(4, parsed.args().size()); i++) {
            Player named = Bukkit.getPlayerExact(parsed.args().get(i));
            if (named != null && !named.getUniqueId().equals(issuer.getUniqueId())) {
                return named.getUniqueId();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- items

    /**
     * Records an item movement. Tracked stacks (with a CoI item UUID) produce one row each;
     * untracked stacks are folded into a batch row per window.
     */
    public void item(ItemMove move) {
        if (AuditValues.isEmpty(move.item())) {
            return;
        }
        if (AuditValues.itemUuid(move.item()) == null) {
            batcher.add(batchKey(move), move.item().getType().name(), move.amount(), move.location(), move.delivered());
            return;
        }
        if (move.kind().equals("creative_spawn") && !firstCreativeReport(move)) {
            return;
        }
        emitter.emit(SessionAuditor.sessionRow(move.eventType(), move.player(), move.session())
                .risk(move.eventType().equals(ITEM_OUT) ? AuditRisk.HIGH : AuditRisk.NORMAL)
                .target(move.targetId())
                .put("kind", move.kind())
                .putAll(AuditValues.item(move.item()))
                .put("amount", move.amount())
                .put("container_type", move.containerType())
                .put("owner_uuid", move.ownerId())
                .put("delivered", move.delivered())
                .putAll(AuditValues.location(move.location()))
                .build());
    }

    private boolean firstCreativeReport(ItemMove move) {
        return reportedCreative.computeIfAbsent(move.session().getSessionId(), id -> new HashSet<>())
                .add(AuditValues.itemUuid(move.item()));
    }

    private static ItemBatcher.BatchKey batchKey(ItemMove move) {
        Location location = move.location();
        String containerKey = move.containerType() == null || location == null || location.getWorld() == null ? ""
                : location.getWorld().getName() + ':' + location.getBlockX() + ':' + location.getBlockY() + ':' + location.getBlockZ();
        return new ItemBatcher.BatchKey(move.eventType(), move.session().getSessionId(), move.player().getUniqueId(),
                move.session().getMode().name(), move.kind(), move.containerType(), containerKey, move.ownerId());
    }

    /** A session player's drop reached the world; remembers it so a pickup by someone else is attributed. */
    public void trackDrop(UUID itemEntityId, ItemMove move) {
        trackedDrops.put(itemEntityId, move);
    }

    /** Called for every item pickup; emits a row only when the item was dropped by a session player. */
    public void pickedUp(UUID itemEntityId, UUID receiverId, String receiverKind, Location location) {
        ItemMove drop = trackedDrops.remove(itemEntityId);
        if (drop == null || drop.player().getUniqueId().equals(receiverId)) {
            return;
        }
        emitter.emit(SessionAuditor.sessionRow(ITEM_OUT, drop.player(), drop.session())
                .risk(AuditRisk.HIGH)
                .target(receiverId)
                .put("kind", "drop_picked_up")
                .put("receiver_kind", receiverKind)
                .putAll(AuditValues.item(drop.item()))
                .put("amount", drop.amount())
                .putAll(AuditValues.location(location))
                .build());
    }

    public void blocks(Player player, SessionData session, String kind, String material, Location location) {
        batcher.add(new ItemBatcher.BatchKey(BLOCKS, session.getSessionId(), player.getUniqueId(),
                session.getMode().name(), kind, null, "", null), material, 1, location, null);
    }

    private void emitBatch(ItemBatcher.Batch batch) {
        ItemBatcher.BatchKey key = batch.key;
        AuditEmitter.Builder row = AuditEmitter.row(key.eventType())
                .actor(key.actorId())
                .correlation(key.sessionId())
                .business(key.sessionId().toString())
                .risk(key.eventType().equals(ITEM_OUT) ? AuditRisk.HIGH : AuditRisk.NORMAL)
                .put("session_id", key.sessionId())
                .put("mode", key.mode())
                .put("kind", key.kind())
                .put("batched", true)
                .put("materials", batch.materials())
                .put("total_amount", batch.total)
                .put("event_count", batch.events)
                .put("window_start_ms", batch.startedAt)
                .put("window_end_ms", batch.lastAt)
                .put("container_type", key.containerType())
                .put("owner_uuid", key.ownerId());
        if (batch.delivered + batch.undelivered > 0) {
            row.put("delivered_amount", batch.delivered).put("undelivered_amount", batch.undelivered);
        }
        putBounds(row, batch);
        emitter.emit(row.build());
    }

    private static void putBounds(AuditEmitter.Builder row, ItemBatcher.Batch batch) {
        if (batch.world == null) return;
        row.put("world", batch.world).put("x", batch.firstX).put("y", batch.firstY).put("z", batch.firstZ)
                .put("min_x", batch.minX).put("min_y", batch.minY).put("min_z", batch.minZ)
                .put("max_x", batch.maxX).put("max_y", batch.maxY).put("max_z", batch.maxZ);
    }

    // ---------------------------------------------------------------- state rows

    /**
     * Gamemode change of a staff member or a session player; {@code session} may be null.
     * Actor: nobody for DoubleLife's own changes, the command issuer (matched in the same tick,
     * empty for console and command blocks) for COMMAND, the player otherwise.
     */
    public void gamemode(Player player, SessionData session, PlayerGameModeChangeEvent event) {
        GameMode from = player.getGameMode();
        String context = systemContext.get(player.getUniqueId());
        UUID sessionId = session == null ? null : session.getSessionId();
        emitter.emit(AuditEmitter.row(GAMEMODE)
                .risk(event.getNewGameMode() == GameMode.CREATIVE ? AuditRisk.HIGH : AuditRisk.NORMAL)
                .subject(player.getUniqueId())
                .actor(actor)
        boolean byCommand = context == null && event.getCause() == PlayerGameModeChangeEvent.Cause.COMMAND;
        CommandSource source = byCommand ? currentCommand() : null;
        UUID actor = context != null ? null : byCommand ? (source == null ? null : source.sourceId()) : player.getUniqueId();
                .correlation(sessionId)
                .business(sessionId == null ? player.getUniqueId().toString() : sessionId.toString())
                .put("session_id", sessionId)
                .put("in_session", session != null)
                .put("from", from.name())
                .put("to", event.getNewGameMode().name())
                .put("cause", event.getCause().name())
                .put("actor_type", context == null ? event.getCause().name().toLowerCase() : context)
                .putAll(AuditValues.location(player.getLocation()))
                .build());
    }

                .put("source_dispatcher", byCommand ? (source == null ? "unknown" : source.dispatcher()) : null)
                .put("source_command_name", source == null ? null : CommandNames.parse(source.label()).label())
                .put("source_session_id", source == null ? null : source.sessionId())
    public void teleport(Player player, SessionData session, PlayerTeleportEvent event) {
        Location to = event.getTo();
        Player nearest = nearestOther(player, to);
        emitter.emit(SessionAuditor.sessionRow(TELEPORT, player, session)
                .target(nearest == null ? null : nearest.getUniqueId())
                .put("cause", event.getCause().name())
                .putAll(AuditValues.location(event.getFrom(), "from_"))
                .putAll(AuditValues.location(to))
                .put("nearest_player_uuid", nearest == null ? null : nearest.getUniqueId())
                .put("nearest_player_distance", nearest == null ? null : (int) Math.round(nearest.getLocation().distance(to)))
                .build());
    }

    private static Player nearestOther(Player self, Location to) {
        if (to == null || to.getWorld() == null) return null;
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player other : to.getWorld().getPlayers()) {
            if (other.getUniqueId().equals(self.getUniqueId())) continue;
            double distance = other.getLocation().distanceSquared(to);
            if (distance < bestDistance) {
                best = other;
                bestDistance = distance;
            }
        }
        return best;
    }

    public void containerOpened(Player player, SessionData session, Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        Location location = inventory.getLocation() != null ? inventory.getLocation() : player.getLocation();
        emitter.emit(SessionAuditor.sessionRow(CONTAINER_OPENED, player, session)
                .put("inventory_type", inventory.getType().name())
                .put("holder_type", holder == null ? "none" : holder.getClass().getSimpleName())
                .put("size", inventory.getSize())
                .put("owner_uuid", otherOwner(player, holder))
                .putAll(AuditValues.location(location))
                .build());
    }

    /** UUID of the human owning a PLAYER or ENDER_CHEST view when it is not the viewer (invsee, /ec other). */
    public static UUID otherOwner(Player viewer, InventoryHolder holder) {
        if (holder instanceof HumanEntity human && !human.getUniqueId().equals(viewer.getUniqueId())) {
            return human.getUniqueId();
        }
        return null;
    }

    /**
     * One item movement. {@code delivered} is only set for drops (true when the Item entity is
     * valid and alive one tick later).
     */
    public record ItemMove(String eventType, Player player, SessionData session, String kind, ItemStack item,
                           int amount, Location location, String containerType, UUID ownerId, UUID targetId,
                           Boolean delivered) {

        public static ItemMove out(Player player, SessionData session, String kind, ItemStack item, int amount,
                                   Location location) {
            return new ItemMove(ITEM_OUT, player, session, kind, item, amount, location, null, null, null, null);
        }

        public ItemMove withContainer(String type, UUID owner) {
            return new ItemMove(eventType, player, session, kind, item, amount, location, type, owner, targetId, delivered);
        }

        public ItemMove asIn() {
            return new ItemMove(ITEM_IN, player, session, kind, item, amount, location, containerType, ownerId, targetId, delivered);
        }

        public ItemMove withDelivered(boolean value) {
            return new ItemMove(eventType, player, session, kind, item, amount, location, containerType, ownerId, targetId, value);
        }
    }
}
