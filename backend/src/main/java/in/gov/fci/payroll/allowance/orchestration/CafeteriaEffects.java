package in.gov.fci.payroll.allowance.orchestration;

import in.gov.fci.payroll.allowance.business.AllowanceErrorCode;
import in.gov.fci.payroll.allowance.business.ClaimRules;
import in.gov.fci.payroll.allowance.business.model.CafeteriaElection;
import in.gov.fci.payroll.allowance.business.model.CafeteriaElectionLine;
import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cafeteria allowance selections received from HRMS through I-21 (plan 10 phase 10f, D10-11;
 * SRS-11-16): the value is the HRMS selection (JSON: {@code finYear, effectiveFrom, components:
 * [{payCode, amount}]} with monthly amounts). Never entered in Payroll. On approval the selection
 * becomes a new version, APPLIED from its exact start date; the earlier version of the year is
 * SUPERSEDED from that date, never updated in place. A selection received after the freeze, with a
 * start date in a locked month, raises its arrear ({@link CafeteriaArrears}, SRS-11-16).
 */
@Component
public class CafeteriaEffects implements HrmsTaskEffects {

    static final String FIELD = "CAFETERIA_SELECTION";
    static final String ENTITY = "cafeteria_election";
    private static final Pattern FIN_YEAR = Pattern.compile("^\\d{4}-\\d{2}$");

    /** The states of a version in force, which a later version supersedes. */
    private static final Set<String> IN_FORCE =
            Set.of(
                    CafeteriaElection.Status.APPLIED.name(),
                    CafeteriaElection.Status.ARREAR_RAISED.name());

    private final AllowanceRepositories.Elections elections;
    private final AllowanceRepositories.ElectionLines lines;
    private final ClaimCatalogue catalogue;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CafeteriaArrears arrears;

    public CafeteriaEffects(
            AllowanceRepositories.Elections elections,
            AllowanceRepositories.ElectionLines lines,
            ClaimCatalogue catalogue,
            AuditService audit,
            JdbcTemplate jdbc,
            ObjectMapper json,
            CafeteriaArrears arrears) {
        this.elections = elections;
        this.lines = lines;
        this.catalogue = catalogue;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.arrears = arrears;
    }

    /** The HRMS selection, checked: monthly amounts by pay code. */
    record Selection(
            String finYear, LocalDate effectiveFrom, Map<Integer, BigDecimal> components) {}

    @Override
    public Set<String> fields() {
        return Set.of(FIELD);
    }

    @Override
    public void check(String fieldCode, long employeeId, String newValue) {
        if (catalogue.member(employeeId).labour()) {
            throw invalid("the cafeteria allowance is an employee selection");
        }
        selection(newValue);
    }

    @Override
    public void apply(Task task, String checker, long approvalId, Instant decidedAt) {
        Selection s = selection(task.newValue());
        List<CafeteriaElection> earlier =
                elections.findByEmployeeIdAndFinYearOrderByVersionNoDesc(
                        task.employeeId(), s.finYear());
        int version = earlier.isEmpty() ? 1 : earlier.get(0).getVersionNo() + 1;
        for (CafeteriaElection e : earlier) {
            if (IN_FORCE.contains(e.getStatus())) {
                e.supersededFrom(s.effectiveFrom());
                elections.saveAndFlush(e);
            }
        }
        CafeteriaElection election =
                elections.save(
                        CafeteriaElection.applied(
                                task.employeeId(),
                                s.finYear(),
                                version,
                                s.effectiveFrom(),
                                task.hrmsRequestId(),
                                decidedAt,
                                approvalId));
        for (Map.Entry<Integer, BigDecimal> c : s.components().entrySet()) {
            lines.save(
                    new CafeteriaElectionLine(
                            election.getId(), componentId(c.getKey()), c.getValue()));
        }
        audit.record(
                checker,
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(election.getId()),
                                "election_status",
                                null,
                                election.getStatus(),
                                "Cafeteria selection v"
                                        + version
                                        + " from HRMS approved on I-21 (task "
                                        + task.taskNo()
                                        + ")")
                        .approval(approvalId)
                        .module("I-16"));
        arrears.settle(election, checker, approvalId);
    }

    private long componentId(int payCode) {
        Long id =
                jdbc.queryForObject(
                        "SELECT MIN(component_id) FROM pay_component WHERE legacy_pay_code = ?",
                        Long.class,
                        payCode);
        if (id == null) {
            throw invalid("pay code " + payCode + " is not in the catalogue (M-07)");
        }
        return id;
    }

    private Selection selection(String value) {
        JsonNode v;
        try {
            v = json.readTree(value == null ? "" : value);
        } catch (JacksonException ex) {
            throw invalid("the HRMS selection is not readable");
        }
        if (v == null || !v.isObject()) {
            throw invalid("the HRMS selection is not readable");
        }
        String finYear = v.path("finYear").asString("");
        if (!FIN_YEAR.matcher(finYear).matches()) {
            throw invalid("the financial year is YYYY-YY");
        }
        LocalDate from;
        try {
            from = LocalDate.parse(v.path("effectiveFrom").asString(""));
        } catch (DateTimeParseException ex) {
            throw invalid("the start date is required (YYYY-MM-DD)");
        }
        LocalDate fyStart = LocalDate.of(Integer.parseInt(finYear.substring(0, 4)), 4, 1);
        if (!ClaimRules.financialYearStart(from).equals(fyStart)) {
            throw invalid("the start date is not in the financial year " + finYear);
        }
        JsonNode list = v.path("components");
        if (!list.isArray() || list.isEmpty()) {
            throw invalid("at least one selected component is required");
        }
        Map<Integer, BigDecimal> components = new LinkedHashMap<>();
        Set<Integer> seen = new HashSet<>();
        for (JsonNode c : list) {
            int payCode = c.path("payCode").asInt(-1);
            BigDecimal amount;
            try {
                amount = new BigDecimal(c.path("amount").asString(""));
            } catch (NumberFormatException ex) {
                throw invalid("each component's monthly amount is a number");
            }
            if (!seen.add(payCode)) {
                throw invalid("pay code " + payCode + " is selected twice");
            }
            if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
                throw invalid("each component's monthly amount is positive, at most two decimals");
            }
            if (!catalogue.payCodeActive(payCode, from)) {
                throw invalid("pay code " + payCode + " is not an active component (M-07)");
            }
            components.put(payCode, amount);
        }
        return new Selection(finYear, from, components);
    }

    private static FciException invalid(String why) {
        return new FciException(AllowanceErrorCode.CLAIM, new Object[] {why}, List.of(), null);
    }
}
