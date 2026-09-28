package dev.ua.ikeepcalm.doublelife.audit;

import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.land.Land;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Lockable;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who owns the claim a container stands in (Lands soft hook) and whether the container carries a
 * vanilla lock. Read-only: never changes a claim or a lock. Main thread only. When Lands is absent
 * or its API fails, every lookup returns nothing and one warning is logged.
 */
public final class ClaimOwners {

    private final Plugin plugin;
    private boolean resolved;
    private LandsHook lands;

    public ClaimOwners(Plugin plugin) {
        this.plugin = plugin;
    }

    /** The claim at {@code location}, or null outside a claim, for virtual inventories or without Lands. */
    public Claim claimAt(Location location, UUID viewerId) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        LandsHook hook = hook();
        if (hook == null) {
            return null;
        }
        try {
            return hook.claimAt(location, viewerId);
        } catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().warning("Lands claim lookup failed; claim owners are no longer audited: " + failure);
            lands = null;
            return null;
        }
    }

    /** Claim and lock metadata for a container: claim_owner_uuid, claim_land_ulid, claim_viewer_trusted, locked. */
    public Map<String, Object> describe(Location location, InventoryHolder holder, UUID viewerId) {
        Map<String, Object> values = new LinkedHashMap<>();
        Claim claim = claimAt(location, viewerId);
        if (claim != null) {
            values.put("claim_owner_uuid", claim.ownerId());
            values.put("claim_land_ulid", claim.landId());
            values.put("claim_viewer_trusted", claim.viewerTrusted());
        }
        if (holder instanceof Lockable lockable) {
            values.put("locked", lockable.isLocked());
        }
        values.values().removeIf(java.util.Objects::isNull);
        return values;
    }

    private LandsHook hook() {
        if (!resolved) {
            resolved = true;
            if (Bukkit.getPluginManager().isPluginEnabled("Lands")) {
                try {
                    lands = LandsHook.create(plugin);
                } catch (RuntimeException | LinkageError failure) {
                    plugin.getLogger().warning("Lands API unavailable; claim owners are not audited: " + failure);
                }
            }
        }
        return lands;
    }

    /** Plain values describing one claim, as seen by one viewer. */
    public record Claim(UUID ownerId, String landId, boolean viewerTrusted) {
    }

    /** Isolates every Lands class reference so this plugin loads without Lands. */
    private static final class LandsHook {
        private final LandsIntegration integration;

        private LandsHook(LandsIntegration integration) {
            this.integration = integration;
        }

        static LandsHook create(Plugin plugin) {
            return new LandsHook(LandsIntegration.of(plugin));
        }

        Claim claimAt(Location location, UUID viewerId) {
            Area area = integration.getArea(location);
            if (area == null) {
                return null;
            }
            Land land = area.getLand();
            UUID owner = area.getOwnerUID() != null ? area.getOwnerUID() : land == null ? null : land.getOwnerUID();
            String landId = land == null || land.getULID() == null ? null : land.getULID().toString();
            boolean trusted = viewerId != null && (viewerId.equals(owner) || area.isTrusted(viewerId));
            return new Claim(owner, landId, trusted);
        }
    }
}
