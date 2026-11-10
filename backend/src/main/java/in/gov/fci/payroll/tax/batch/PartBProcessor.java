package in.gov.fci.payroll.tax.batch;

import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.tax.business.model.Form16Certificate;
import in.gov.fci.payroll.tax.orchestration.Form16Service;
import in.gov.fci.payroll.tax.orchestration.TaxComputationService;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * Part B of one member in the TAX_FORM16_PART_B job (C-10; SRS-7-5, SRS-7-19), computed by {@link
 * Form16Service#partBOf} exactly as the screen does, on one snapshot of the year-end rules per
 * partition. A member who gets no certificate is filtered (counted, not an error) and its reason
 * tallied; the tally is logged once per partition, without amounts or member identities.
 */
public class PartBProcessor
        implements ItemProcessor<TaxMemberReader.Row, Form16Certificate>, StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(PartBProcessor.class);

    /** Step execution-context key: the reasons members of the partition were not certified. */
    static final String NOT_CERTIFIED = "fci.tax.notCertified";

    private final EmployeeDirectory employees;
    private final Form16Service form16;
    private final TaxRepositories.Form16s certificates;
    private final TaxComputationService.Run rates;
    private final Clock clock;
    private final String actor;
    private final String finYear;
    private final LocalDate yearEnd;
    private final Long versionId;
    private final Map<String, Integer> notCertified = new TreeMap<>();

    PartBProcessor(
            EmployeeDirectory employees,
            Form16Service form16,
            TaxRepositories.Form16s certificates,
            TaxComputationService.Run rates,
            Clock clock,
            String actor,
            String finYear,
            LocalDate yearEnd,
            Long versionId) {
        this.employees = employees;
        this.form16 = form16;
        this.certificates = certificates;
        this.rates = rates;
        this.clock = clock;
        this.actor = actor;
        this.finYear = finYear;
        this.yearEnd = yearEnd;
        this.versionId = versionId;
    }

    @Override
    public Form16Certificate process(TaxMemberReader.Row row) {
        Optional<EmployeeDirectory.Member> m = employees.member(row.employeeId());
        if (m.isEmpty()) {
            return tally("no member record");
        }
        Form16Service.PartB p =
                form16.partBOf(
                        actor,
                        m.get(),
                        finYear,
                        yearEnd,
                        versionId,
                        rates,
                        certificates
                                .findByEmployeeIdAndFinYear(row.employeeId(), finYear)
                                .orElse(null),
                        clock.instant());
        return p.certificate() == null ? tally(p.skipped().reason()) : p.certificate();
    }

    private Form16Certificate tally(String reason) {
        notCertified.merge(category(reason), 1, Integer::sum);
        return null;
    }

    /** The reason without the member's amounts: a year-end difference names its amount. */
    static String category(String reason) {
        return reason.startsWith("actual tax differs") ? "year-end mismatch (X-05)" : reason;
    }

    @Override
    public ExitStatus afterStep(StepExecution step) {
        if (!notCertified.isEmpty()) {
            // The partition's tally, beside its counts (C-13 job detail), without amounts.
            step.getExecutionContext().putString(NOT_CERTIFIED, notCertified.toString());
            log.info(
                    "event=TAX_PART_B_NOT_CERTIFIED step={} reasons={}",
                    step.getStepName(),
                    notCertified);
        }
        return step.getExitStatus();
    }
}
