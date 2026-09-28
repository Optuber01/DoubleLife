package dev.ua.ikeepcalm.doublelife.listener;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.audit.ActivityAuditor;
import dev.ua.ikeepcalm.doublelife.audit.ActivityAuditor.ItemMove;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.util.StaffGroups;
import org.bukkit.Location;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * Audit-only listener: observes at MONITOR and never changes the event. Session activity
 * rows are emitted as they happen. Item movement between the player and containers lives in
 * {@link InventoryAuditListener}.
 */
public class AuditListener implements Listener {

    private static final Set<PlayerTeleportEvent.TeleportCause> AUDITED_TELEPORTS = Set.of(
            PlayerTeleportEvent.TeleportCause.COMMAND,
            PlayerTeleportEvent.TeleportCause.PLUGIN,
            PlayerTeleportEvent.TeleportCause.SPECTATE);

    private final DoubleLife plugin;

    public AuditListener(DoubleLife plugin) {
        this.plugin = plugin;
    }

    private ActivityAuditor auditor() {
        return plugin.getActivityAuditor();
    }

    private SessionData session(Player player) {
        return plugin.getSessionManager().getSession(player);
    }

    /** Not ignoreCancelled: blocked commands are recorded as DENIED. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        SessionData session = session(event.getPlayer());
        if (!event.isCancelled()) {
            auditor().noteCommand(event.getPlayer().getUniqueId(), "player",
                    session == null ? null : session.getSessionId(), event.getMessage());
        }
        if (session != null) {
            auditor().command(event.getPlayer(), session, event.getMessage(), event.isCancelled());
        }
    }

    /** Console, RCON and command block commands, so a COMMAND gamemode change can name its dispatcher. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        auditor().noteCommand(null, dispatcher(event.getSender()), null, event.getCommand());
    }

    private static String dispatcher(CommandSender sender) {
        if (sender instanceof RemoteConsoleCommandSender) return "rcon";
        if (sender instanceof ConsoleCommandSender) return "console";
        if (sender instanceof BlockCommandSender) return "command_block";
        if (sender instanceof Entity) return "entity";
        return "other";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        SessionData session = session(player);
        if (session != null || player.hasPermission("doublelife.use") || !StaffGroups.of(plugin, player).isEmpty()) {
            auditor().gamemode(player, session, event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        SessionData session = session(event.getPlayer());
        if (session != null && AUDITED_TELEPORTS.contains(event.getCause())) {
            auditor().teleport(event.getPlayer(), session, event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        SessionData session = session(event.getPlayer());
        if (session != null) {
            auditor().blocks(event.getPlayer(), session, "place",
                    event.getBlockPlaced().getType().name(), event.getBlockPlaced().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        SessionData session = session(event.getPlayer());
        if (session != null) {
            auditor().blocks(event.getPlayer(), session, "break",
                    event.getBlock().getType().name(), event.getBlock().getLocation());
        }
    }

    /** A drop only counts as delivered if the Item entity is valid and alive one tick later. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        SessionData session = session(player);
        if (session == null) return;
        Item entity = event.getItemDrop();
        ItemStack stack = entity.getItemStack().clone();
        ItemMove move = ItemMove.out(player, session, "drop", stack, stack.getAmount(), player.getLocation().clone());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            boolean delivered = entity.isValid() && !entity.isDead();
            auditor().item(move.withDelivered(delivered));
            if (delivered) {
                auditor().trackDrop(entity.getUniqueId(), move.withDelivered(true));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        Item item = event.getItem();
        auditor().pickedUp(item.getUniqueId(), event.getEntity().getUniqueId(),
                event.getEntity().getType().name(), item.getLocation());
        if (event.getEntity() instanceof Player player) {
            SessionData session = session(player);
            if (session != null) {
                ItemStack stack = item.getItemStack();
                auditor().item(ItemMove.out(player, session, "pickup", stack, stack.getAmount(), item.getLocation()).asIn());
            }
        }
    }

    /** A hopper (or hopper minecart) collecting a session drop. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        Location location = event.getInventory().getLocation() != null ? event.getInventory().getLocation() : item.getLocation();
        auditor().pickedUp(item.getUniqueId(), null, event.getInventory().getType().name(), location);
    }

    /** Item placed into an empty item frame; verified one tick later. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame)) return;
        Player player = event.getPlayer();
        SessionData session = session(player);
        ItemStack hand = player.getInventory().getItem(event.getHand());
        if (session == null || hand == null || hand.getType().isAir() || !frame.getItem().getType().isAir()) return;
        ItemStack placed = hand.asOne();
        ItemMove move = ItemMove.out(player, session, "frame_put", placed, 1, frame.getLocation().clone())
                .withContainer(frame.getType().name(), null);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (frame.isValid() && !frame.getItem().getType().isAir()) {
                auditor().item(move.withDelivered(true));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        Player player = event.getPlayer();
        SessionData session = session(player);
        if (session == null) return;
        Location location = event.getRightClicked().getLocation();
        String type = event.getRightClicked().getType().name();
        ItemStack given = event.getPlayerItem();
        ItemStack taken = event.getArmorStandItem();
        if (!given.getType().isAir()) {
            auditor().item(ItemMove.out(player, session, "armor_stand_put", given.clone(), given.getAmount(), location)
                    .withContainer(type, null));
        }
        if (!taken.getType().isAir()) {
            auditor().item(ItemMove.out(player, session, "armor_stand_take", taken.clone(), taken.getAmount(), location)
                    .withContainer(type, null).asIn());
        }
    }
}
