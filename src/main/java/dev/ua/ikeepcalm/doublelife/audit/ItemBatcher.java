package dev.ua.ikeepcalm.doublelife.audit;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Folds high-frequency, untracked item and block activity into one row per
 * session/kind/container per window, so a staff member reorganising a chest or
 * building does not produce a row per click or per block. Main thread only.
 */
final class ItemBatcher {

    static final long WINDOW_MILLIS = 10_000L;
    private static final int MAX_MATERIALS = 48;

    private final Map<String, Batch> batches = new LinkedHashMap<>();
    private final Consumer<Batch> sink;

    ItemBatcher(Consumer<Batch> sink) {
        this.sink = sink;
    }

    /** Adds {@code amount} of {@code material}; flushes the previous batch for the key if its window elapsed. */
    void add(BatchKey key, String material, int amount, Location location, Boolean delivered) {
        long now = System.currentTimeMillis();
        String id = key.id();
        Batch batch = batches.get(id);
        if (batch != null && now - batch.startedAt >= WINDOW_MILLIS) {
            sink.accept(batches.remove(id));
            batch = null;
        }
        if (batch == null) {
            batch = new Batch(key, now);
            batches.put(id, batch);
        }
        batch.add(material, amount, location, delivered, now);
    }

    /** Flushes every batch whose window elapsed (called from a periodic task). */
    void flushStale() {
        long now = System.currentTimeMillis();
        flushWhere(batch -> now - batch.startedAt >= WINDOW_MILLIS);
    }

    /** Flushes every batch that belongs to the given session (session end, quit, shutdown). */
    void flushSession(UUID sessionId) {
        flushWhere(batch -> batch.key.sessionId().equals(sessionId));
    }

    void flushAll() {
        flushWhere(batch -> true);
    }

    private void flushWhere(java.util.function.Predicate<Batch> condition) {
        List<Batch> ready = new ArrayList<>();
        Iterator<Batch> iterator = batches.values().iterator();
        while (iterator.hasNext()) {
            Batch batch = iterator.next();
            if (condition.test(batch)) {
                ready.add(batch);
                iterator.remove();
            }
        }
        ready.forEach(sink);
    }

    /** Identity of a batch: one per session, event, kind and container. */
    record BatchKey(String eventType, UUID sessionId, UUID actorId, String mode, String kind,
                    String containerType, String containerKey, UUID ownerId) {
        String id() {
            return eventType + '|' + sessionId + '|' + kind + '|' + containerType + '|' + containerKey;
        }
    }

    /** Accumulated counts for one window. Plain values only once built. */
    static final class Batch {
        final BatchKey key;
        final long startedAt;
        long lastAt;
        final Map<String, Integer> counts = new TreeMap<>();
        int total;
        int events;
        int delivered;
        int undelivered;
        String world;
        int firstX, firstY, firstZ;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        Batch(BatchKey key, long startedAt) {
            this.key = key;
            this.startedAt = startedAt;
        }

        void add(String material, int amount, Location location, Boolean wasDelivered, long now) {
            if (counts.containsKey(material) || counts.size() < MAX_MATERIALS) {
                counts.merge(material, amount, Integer::sum);
            } else {
                counts.merge("OTHER", amount, Integer::sum);
            }
            total += amount;
            events++;
            lastAt = now;
            if (wasDelivered != null) {
                if (wasDelivered) delivered += amount; else undelivered += amount;
            }
            track(location);
        }

        private void track(Location location) {
            if (location == null || location.getWorld() == null) return;
            int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
            if (world == null) {
                world = location.getWorld().getName();
                firstX = x; firstY = y; firstZ = z;
            }
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }

        String materials() {
            StringBuilder text = new StringBuilder();
            counts.forEach((material, count) -> {
                if (!text.isEmpty()) text.append(", ");
                text.append(material).append(" x").append(count);
            });
            return text.toString();
        }
    }
}
