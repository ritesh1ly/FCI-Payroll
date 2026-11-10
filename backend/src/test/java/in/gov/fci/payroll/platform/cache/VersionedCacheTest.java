package in.gov.fci.payroll.platform.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The master-data cache (Caffeine): served from memory while the region's version is unchanged,
 * reloaded once the version moves (an approved change on any instance), the maximum age never above
 * the configured periodic reload, a change marked through the regions.
 */
class VersionedCacheTest {

    private final AtomicLong version = new AtomicLong(1);
    private final CacheRegions regions = mock(CacheRegions.class);

    VersionedCacheTest() {
        when(regions.properties())
                .thenReturn(new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)));
        when(regions.version("R")).thenAnswer(call -> version.get());
    }

    @Test
    void aValueIsLoadedOncePerVersionAndAgainAfterAChange() {
        VersionedCache<String, String> cache = VersionedCache.of("R", regions, 10);
        AtomicInteger loads = new AtomicInteger();
        assertThat(cache.get("k", k -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        assertThat(cache.get("k", k -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        assertThat(cache.getIfCurrent("k")).contains("v1");
        version.incrementAndGet();
        assertThat(cache.getIfCurrent("k")).isEmpty();
        assertThat(cache.get("k", k -> "v" + loads.incrementAndGet())).isEqualTo("v2");
        assertThat(loads).hasValue(2);
    }

    @Test
    void aValuePutUnderAnOlderVersionIsNotServedAndALocalDropReloads() {
        VersionedCache<String, String> cache = VersionedCache.of("R", regions, 10);
        long before = cache.version();
        version.incrementAndGet();
        cache.put("k", before, "stale");
        assertThat(cache.getIfCurrent("k")).isEmpty();
        cache.put("k", cache.version(), "fresh");
        assertThat(cache.getIfCurrent("k")).contains("fresh");
        cache.invalidateLocally("k");
        assertThat(cache.getIfCurrent("k")).isEmpty();
    }

    @Test
    void aShorterAgeIsKeptAndALongerOneIsCappedAndAChangeIsMarked() {
        VersionedCache<String, String> shorter =
                VersionedCache.of("R", regions, 10, Duration.ofMinutes(1));
        VersionedCache<String, String> longer =
                VersionedCache.of("R", regions, 10, Duration.ofHours(1));
        assertThat(shorter.get("k", k -> "a")).isEqualTo("a");
        assertThat(longer.get("k", k -> "b")).isEqualTo("b");
        shorter.changed("tester");
        verify(regions).changed("R", "tester");
    }

    @Test
    void aTransactionThatChangedTheRegionReadsAroundTheCacheAndCachesNothing() {
        VersionedCache<String, String> cache = VersionedCache.of("R", regions, 10);
        assertThat(cache.get("k", k -> "committed")).isEqualTo("committed");
        when(regions.changedInCurrentTransaction("R")).thenReturn(true);
        AtomicInteger loads = new AtomicInteger();
        // Its own uncommitted data: loaded every time, never served from or written to the cache.
        assertThat(cache.get("k", k -> "uncommitted" + loads.incrementAndGet()))
                .isEqualTo("uncommitted1");
        assertThat(cache.get("k", k -> "uncommitted" + loads.incrementAndGet()))
                .isEqualTo("uncommitted2");
        assertThat(cache.getIfCurrent("k")).isEmpty();
        cache.put("k", cache.version(), "uncommitted");
        // After a rollback the transaction is gone: the committed entry is still the one served.
        when(regions.changedInCurrentTransaction("R")).thenReturn(false);
        assertThat(cache.getIfCurrent("k")).contains("committed");
    }
}
