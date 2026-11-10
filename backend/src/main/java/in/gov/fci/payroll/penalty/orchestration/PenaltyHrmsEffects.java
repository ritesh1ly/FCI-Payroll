package in.gov.fci.payroll.penalty.orchestration;

import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.penalty.api.PenaltyPayEffects;
import in.gov.fci.payroll.penalty.business.PenaltyErrorCode;
import in.gov.fci.payroll.penalty.business.PenaltyRules;
import in.gov.fci.payroll.penalty.business.model.PenaltyOrder;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Employee penalties received from HRMS (plan 12 phase 12c; SRS-14-1, SRS-14-13, CF-17, SRS-G-45;
 * D12-2, D12-14): a penalty order arrives as an I-21 task (field PENALTY_ORDER) whose value is the
 * HRMS penalty record, and is recorded on I-06 with its Penalty ID only when the task is approved.
 * Checked at intake so a task never opens on a record that could not be applied.
 *
 * <p>The record {@code {nature, orderReference, effectiveDate, amount?, instalments?, startYymm?,
 * periodTo?}}: a RECOVERY carries its amount, instalments and first month (moved to the next open
 * month when that month is already locked, as the loans do: D09-13); an INCREMENT_STOP is the
 * effective-dated flag the I-05 increment run reads ({@code penalty.api.IncrementStops},
 * SRS-14-13), to {@code periodTo} or open; a BASIC_REDUCTION is recorded with its date and, when
 * the record gives {@code reducedBasic}, writes that Basic through the one writer of Basic Pay
 * ({@link PenaltyPayEffects}, SRS-14-8, SRS-13-26). A record without the figure is recorded and
 * listed on X-05: nothing guesses the reduced Basic. OPEN-QUESTION (PENDING API-12-2,
 * I06-BASIC-BASIS): the HRMS penalty record's layout is not specified; this one is designed here.
 */
@Component
public class PenaltyHrmsEffects implements HrmsTaskEffects {

    static final String FIELD = "PENALTY_ORDER";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern PRINTABLE = Pattern.compile("^[\\x20-\\x7E]{1,60}$");
    private static final YearMonth ANY = YearMonth.of(1900, 1);

    private final PenaltyWriter writer;
    private final ReferenceData references;
    private final EmployeeDirectory employees;
    private final OrgDirectory org;
    private final PayrollRecords payroll;
    private final PenaltyPayEffects pay;
    private final ExceptionWorklist worklist;
    private final ObjectMapper json;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public PenaltyHrmsEffects(
            PenaltyWriter writer,
            ReferenceData references,
            EmployeeDirectory employees,
            OrgDirectory org,
            PayrollRecords payroll,
            PenaltyPayEffects pay,
            ExceptionWorklist worklist,
            ObjectMapper json) {
        this.writer = writer;
        this.references = references;
        this.employees = employees;
        this.org = org;
        this.payroll = payroll;
        this.pay = pay;
        this.worklist = worklist;
        this.json = json;
    }

    @Override
    public Set<String> fields() {
        return Set.of(FIELD);
    }

    @Override
    public void check(String fieldCode, long employeeId, String newValue) {
        Record r = record(read(newValue));
        if (r.reducedBasic() != null) {
            String defect = pay.reductionDefect(employeeId, r.effectiveDate(), r.reducedBasic());
            if (defect != null) {
                throw invalid(defect);
            }
        }
    }

    @Override
    public void apply(Task task, String checker, long approvalId, Instant decidedAt) {
        Record r = record(read(task.newValue()));
        YearMonth open = openPeriod(task.employeeId(), YearMonth.from(decidedAt.atZone(IST)));
        String start = r.startYymm();
        if (start != null && PenaltyRules.month(start).orElseThrow().isBefore(open)) {
            start = String.format("%04d%02d", open.getYear(), open.getMonthValue());
        }
        PenaltyOrder order =
                writer.create(
                        new PenaltyWriter.Approved(
                                task.employeeId(),
                                r.nature(),
                                r.orderReference(),
                                r.effectiveDate(),
                                r.amount(),
                                r.instalments(),
                                start,
                                r.periodTo(),
                                r.effectiveDate().isBefore(open.atDay(1)),
                                task.taskId(),
                                task.hrmsRequestId(),
                                null),
                        checker,
                        approvalId,
                        decidedAt);
        if (r.reducedBasic() != null) {
            pay.reduceBasic(
                    new PenaltyPayEffects.Reduction(
                            task.employeeId(),
                            order.getPenaltyRef(),
                            r.orderReference(),
                            r.effectiveDate(),
                            r.reducedBasic(),
                            PenaltyChangeService.HRMS,
                            task.hrmsRequestId(),
                            task.taskId(),
                            approvalId,
                            checker));
        } else if (PenaltyChangeService.BASIC_REDUCTION.equals(r.nature())) {
            worklist.raise(
                    checker,
                    new ExceptionWorklist.NewEntry(
                            null,
                            task.employeeId(),
                            PenaltyOrderService.SCREEN,
                            PenaltyChangeService.INCREMENT_PENDING,
                            "WARNING",
                            null,
                            null,
                            PenaltyWriter.ORDER_ENTITY,
                            order.getPenaltyRef(),
                            "ENT",
                            "I-05",
                            order.getPenaltyRef()
                                    + ": the Basic-reduction order came with no reduced Basic; the"
                                    + " Basic stands until it is fixed on I-05."));
        }
    }

    /** The HRMS penalty record, checked. */
    private record Record(
            String nature,
            String orderReference,
            LocalDate effectiveDate,
            BigDecimal amount,
            Integer instalments,
            String startYymm,
            LocalDate periodTo,
            BigDecimal reducedBasic) {}

    private Record record(JsonNode v) {
        String nature = v.path("nature").asString("");
        if (!references.contains(PenaltyOrderService.NATURES, nature)) {
            throw invalid("the nature is not one of the list");
        }
        String reference = v.path("orderReference").asString("").strip();
        if (!PRINTABLE.matcher(reference).matches()) {
            throw invalid("the order reference is required, printable, at most 60 characters");
        }
        LocalDate effective = date(v, "effectiveDate");
        if (effective == null) {
            throw invalid("the effective date is required");
        }
        LocalDate to = date(v, "periodTo");
        if (to != null && to.isBefore(effective)) {
            throw invalid("the period ends before it starts");
        }
        if (PenaltyOrderService.RECOVERY.equals(nature)) {
            BigDecimal amount =
                    v.path("amount").isNumber() ? v.path("amount").decimalValue() : null;
            int instalments = v.path("instalments").asInt(0);
            String start = v.path("startYymm").asString(null);
            PenaltyRules.defect(amount, instalments, start, ANY)
                    .ifPresent(
                            d -> {
                                throw invalid("the recovery terms are not valid (" + d + ")");
                            });
            return new Record(nature, reference, effective, amount, instalments, start, null, null);
        }
        if (!v.path("amount").isMissingNode() && !v.path("amount").isNull()) {
            throw invalid("an amount is given for a nature that recovers nothing");
        }
        JsonNode reduced = v.path("reducedBasic");
        if (!reduced.isMissingNode() && !reduced.isNull() && !reduced.isNumber()) {
            throw invalid("the reduced Basic is not a number");
        }
        if (reduced.isNumber() && !PenaltyChangeService.BASIC_REDUCTION.equals(nature)) {
            throw invalid("a reduced Basic is given for a nature that does not reduce it");
        }
        return new Record(
                nature,
                reference,
                effective,
                null,
                null,
                null,
                to,
                reduced.isNumber() ? reduced.decimalValue() : null);
    }

    private static LocalDate date(JsonNode v, String field) {
        String s = v.path(field).asString(null);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException notADate) {
            throw invalid(field + " is not a date");
        }
    }

    private JsonNode read(String value) {
        try {
            JsonNode v = json.readTree(value == null ? "" : value);
            if (v == null || !v.isObject()) {
                throw invalid("the HRMS penalty record is not an object");
            }
            return v;
        } catch (JacksonException unreadable) {
            throw invalid("the HRMS penalty record is unreadable");
        }
    }

    /** The month recovery may start for the member: this month, or the next once locked. */
    private YearMonth openPeriod(long employeeId, YearMonth current) {
        Long unit =
                employees
                        .member(employeeId)
                        .flatMap(m -> org.cycleUnitOf(m.locationId()))
                        .map(OrgLocation::getId)
                        .orElse(null);
        String yymm = String.format("%04d%02d", current.getYear(), current.getMonthValue());
        return unit != null
                        && payroll.cycle(unit, yymm)
                                .filter(PayrollRecords.Cycle::isLocked)
                                .isPresent()
                ? current.plusMonths(1)
                : current;
    }

    private static FciException invalid(String why) {
        return new FciException(PenaltyErrorCode.ORDER, new Object[] {why}, List.of(), null);
    }
}
