package in.gov.fci.payroll.platform.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** HLD §3.12: the maximum age of an entry is the shorter of the asked and the configured. */
class VersionedCacheMutationGapsTest {

    private static CacheRegions regions(Duration configuredMaxAge) {
        CacheRegions regions = mock(CacheRegions.class);
        when(regions.properties())
                .thenReturn(new CacheProperties(Duration.ofSeconds(10), configuredMaxAge));
        when(regions.version("R")).thenReturn(1L);
        return regions;
    }

    @Test
    void aShorterAskedAgeExpiresTheEntryBeforeTheConfiguredAge() throws Exception {
        VersionedCache<String, String> cache =
                VersionedCache.of("R", regions(Duration.ofHours(1)), 10, Duration.ofMillis(100));
        cache.put("k", 1L, "v");
        Thread.sleep(500);
        assertThat(cache.getIfCurrent("k")).isEmpty();
    }

    @Test
    void aLongerAskedAgeIsCappedAtTheConfiguredAge() throws Exception {
        VersionedCache<String, String> cache =
                VersionedCache.of("R", regions(Duration.ofMillis(100)), 10, Duration.ofHours(1));
        cache.put("k", 1L, "v");
        Thread.sleep(500);
        assertThat(cache.getIfCurrent("k")).isEmpty();
    }
}
