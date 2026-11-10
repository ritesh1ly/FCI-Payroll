package in.gov.fci.payroll.platform.export;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * List exports (SRS-G-13), {@code fci.export.*}. Technical limits, not business rules: how many
 * downloads stream at once, how long one may take, and the largest list one download may carry
 * (above it the export is refused with "narrow the filter", never cut short).
 *
 * @param threads downloads streaming at the same time (the export pool's size)
 * @param queueCapacity downloads waiting for a free thread; beyond it a download is refused (503)
 * @param timeout the longest one download may stream
 * @param maxRows the most rows one download carries (Excel's own limit is 1,048,575)
 * @param batchSize rows read per keyset batch inside the export's snapshot
 */
@Validated
@ConfigurationProperties("fci.export")
public record ExportProperties(
        @DefaultValue("4") @Min(1) @Max(32) int threads,
        @DefaultValue("20") @Min(0) @Max(500) int queueCapacity,
        @DefaultValue("10m") @NotNull Duration timeout,
        @DefaultValue("200000") @Min(1) @Max(1_048_575) int maxRows,
        @DefaultValue("500") @Min(10) @Max(10_000) int batchSize) {}
