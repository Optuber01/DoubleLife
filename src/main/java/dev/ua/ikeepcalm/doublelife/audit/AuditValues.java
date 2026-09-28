package dev.ua.ikeepcalm.doublelife.audit;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts Bukkit objects into plain audit metadata values. Main thread only: every
 * method reads live Bukkit state, and the returned maps contain only plain values.
 */
public final class AuditValues {

    private static final NamespacedKey ITEM_UUID_KEY = NamespacedKey.fromString("circleofimagination:item_uuid");
    private static final NamespacedKey ITEM_PARENT_KEY = NamespacedKey.fromString("circleofimagination:item_parent");
    private static final int MAX_LISTED_STACKS = 40;

    private AuditValues() {
    }

    public static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    /** CoI tracked-item UUID, or null when the stack is untracked. */
    public static String itemUuid(ItemStack item) {
        return pdcString(item, ITEM_UUID_KEY);
    }

    /** CoI parent tracked-item UUID, or null when the stack has none. */
    public static String parentItemUuid(ItemStack item) {
        return pdcString(item, ITEM_PARENT_KEY);
    }

    private static String pdcString(ItemStack item, NamespacedKey key) {
        if (isEmpty(item) || key == null || !item.hasItemMeta()) {
            return null;
        }
        try {
            PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
            String value = pdc.get(key, PersistentDataType.STRING);
            return value == null || value.isBlank() ? null : value;
        } catch (RuntimeException wrongType) {
            return null;
        }
    }

    /** Single-stack metadata: material, amount, display_name, item_uuid, parent_item_uuid. */
    public static Map<String, Object> item(ItemStack item) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (isEmpty(item)) {
            return values;
        }
        values.put("material", item.getType().name());
        values.put("amount", item.getAmount());
        putIfNotNull(values, "display_name", displayName(item));
        putIfNotNull(values, "item_uuid", itemUuid(item));
        putIfNotNull(values, "parent_item_uuid", parentItemUuid(item));
        return values;
    }

    /** Plain-text custom name (max 64 chars), or null when the stack has none. */
    public static String displayName(ItemStack item) {
        if (isEmpty(item)) {
            return null;
        }
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.hasDisplayName() || meta.displayName() == null) {
                return null;
            }
            return AuditEmitter.bounded(PlainTextComponentSerializer.plainText().serialize(meta.displayName()), 64);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Location metadata as {@code world/x/y/z}, or with a key prefix such as {@code to_}. */
    public static Map<String, Object> location(Location location, String prefix) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (location == null || location.getWorld() == null) {
            return values;
        }
        values.put(prefix + "world", location.getWorld().getName());
        values.put(prefix + "x", location.getBlockX());
        values.put(prefix + "y", location.getBlockY());
        values.put(prefix + "z", location.getBlockZ());
        return values;
    }

    public static Map<String, Object> location(Location location) {
        return location(location, "");
    }

    /** Full player inventory contents: storage, armour and off-hand. */
    public static ItemStack[] contents(PlayerInventory inventory) {
        List<ItemStack> all = new ArrayList<>();
        addAll(all, inventory.getStorageContents());
        addAll(all, inventory.getArmorContents());
        all.add(inventory.getItemInOffHand());
        return all.toArray(new ItemStack[0]);
    }

    private static void addAll(List<ItemStack> into, ItemStack[] items) {
        if (items == null) return;
        for (ItemStack item : items) {
            if (item != null) into.add(item);
        }
    }

    /**
     * Digest of a set of stacks: a SHA-256 over each stack's serialized bytes, the stack and
     * item counts, a bounded "MATERIAL xN" list and the CoI tracked-item UUIDs it contains.
     */
    public static Map<String, Object> digest(ItemStack[] items, String prefix) {
        Map<String, Object> values = new LinkedHashMap<>();
        MessageDigest sha = sha256();
        List<String> listed = new ArrayList<>();
        List<String> uuids = new ArrayList<>();
        List<String> parents = new ArrayList<>();
        int stacks = 0;
        int count = 0;
        for (ItemStack item : items == null ? new ItemStack[0] : items) {
            if (isEmpty(item)) continue;
            stacks++;
            count += item.getAmount();
            update(sha, item);
            if (listed.size() < MAX_LISTED_STACKS) listed.add(item.getType().name() + " x" + item.getAmount());
            addIfNotNull(uuids, itemUuid(item));
            addIfNotNull(parents, parentItemUuid(item));
        }
        values.put(prefix + "stack_count", stacks);
        values.put(prefix + "item_count", count);
        putIfNotNull(values, prefix + "digest", sha == null ? null : HexFormat.of().formatHex(sha.digest()));
        values.put(prefix + "items", String.join(", ", listed));
        values.put(prefix + "item_uuids", String.join(",", uuids));
        values.put(prefix + "parent_item_uuids", String.join(",", parents));
        return values;
    }

    /**
     * Promotes the first tracked item of a multi-stack digest to the indexed
     * {@code item_uuid}/{@code parent_item_uuid} keys.
     */
    public static Map<String, Object> firstTracked(ItemStack[] items) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (ItemStack item : items == null ? new ItemStack[0] : items) {
            String uuid = itemUuid(item);
            if (uuid != null) {
                values.put("item_uuid", uuid);
                putIfNotNull(values, "parent_item_uuid", parentItemUuid(item));
                return values;
            }
        }
        return values;
    }

    private static void update(MessageDigest sha, ItemStack item) {
        if (sha == null) return;
        try {
            sha.update(item.serializeAsBytes());
        } catch (RuntimeException unserializable) {
            sha.update((item.getType().name() + "x" + item.getAmount()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            return null;
        }
    }

    private static void putIfNotNull(Map<String, Object> values, String key, Object value) {
        if (value != null) values.put(key, value);
    }

    private static void addIfNotNull(List<String> values, String value) {
        if (value != null) values.add(value);
    }
}
