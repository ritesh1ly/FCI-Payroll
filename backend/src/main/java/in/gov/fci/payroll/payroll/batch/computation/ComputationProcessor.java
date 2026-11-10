package in.gov.fci.payroll.payroll.batch.computation;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.jobs.batch.support.InvalidRecordException;
import in.gov.fci.payroll.payroll.api.StaffDeductionRunner;
import in.gov.fci.payroll.payroll.business.PayDataException;
import in.gov.fci.payroll.payroll.business.PayRules;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * Applies {@link PayRules} to one member with the pay configuration in force for the period (one
 * snapshot per partition). A configuration or data gap ({@link PayDataException}) becomes an {@link
 * InvalidRecordException}: the member is skipped into the failure log (MEMBER_DATA) and the run
 * goes on. A member whose salary is stopped is filtered (no result row, counted as a filter).
 */
public class ComputationProcessor
        implements ItemProcessor<MemberReader.MemberRow, ComputationProcessor.Computed> {

    /** A computed member with its lines. */
    public record Computed(MemberReader.MemberRow member, PayRules.Outcome outcome) {}

    private final PayConfiguration.Snapshot configuration;
    private final StaffDeductionRunner.Session deductions;

    public ComputationProcessor(PayConfiguration.Snapshot configuration) {
        this(configuration, null);
    }

    /** With the month's deductions, pinned for the partition (plan 16 phase 16e-2; SRS-1-6). */
    public ComputationProcessor(
            PayConfiguration.Snapshot configuration, StaffDeductionRunner.Session deductions) {
        this.configuration = configuration;
        this.deductions = deductions;
    }

    /** SRS-1-14 (i): the band (pay scale code) is HRMS-owned, shown on M-01. */
    static final PayDataException.Where NO_SCALE =
            new PayDataException.Where("employee", "band_id", "HRMS", "M-01");

    @Override
    public Computed process(MemberReader.MemberRow row) {
        PayRules.Outcome outcome;
        try {
            outcome = PayRules.compute(row.member(), configuration);
            if (PayRules.COMPUTED.equals(outcome.status()) && !row.scaleInForce()) {
                // SRS-1-14 (i) (US-GAP-44): a valid pay scale is mandatory before computation; the
                // band comes from HRMS, so the member is flagged for correction there.
                throw new PayDataException(
                        "No pay scale in force for the member's band (employee "
                                + row.employeeId()
                                + ", SRS-1-14)",
                        NO_SCALE);
            }
            if (deductions != null) {
                outcome =
                        deductions.apply(
                                outcome,
                                row.employeeId(),
                                row.locationId(),
                                row.payPattern(),
                                configuration);
            }
        } catch (PayDataException ex) {
            // Skipped and logged as an invalid record; also flagged on X-05
            // (BlockedMemberListener).
            throw new MemberBlockedException(ex);
        }
        return PayRules.COMPUTED.equals(outcome.status()) ? new Computed(row, outcome) : null;
    }
}
