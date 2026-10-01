package dev.ua.ikeepcalm.doublelife.listener;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerJoinListener implements Listener {

    private final DoubleLife plugin;

    public PlayerJoinListener(DoubleLife plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Check op whitelist on join (delayed so the player object is fully initialised)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // A player who left within the delay keeps their pending session for the next join.
            if (!player.isOnline()) {
                return;
            }
            plugin.getOpGuardService().checkAndDeop(player);
            plugin.getSessionManager().restoreSessionForPlayer(player);
        }, 20L);
    }

    /**
     * Ends an active session before the server saves the player, so the saved playerdata holds
     * the restored snapshot and the LuckPerms session nodes are removed while the user is loaded.
     * LOWEST so it runs before other plugins snapshot the inventory on quit.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuitDuringSession(PlayerQuitEvent event) {
        plugin.getSessionManager().handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Remove op on disconnect so it is not persisted in ops.json
        plugin.getOpGuardService().checkAndDeop(event.getPlayer());
    }
}