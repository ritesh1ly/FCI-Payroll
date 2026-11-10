package in.gov.fci.payroll.jobs.batch.support;

/** Names shared by the batch jobs, listeners and the request service. */
public final class BatchKeys {

    /**
     * Identifying job parameter: the request. Same request = same JobInstance, so restart works.
     */
    public static final String REQUEST_ID = "jobRequestId";

    /**
     * Non-identifying job parameter: the request's parameter hash (resume rule, duplicate check).
     */
    public static final String PARAM_HASH = "fci.paramHash";

    /** Step execution-context key a job sets to describe its result in one line. */
    public static final String SUMMARY = "fci.summary";

    /** Partition execution-context key: the items a partition is expected to read (progress). */
    public static final String PLANNED = "fci.planned";

    /**
     * Step execution-context key that marks a partitioned step's manager ({@link
     * PartitionedSteps}): its counts are its workers', added in only when the step ends.
     */
    public static final String PARTITION_MANAGER = "fci.partitionManager";

    /** Partition execution-context key: the comma-separated location ids of a partition. */
    public static final String LOCATIONS = "fci.locationIds";

    /** MDC keys of every log line written while a job runs. */
    public static final String MDC_REQUEST = "jobRequestId";

    public static final String MDC_EXECUTION = "jobExecutionId";

    public static final String MDC_JOB = "jobName";

    private BatchKeys() {}
}
