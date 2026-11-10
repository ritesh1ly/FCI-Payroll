package in.gov.fci.payroll.platform.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * A Caffeine cache of one {@link CacheRegions region}: an entry is served only while the region's
 * version is the one it was loaded under, so an approved change on any instance replaces it on the
 * next read after that instance's version check; {@code maxAge} is the periodic full reload. The
 * version is taken BEFORE loading, so an entry can only be newer than its version, never older (a
 * load racing a change is reloaded once more, never served stale).
 *
 * @param <K> key
 * @param <V> value (never null)
 */
public final class VersionedCache<K, V> {

    private record Entry<V>(long version, V value) {}

    private final String region;
    private final CacheRegions regions;
    private final Cache<K, Entry<V>> cache;

    private VersionedCache(String region, CacheRegions regions, long maximumSize, Duration maxAge) {
        this.region = region;
        this.regions = regions;
        this.cache =
                Caffeine.newBuilder().maximumSize(maximumSize).expireAfterWrite(maxAge).build();
    }

    /** A cache of {@code region} with the configured maximum age. */
    public static <K, V> VersionedCache<K, V> of(
            String region, CacheRegions regions, long maximumSize) {
        return new VersionedCache<>(region, regions, maximumSize, regions.properties().maxAge());
    }

    /** As {@link #of}, with a shorter maximum age for entries that must also expire by time. */
    public static <K, V> VersionedCache<K, V> of(
            String region, CacheRegions regions, long maximumSize, Duration maxAge) {
        Duration age =
                maxAge.compareTo(regions.properties().maxAge()) < 0
                        ? maxAge
                        : regions.properties().maxAge();
        return new VersionedCache<>(region, regions, maximumSize, age);
    }

    /**
     * The current value of {@code key}, loading it (once, per key) if absent or of an older
     * version.
     */
    public V get(K key, Function<K, V> loader) {
        if (regions.changedInCurrentTransaction(region)) {
            // This transaction changed the region: it reads its own uncommitted data, not cached.
            return loader.apply(key);
        }
        long version = regions.version(region);
        return cache.asMap()
                .compute(
                        key,
                        (k, old) ->
                                old != null && old.version() == version
                                        ? old
                                        : new Entry<>(version, loader.apply(k)))
                .value();
    }

    /** The value of {@code key} if cached under the current version. */
    public Optional<V> getIfCurrent(K key) {
        if (regions.changedInCurrentTransaction(region)) {
            return Optional.empty();
        }
        long version = regions.version(region);
        Entry<V> entry = cache.getIfPresent(key);
        return entry != null && entry.version() == version
                ? Optional.of(entry.value())
                : Optional.empty();
    }

    /**
     * Stores a value loaded by the caller. Pass the version read with {@link #version()} BEFORE the
     * caller loaded it.
     */
    public void put(K key, long version, V value) {
        if (regions.changedInCurrentTransaction(region)) {
            return;
        }
        cache.put(key, new Entry<>(version, value));
    }

    /** The region's current version (read it before loading a value for {@link #put}). */
    public long version() {
        return regions.version(region);
    }

    /** Drops one entry on this instance only (e.g. a signed-out session). */
    public void invalidateLocally(K key) {
        cache.invalidate(key);
    }

    /** Marks the region changed on every instance (in the caller's transaction). */
    public void changed(String changedBy) {
        regions.changed(region, changedBy);
    }
}
