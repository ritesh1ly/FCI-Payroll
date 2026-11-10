package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import in.gov.fci.payroll.employee.business.HrmsChangeRules;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Applies an HRMS value to the employee record (plan 16 D16-4; SRS-1-28 "apply the value from its
 * effective date"; SRS-1-19 for the fields without pay effect). The column is the one the
 * field-source registry names for the field ({@code hrms_pay_effect_field.field_source_id} ->
 * {@code field_source_registry}); each supported column has its own statement, so no column name is
 * ever built from data. A field with no mapped column, or a column not supported here, is not
 * applicable: its task cannot be submitted until it is configured. Every change is audited with its
 * old and new value (Restricted values masked).
 *
 * <p>SRS-1-22: a new date of birth recalculates the retirement date in the same transaction.
 */
@Component
public class HrmsChangeApplier {

    /** The employee columns an I-21 task may write and the value type of each. */
    private static final Map<String, String> COLUMNS =
            Map.of(
                    "birth_date", "DATE",
                    "pan_number", "TEXT",
                    "uan_number", "TEXT",
                    "epfo_member_id", "TEXT",
                    "eps_number", "TEXT",
                    "eps_join_date", "DATE",
                    "pran_number", "TEXT",
                    "bank_account_number", "TEXT",
                    "current_basic", "AMOUNT",
                    "gender", "TEXT");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final SystemParameters parameters;

    /** Fields another module applies (plan 09 phase 09c: loans), by field code. */
    private Map<String, HrmsTaskEffects> effects = Map.of();

    public HrmsChangeApplier(JdbcTemplate jdbc, AuditService audit, SystemParameters parameters) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.parameters = parameters;
    }

    /** Where SRS-1-24 alerts go (none in a slice test without the event bus). */
    private org.springframework.context.ApplicationEventPublisher events = event -> {};

    @Autowired(required = false)
    void setEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    /** The modules that apply their own I-21 fields (none registered: none). */
    @Autowired(required = false)
    void setEffects(List<HrmsTaskEffects> owners) {
        Map<String, HrmsTaskEffects> byField = new java.util.HashMap<>();
        for (HrmsTaskEffects owner : owners) {
            for (String code : owner.fields()) {
                byField.put(code, owner);
            }
        }
        this.effects = Map.copyOf(byField);
    }

    /** The module that applies this field, if another one does. */
    Optional<HrmsTaskEffects> effectFor(String fieldCode) {
        return Optional.ofNullable(fieldCode == null ? null : effects.get(fieldCode));
    }

    /** The column the field writes, or null when it has none supported. */
    String column(Map<String, Object> field) {
        Object sourceId = field.get("FIELD_SOURCE_ID");
        if (sourceId == null) {
            return null;
        }
        List<String> names =
                jdbc.queryForList(
                        "SELECT field_name FROM field_source_registry WHERE field_source_id = ? AND"
                                + " entity_name = 'employee'",
                        String.class,
                        ((Number) sourceId).longValue());
        return names.isEmpty() || !COLUMNS.containsKey(names.get(0)) ? null : names.get(0);
    }

    boolean applicable(Map<String, Object> field) {
        // Plan 16 phase 16f-1: a life-cycle event field is applied as an event, not a column.
        String code = (String) field.get("FIELD_CODE");
        return HrmsEventApplier.handles(code)
                || effectFor(code).isPresent()
                || column(field) != null;
    }

    /** The value in Payroll now (null when the field has no column). */
    String current(Map<String, Object> field, long employeeId) {
        String column = column(field);
        if (column == null) {
            return null;
        }
        Object value = jdbc.queryForMap(read(column), employeeId).values().iterator().next();
        return text(value);
    }

    /**
     * SRS-1-9, BR-EMPM-003: a PAN is unique across the employee population, so a PAN another
     * employee already holds is refused with its reason (HRC-422-PAN-HELD), at submission and again
     * at approval, never left to the database's unique index. Other fields have no such check.
     */
    void requireUnique(Map<String, Object> field, long employeeId, String value) {
        if (value == null || !"pan_number".equals(column(field))) {
            return;
        }
        Integer holders =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM employee WHERE pan_number = ? AND employee_id <> ?",
                        Integer.class,
                        value.strip(),
                        employeeId);
        if (holders != null && holders > 0) {
            throw new FciException(ApiErrorCode.I21_PAN_HELD);
        }
    }

    /** Writes the value; refused when it does not fit the column's type (HRC-422-VALUE). */
    void apply(
            Map<String, Object> field, long employeeId, String value, String by, Long approvalId) {
        String column = column(field);
        if (column == null) {
            throw new FciException(
                    ApiErrorCode.I21_NOT_APPLICABLE,
                    new Object[] {field.get("FIELD_CODE")},
                    List.of(),
                    null);
        }
        String code = (String) field.get("FIELD_CODE");
        String before = current(field, employeeId);
        Object typed = typed(COLUMNS.get(column), value, code);
        requireUnique(field, employeeId, value);
        jdbc.update(write(column), typed, employeeId);
        record(by, employeeId, column, code, before, value, approvalId);
        if ("birth_date".equals(column)) {
            OptionalLong age = parameters.configured(SystemParameters.Code.RETIREMENT_AGE);
            if (age.isPresent()) {
                LocalDate retirement =
                        HrmsChangeRules.retirementDate(
                                ((java.sql.Date) typed).toLocalDate(), (int) age.getAsLong());
                Object old =
                        jdbc.queryForObject(
                                "SELECT retirement_date FROM employee WHERE employee_id = ?",
                                Object.class,
                                employeeId);
                jdbc.update(
                        "UPDATE employee SET retirement_date = ?, row_version = row_version + 1"
                                + " WHERE employee_id = ?",
                        java.sql.Date.valueOf(retirement),
                        employeeId);
                record(
                        by,
                        employeeId,
                        "retirement_date",
                        code,
                        text(old),
                        retirement.toString(),
                        approvalId);
                // SRS-1-24 (US-GAP-45): retiring this month or next: alert before the lock.
                if (HrmsChangeRules.retiresSoon(
                        retirement, java.time.YearMonth.now(java.time.ZoneId.of("Asia/Kolkata")))) {
                    events.publishEvent(
                            new in.gov.fci.payroll.employee.api.EmployeeEvents.RetirementSoon(
                                    employeeId, retirement, approvalId, by));
                }
            }
        }
    }

    private void record(
            String by,
            long employeeId,
            String column,
            String code,
            String before,
            String after,
            Long approvalId) {
        String empNum =
                jdbc.queryForObject(
                        "SELECT emp_num FROM employee WHERE employee_id = ?",
                        String.class,
                        employeeId);
        AuditService.Entry entry =
                AuditService.Entry.change(
                                "employee",
                                empNum,
                                column,
                                HrmsChangeRules.mask(code, before),
                                HrmsChangeRules.mask(code, after),
                                "HRMS change (I-21)")
                        .module(HrmsChangeService.SCREEN);
        audit.record(by, approvalId == null ? entry : entry.approval(approvalId));
    }

    private static Object typed(String type, String value, String code) {
        if (value == null) {
            throw new FciException(ApiErrorCode.I21_VALUE, new Object[] {code}, List.of(), null);
        }
        try {
            return switch (type) {
                case "DATE" -> java.sql.Date.valueOf(LocalDate.parse(value));
                case "AMOUNT" -> {
                    BigDecimal amount = new BigDecimal(value);
                    if (amount.signum() <= 0) {
                        throw new NumberFormatException("not positive");
                    }
                    yield amount;
                }
                default -> {
                    if (value.isBlank() || value.length() > 30) {
                        throw new IllegalArgumentException("blank or too long");
                    }
                    yield value.strip();
                }
            };
        } catch (DateTimeParseException | IllegalArgumentException bad) {
            throw new FciException(ApiErrorCode.I21_VALUE, new Object[] {code}, List.of(), null);
        }
    }

    private static String text(Object value) {
        return switch (value) {
            case null -> null;
            case java.sql.Date d -> d.toLocalDate().toString();
            case java.sql.Timestamp t -> t.toLocalDateTime().toLocalDate().toString();
            case LocalDate d -> d.toString();
            case java.time.LocalDateTime d -> d.toLocalDate().toString();
            case BigDecimal b -> b.toPlainString();
            default -> value.toString();
        };
    }

    /** The constant read of each supported column. */
    private static String read(String column) {
        return switch (column) {
            case "birth_date" -> "SELECT birth_date FROM employee WHERE employee_id = ?";
            case "pan_number" -> "SELECT pan_number FROM employee WHERE employee_id = ?";
            case "uan_number" -> "SELECT uan_number FROM employee WHERE employee_id = ?";
            case "epfo_member_id" -> "SELECT epfo_member_id FROM employee WHERE employee_id = ?";
            case "eps_number" -> "SELECT eps_number FROM employee WHERE employee_id = ?";
            case "eps_join_date" -> "SELECT eps_join_date FROM employee WHERE employee_id = ?";
            case "pran_number" -> "SELECT pran_number FROM employee WHERE employee_id = ?";
            case "bank_account_number" ->
                    "SELECT bank_account_number FROM employee WHERE employee_id = ?";
            case "current_basic" -> "SELECT current_basic FROM employee WHERE employee_id = ?";
            default -> "SELECT gender FROM employee WHERE employee_id = ?";
        };
    }

    /**
     * The constant write of each supported column; each bumps row_version (Oracle review F5), so a
     * JPA save of the member that read the row before fails its version check.
     */
    private static String write(String column) {
        return switch (column) {
            case "birth_date" ->
                    "UPDATE employee SET birth_date = ?, row_version = row_version + 1 WHERE"
                            + " employee_id = ?";
            case "pan_number" ->
                    "UPDATE employee SET pan_number = ?, row_version = row_version + 1 WHERE"
                            + " employee_id = ?";
            case "uan_number" ->
                    "UPDATE employee SET uan_number = ?, row_version = row_version + 1 WHERE"
                            + " employee_id = ?";
            case "epfo_member_id" ->
                    "UPDATE employee SET epfo_member_id = ?, row_version = row_version + 1 WHERE"
                            + " employee_id = ?";
            case "eps_number" ->
                    "UPDATE employee SET eps_number = ?, row_version = row_version + 1 WHERE"
                            + " employee_id = ?";
            case "eps_join_date" ->
                    "UPDATE employee SET eps_join_date = ?, row_version = row_version + 1 WHERE"
                            + " employee_id = ?";
            case "pran_number" ->
                    "UPDATE employee SET pran_number = ?, row_version = row_version + 1 WHERE"
                            + " employee_id = ?";
            case "bank_account_number" ->
                    "UPDATE employee SET bank_account_number = ?, row_version = row_version + 1"
                            + " WHERE employee_id = ?";
            case "current_basic" ->
                    "UPDATE employee SET current_basic = ?, row_version = row_version + 1 WHERE"
                            + " employee_id = ?";
            default ->
                    "UPDATE employee SET gender = ?, row_version = row_version + 1 WHERE"
                            + " employee_id = ?";
        };
    }
}
