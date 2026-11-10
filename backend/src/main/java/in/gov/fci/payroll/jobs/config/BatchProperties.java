package in.gov.fci.payroll.jobs.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Every tunable of the batch module ({@code fci.batch.*}; values for the batch instance in
 * application-batch.yml). The online instance reads only the parts it validates against (restart
 * window, default priority). Reference: docs/BATCH_DESIGN.md, "Configuration".
 */
@Validated
@ConfigurationProperties(prefix = "fci.batch")
public record BatchProperties(
        @Valid @NotNull @DefaultValue Poller poller,
        @Valid @NotNull @DefaultValue Chunk chunk,
        @Valid @NotNull @DefaultValue Partition partition,
        @Valid @NotNull @DefaultValue Retry retry,
        @Valid @NotNull @DefaultValue Skip skip,
        @Valid @NotNull @DefaultValue Lock lock,
        @Valid @NotNull @DefaultValue Recovery recovery,
        @Valid @NotNull @DefaultValue Schedules schedules,
        @Valid @NotNull @DefaultValue Interfaces interfaces,
        @DefaultValue("72h") @NotNull Duration restartWindow,
        @DefaultValue("60s") @NotNull Duration shutdownTimeout) {

    /** The job-queue poller and the job executor. */
    public record Poller(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5s") @NotNull Duration interval,
            @DefaultValue("2") @Min(1) @Max(16) int maxConcurrentJobs,
            @DefaultValue("10") @Min(1) @Max(100) int claimBatchSize) {}

    /** Commit interval and reader page size of the chunk steps. */
    public record Chunk(
            @DefaultValue("100") @Min(1) @Max(10000) int size,
            @DefaultValue("100") @Min(1) @Max(10000) int pageSize) {}

    /** Partitioned steps: partitions per step and worker threads (shared by all running jobs). */
    public record Partition(
            @DefaultValue("4") @Min(1) @Max(64) int gridSize,
            @DefaultValue("4") @Min(1) @Max(64) int poolSize) {}

    /**
     * Retry with exponential backoff for transient database errors (deadlock, lock timeout,
     * connection).
     */
    public record Retry(
            @DefaultValue("3") @Min(1) @Max(10) int limit,
            @DefaultValue("500ms") @NotNull Duration initialInterval,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("10s") @NotNull Duration maxInterval) {}

    /** Bad records are skipped up to this limit per step and written to batch_skipped_item. */
    public record Skip(@DefaultValue("100") @Min(0) @Max(100000) int limit) {}

    /**
     * The job lock: heartbeat while running; a lock older than staleAfter belongs to a dead node.
     */
    public record Lock(
            @DefaultValue("30s") @NotNull Duration heartbeatInterval,
            @DefaultValue("3m") @NotNull Duration staleAfter) {}

    /** Crash recovery at start-up and on every poll. */
    public record Recovery(@DefaultValue("false") boolean autoRestart) {}

    /** Cron expressions (business zone) of the time-triggered jobs. */
    public record Schedules(
            @DefaultValue("0 30 1 * * *") @NotBlank String interfacePull,
            @DefaultValue("0 0 20 L * *") @NotBlank String monthEnd) {}

    /** The HRMS pull source (OQ-8); its resilience values are its interface_config row (X-03). */
    public record Interfaces(@DefaultValue("") String hrmsSource) {}
}
