package in.gov.fci.payroll.platform.cache;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Master-data caches ({@code fci.cache.*}), the same on every online and batch instance.
 *
 * @param checkInterval how often an instance asks the database whether a cache region changed (one
 *     small query for all regions, only while the caches are being read): the longest an approved
 *     change takes to reach every other instance
 * @param maxAge the periodic full reload: no entry is served older than this, whatever the versions
 */
@Validated
@ConfigurationProperties(prefix = "fci.cache")
public record CacheProperties(
        @DefaultValue("10s") @NotNull Duration checkInterval,
        @DefaultValue("15m") @NotNull Duration maxAge) {}
