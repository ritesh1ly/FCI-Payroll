package in.gov.fci.payroll.jobs.business.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * The jobs of the batch module, each a Spring Batch job bean. Only the submittable ones may be
 * queued from the UI (C-13); the others are queued by the batch instance's schedules.
 */
public enum JobType {
    /** Staff payroll dry run (C-02): a v4.0 SIMULATION computation run; changes no cycle state. */
    PAYROLL_DRY_RUN("payrollDryRunJob", true, 2),
    /**
     * Staff payroll computation (C-02): the ACTUAL computation run of a FROZEN cycle, which becomes
     * COMPUTED.
     */
    PAYROLL_COMPUTATION("payrollComputationJob", true, 9),
    /**
     * DA arrear (MST-DA-001): an arrear_event with the per-month DA differentials of the ACTUAL
     * results.
     */
    DA_ARREAR("daArrearJob", true, 5),
    /**
     * HRA arrear (plan 13 phase 13c; SRS-8-10): an arrear_event with the per-month HRA
     * differentials of the ACTUAL results, on the month's city class.
     */
    HRA_ARREAR("hraArrearJob", true, 5),
    /**
     * Form 16 Part B of a paying unit's locked year (C-10; SRS-7-5, SRS-NF-7: statutory outputs as
     * background jobs after lock): partitioned by the unit's locations, committed chunk by chunk.
     */
    TAX_FORM16_PART_B("taxForm16PartBJob", true, 3),
    /**
     * The Form 24Q of a paying unit's locked quarter (C-11; SRS-7-7, SRS-NF-7): the return
     * generated, or regenerated in place, with its deductees; partitioned by the unit's locations.
     */
    TAX_24Q_RETURN("tax24QReturnJob", true, 3),
    /** Nightly HRMS interface pull (X-03); schedule only. */
    INTERFACE_PULL("interfacePullJob", false, 7),
    /** Month-end readiness check; schedule only. */
    MONTH_END_CLOSE("monthEndCloseJob", false, 9),
    /**
     * C-09 General (Simulation) Run above the interactive limit (SRS-15-7, plan 05 D-21): queued by
     * C-09 itself, without an approval; never offered on the C-13 submit form.
     */
    SIMULATION_RUN("simulationRunJob", false, 1),
    /**
     * O-01 Bulk PDF (OD-144, OD-149; SRS-NF-2: the bulk payslip download as a background job): one
     * ZIP of a cycle unit's payslip PDFs of a locked month, rendered from the locked results at run
     * time; queued by O-01 itself, never on the C-13 submit form.
     */
    PAYSLIP_BULK("payslipBulkJob", false, 2),
    /**
     * C-10 Bulk PDF (OD-144, OD-149; SRS-NF-2, SRS-NF-7): one ZIP of a paying unit's Form 16
     * certificates of a year, rendered at run time; queued by C-10 itself.
     */
    FORM16_BULK("form16BulkJob", false, 2);

    /**
     * SRS-NF-11 / SRS-NF-14 (deep review DR-17): jobs of this priority or lower (bulk statutory
     * outputs, simulations and dry runs) never take the last free job slot of a node, which stays
     * for the Actual Run, the month-end close and the other priority work.
     */
    public static final int LOW_PRIORITY = 3;

    private final String jobBean;
    private final boolean submittable;
    private final int priority;

    JobType(String jobBean, boolean submittable, int priority) {
        this.jobBean = jobBean;
        this.submittable = submittable;
        this.priority = priority;
    }

    /**
     * SRS-NF-11: the queue priority of the type (9 highest): the Actual Run and the month-end close
     * first, the interface pull and the arrear jobs next, the bulk statutory outputs, then dry runs
     * and simulations. A requester may lower it for a job, never raise it.
     */
    public int priority() {
        return priority;
    }

    /** Bulk work that leaves a node's last free slot to priority work (SRS-NF-11). */
    public boolean isLowPriority() {
        return priority <= LOW_PRIORITY;
    }

    public String jobBean() {
        return jobBean;
    }

    public boolean isSubmittable() {
        return submittable;
    }

    public static Optional<JobType> of(String name) {
        return Arrays.stream(values()).filter(t -> t.name().equals(name)).findFirst();
    }
}
