package in.gov.fci.payroll.payroll.batch.arrear;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.batch.support.InvalidRecordException;
import in.gov.fci.payroll.payroll.batch.reader.EmployeeRow;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.payroll.business.DaArrearRules;
import in.gov.fci.payroll.payroll.business.PayDataException;
import in.gov.fci.payroll.payroll.business.PayRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One member's rate differentials over the range, from the revised component's lines of the
 * member's ACTUAL results of record ({@code result_status} CURRENT) and the % in force for each
 * month now: DA (DA_ARREAR) or, plan 13 phase 13c, HRA on the month's city class (HRA_ARREAR,
 * SRS-8-10). Members without a differential are filtered; months without an ACTUAL result are
 * counted, never invented. Also the step's summary line (it holds the counts).
 */
public class DaArrearProcessor
        implements ItemProcessor<EmployeeRow, DaArrearProcessor.MemberArrear>,
                StepExecutionListener {

    public static final String MONTHS_WITHOUT_RESULT = "fci.monthsWithoutResult";

    /** One differential row for arrear_detail. */
    public record Detail(
            long componentId,
            String yymm,
            LocalDate periodDate,
            BigDecimal original,
            BigDecimal recomputed,
            BigDecimal arrear) {}

    public record MemberArrear(long eventId, long employeeId, List<Detail> details) {}

    /**
     * The revised rate: its component and the % in force for a member's month (as approved now).
     */
    public interface Rate {
        int payCode();

        String label();

        Optional<BigDecimal> percent(
                PayConfiguration.Snapshot snapshot, EmployeeRow member, YearMonth month);
    }

    /** DA_ARREAR: the DA % of the member's pay pattern. */
    public static final Rate DA =
            new Rate() {
                @Override
                public int payCode() {
                    return PayRules.DA;
                }

                @Override
                public String label() {
                    return "DA";
                }

                @Override
                public Optional<BigDecimal> percent(
                        PayConfiguration.Snapshot snapshot, EmployeeRow member, YearMonth month) {
                    return snapshot.da(member.payPattern());
                }
            };

    private static final String PAID =
            "SELECT r.payroll_yymm, c.component_id, c.amount, c.basis_amount FROM payroll_result r"
                + " JOIN payroll_result_component c ON c.payroll_result_id = r.payroll_result_id"
                + " WHERE r.employee_id = ? AND r.run_type = 'ACTUAL' AND r.result_status ="
                + " 'CURRENT' AND r.payroll_yymm BETWEEN ? AND ? AND c.legacy_pay_code = ?";

    private final JdbcTemplate jdbc;
    private final PayConfiguration configuration;
    private final Rate rate;
    private final long eventId;
    private final YearMonth from;
    private final YearMonth to;
    private final Map<YearMonth, PayConfiguration.Snapshot> snapshots = new HashMap<>();
    private final AtomicLong monthsWithoutResult = new AtomicLong();

    public DaArrearProcessor(
            JdbcTemplate jdbc,
            PayConfiguration configuration,
            Long eventId,
            YearMonth from,
            YearMonth to) {
        this(jdbc, configuration, DA, eventId, from, to);
    }

    public DaArrearProcessor(
            JdbcTemplate jdbc,
            PayConfiguration configuration,
            Rate rate,
            Long eventId,
            YearMonth from,
            YearMonth to) {
        this.jdbc = jdbc;
        this.configuration = configuration;
        this.rate = rate;
        this.eventId = eventId == null ? -1 : eventId;
        this.from = from;
        this.to = to;
    }

    @Override
    public MemberArrear process(EmployeeRow row) {
        List<Map<String, Object>> paid =
                jdbc.queryForList(
                        PAID,
                        row.employeeId(),
                        CycleKeys.yymm(from),
                        CycleKeys.yymm(to),
                        rate.payCode());
        Set<String> monthsPaid = new HashSet<>();
        List<Detail> details = new ArrayList<>();
        try {
            for (Map<String, Object> line : paid) {
                String yymm = String.valueOf(line.get("PAYROLL_YYMM"));
                monthsPaid.add(yymm);
                YearMonth month =
                        YearMonth.of(
                                Integer.parseInt(yymm.substring(0, 4)),
                                Integer.parseInt(yymm.substring(4)));
                BigDecimal percent =
                        rate.percent(snapshot(month), row, month)
                                .orElseThrow(
                                        () ->
                                                new PayDataException(
                                                        rate.label()
                                                                + " rate not seeded for "
                                                                + row.payPattern()
                                                                + " in "
                                                                + month));
                DaArrearRules.differential(
                                (BigDecimal) line.get("AMOUNT"),
                                (BigDecimal) line.get("BASIS_AMOUNT"),
                                percent)
                        .ifPresent(
                                d ->
                                        details.add(
                                                new Detail(
                                                        ((Number) line.get("COMPONENT_ID"))
                                                                .longValue(),
                                                        yymm,
                                                        month.atDay(1),
                                                        d.original(),
                                                        d.recomputed(),
                                                        d.arrear())));
            }
        } catch (PayDataException ex) {
            throw new InvalidRecordException(ex.getMessage());
        }
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            if (!monthsPaid.contains(CycleKeys.yymm(m))) {
                monthsWithoutResult.incrementAndGet();
            }
        }
        return details.isEmpty()
                ? null
                : new MemberArrear(eventId, row.employeeId(), List.copyOf(details));
    }

    private synchronized PayConfiguration.Snapshot snapshot(YearMonth month) {
        return snapshots.computeIfAbsent(month, m -> configuration.staffConfiguration(m.atDay(1)));
    }

    @Override
    public ExitStatus afterStep(StepExecution step) {
        step.getExecutionContext().putLong(MONTHS_WITHOUT_RESULT, monthsWithoutResult.get());
        step.getExecutionContext()
                .putString(
                        BatchKeys.SUMMARY,
                        "Arrear event "
                                + eventId
                                + " ("
                                + rate.label()
                                + " revision "
                                + from
                                + " to "
                                + to
                                + "): members read "
                                + step.getReadCount()
                                + ", with a differential "
                                + step.getWriteCount()
                                + ", without one "
                                + step.getFilterCount()
                                + ", member-months without an ACTUAL result "
                                + monthsWithoutResult.get());
        return step.getExitStatus();
    }
}
