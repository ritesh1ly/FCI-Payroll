package in.gov.fci.payroll.increment.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.increment.business.IncrementErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * I-25 Wage Revision steps 5, 6 and 8 for staff (plan 17 phase 17g; SRS-8-25, SRS-13-25; D17-1).
 * Once Headquarters has approved the revision with its OTP, the Rule Administrator at Headquarters
 * fixes pay in bulk: each simulated member whose case fits the rule gets the new Basic from the
 * component's effective date through the one Basic writer (event PAY_REVISION, a {@code
 * pay_fixation_event} linked to the revision), and - the date being before the current month - its
 * single-member arrear is raised through the one retrospective mechanism, computed month by month
 * and placed on I-08 (step 6). Cases outside the rule are listed as exceptions to fix individually
 * on I-05 (step 5, SRS-13-25): a stagnation case, a member no longer in service, a new Basic above
 * the scale maximum. The Revision Status Report (step 8) counts, office by office, the members
 * fixed, pending, in exception and paid. Instalments, TDS with Section 89 relief and corrective
 * revisions (steps 7 and 9) wait (PENDING 13-i25).
 */
@Service
@Loggable
public class WageRevisionFixationService {

    static final String SCREEN = "I-25";

    /** The outcome of the bulk fixation. */
    public record Fixed(int fixed, int exceptions, int arrears) {}

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final BasicPayService basic;
    private final Scales scales;
    private final AuditService audit;
    private final Clock clock;

    public WageRevisionFixationService(
            NamedParameterJdbcTemplate named,
            Authorization policy,
            BasicPayService basic,
            Scales scales,
            AuditService audit,
            Clock clock) {
        this.named = named;
        this.policy = policy;
        this.basic = basic;
        this.scales = scales;
        this.audit = audit;
        this.clock = clock;
    }

    /** SRS-8-25 steps 5 and 6: the bulk fixation of an approved revision. */
    @Transactional
    public Fixed fix(AccessContext context, long revisionId) {
        policy.require(context, SCREEN, "CREATE");
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(CommonErrorCode.ACCESS_DENIED);
        }
        MapSqlParameterSource rev = new MapSqlParameterSource("id", revisionId);
        List<Map<String, Object>> found =
                named.queryForList(
                        "SELECT w.revision_code, w.revision_status, c.effective_from FROM"
                                + " wage_revision w JOIN wage_revision_component c ON"
                                + " c.wage_revision_id = w.wage_revision_id WHERE"
                                + " w.wage_revision_id = :id",
                        rev);
        if (found.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        Map<String, Object> r = found.getFirst();
        if (!"APPROVED".equals(r.get("REVISION_STATUS"))) {
            throw new FciException(
                    IncrementErrorCode.STATE,
                    new Object[] {r.get("REVISION_STATUS"), "APPROVED"},
                    List.of(),
                    null);
        }
        String code = (String) r.get("REVISION_CODE");
        LocalDate effective = Dates.local(r.get("EFFECTIVE_FROM"));
        List<Map<String, Object>> members =
                named.queryForList(
                        "SELECT m.wr_member_id, m.employee_id, m.old_pay, m.new_pay,"
                                + " e.employment_status, e.is_stagnation_case FROM"
                                + " wage_revision_member m JOIN employee e ON e.employee_id ="
                                + " m.employee_id WHERE m.wage_revision_id = :id AND"
                                + " m.fixation_status = 'PENDING' ORDER BY m.wr_member_id",
                        rev);
        int fixed = 0;
        int exceptions = 0;
        int arrears = 0;
        for (Map<String, Object> m : members) {
            long member = ((Number) m.get("WR_MEMBER_ID")).longValue();
            long employee = ((Number) m.get("EMPLOYEE_ID")).longValue();
            BigDecimal newPay = (BigDecimal) m.get("NEW_PAY");
            Optional<String[]> exception = exception(m, employee, newPay, effective);
            if (exception.isPresent()) {
                named.update(
                        "UPDATE wage_revision_member SET fixation_status = 'EXCEPTION',"
                                + " exception_reason = :reason, exception_note = :note, row_version"
                                + " = row_version + 1 WHERE wr_member_id = :id",
                        new MapSqlParameterSource("id", member)
                                .addValue("reason", exception.get()[0])
                                .addValue("note", exception.get()[1]));
                exceptions++;
                continue;
            }
            BasicPayService.Applied applied =
                    basic.apply(
                            new BasicPayService.Change(
                                    employee,
                                    newPay,
                                    effective,
                                    "PAY_REVISION",
                                    "CALC",
                                    "Wage revision " + code,
                                    code,
                                    null,
                                    null,
                                    null,
                                    context.loginId(),
                                    "WAGE_REVISION",
                                    "wage_revision_member",
                                    String.valueOf(member)));
            named.update(
                    "INSERT INTO pay_fixation_event (arrear_event_id, wage_revision_id,"
                        + " employee_id, event_basis, old_basic, revised_basic, fixation_date,"
                        + " order_reference, basic_history_id, is_historical_only) VALUES (:arrear,"
                        + " :revision, :employee, :basis, :old, :new, :date, :order, :history,"
                        + " 'N')",
                    new MapSqlParameterSource()
                            .addValue("arrear", applied.arrearEventId(), Types.NUMERIC)
                            .addValue("revision", revisionId)
                            .addValue("employee", employee)
                            .addValue("basis", code)
                            .addValue(
                                    "old",
                                    applied.oldBasic() == null
                                            ? m.get("OLD_PAY")
                                            : applied.oldBasic())
                            .addValue("new", newPay)
                            .addValue("date", Date.valueOf(effective))
                            .addValue("order", code)
                            .addValue("history", applied.historyId()));
            named.update(
                    "UPDATE wage_revision_member SET fixation_status = 'FIXED', arrear_event_id ="
                            + " :arrear, payment_status = :payment, row_version = row_version + 1"
                            + " WHERE wr_member_id = :id",
                    new MapSqlParameterSource("id", member)
                            .addValue("arrear", applied.arrearEventId(), Types.NUMERIC)
                            .addValue(
                                    "payment",
                                    applied.arrearEventId() == null ? "NOT_DUE" : "PENDING"));
            fixed++;
            if (applied.arrearEventId() != null) {
                arrears++;
            }
        }
        Timestamp now = Timestamp.from(clock.instant());
        MapSqlParameterSource done = rev.addValue("at", now).addValue("by", context.loginId());
        named.update(
                "UPDATE wage_revision SET revision_status = 'PAY_FIXED', current_step = 5,"
                        + " row_version = row_version + 1 WHERE wage_revision_id = :id",
                done);
        named.update(
                "UPDATE wage_revision_step SET step_status = 'DONE', completed_at = :at,"
                        + " completed_by = :by WHERE wage_revision_id = :id AND step_no = 5",
                done);
        // Step 6: done at once when no arrear is due; otherwise in progress while the arrears are
        // computed and submitted on I-08 (chk_wr_step_done: DONE carries its completion).
        named.update(
                arrears > 0
                        ? "UPDATE wage_revision_step SET step_status = 'IN_PROGRESS', started_at ="
                                + " :at WHERE wage_revision_id = :id AND step_no = 6"
                        : "UPDATE wage_revision_step SET step_status = 'DONE', started_at = :at,"
                                + " completed_at = :at, completed_by = :by WHERE wage_revision_id ="
                                + " :id AND step_no = 6",
                done);
        named.update(
                "UPDATE wage_revision_step SET step_status = 'IN_PROGRESS', started_at = :at WHERE"
                        + " wage_revision_id = :id AND step_no = 8",
                done);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "wage_revision",
                                code,
                                "revision_status",
                                "APPROVED",
                                "PAY_FIXED",
                                "Fixed "
                                        + fixed
                                        + ", exceptions "
                                        + exceptions
                                        + ", arrears raised "
                                        + arrears)
                        .module(SCREEN));
        return new Fixed(fixed, exceptions, arrears);
    }

    /** SRS-13-25: the cases outside the rule, with the DDL exception reason and a note. */
    private Optional<String[]> exception(
            Map<String, Object> m, long employee, BigDecimal newPay, LocalDate effective) {
        if ("Y".equals(m.get("IS_STAGNATION_CASE"))) {
            return Optional.of(new String[] {"STAGNATION", "A stagnation case: fix individually"});
        }
        String status = (String) m.get("EMPLOYMENT_STATUS");
        if ("SUSPENDED".equals(status)) {
            return Optional.of(new String[] {"SUSPENSION", "Under suspension on fixation"});
        }
        if (!"IN_SERVICE".equals(status)) {
            return Optional.of(new String[] {"OTHER", "Employment status " + status});
        }
        Optional<Scales.Scale> scale = scales.ofMember(employee, effective);
        if (scale.isPresent() && newPay.compareTo(scale.get().max()) > 0) {
            return Optional.of(
                    new String[] {
                        "MAXIMUM_SCALE",
                        "New Basic "
                                + newPay.toPlainString()
                                + " above the scale maximum "
                                + scale.get().max().toPlainString()
                    });
        }
        return Optional.empty();
    }

    private static final String REPORT =
            " FROM wage_revision_member m JOIN org_location l ON l.location_id = m.location_id"
                    + " WHERE m.wage_revision_id = :id AND (:all = 'Y' OR m.location_id IN"
                    + " (:locations)) GROUP BY l.location_code, l.location_name";

    /** SRS-8-25 step 8: the rows the Revision Status Report will carry. */
    @Transactional(readOnly = true)
    public long statusSize(AccessContext context, long revisionId) {
        policy.require(context, SCREEN, "EXPORT");
        Long n =
                named.queryForObject(
                        "SELECT COUNT(*) FROM (SELECT l.location_code" + REPORT + ") x",
                        scoped(context, revisionId),
                        Long.class);
        return RowCounts.of(n);
    }

    /** SRS-8-25 step 8: office by office, the members fixed, pending, in exception and paid. */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void statusReport(AccessContext context, long revisionId, ExportSink sink)
            throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        MapSqlParameterSource p = scoped(context, revisionId);
        Long expected =
                named.queryForObject(
                        "SELECT COUNT(*) FROM (SELECT l.location_code" + REPORT + ") x",
                        p,
                        Long.class);
        sink.header("office", "members", "fixed", "pending", "exception", "paid");
        named.query(
                "SELECT l.location_code, l.location_name, COUNT(*), SUM(CASE WHEN"
                        + " m.fixation_status IN ('FIXED', 'FIXED_INDIVIDUALLY') THEN 1 ELSE 0"
                        + " END), SUM(CASE WHEN m.fixation_status = 'PENDING' THEN 1 ELSE 0 END),"
                        + " SUM(CASE WHEN m.fixation_status = 'EXCEPTION' THEN 1 ELSE 0 END),"
                        + " SUM(CASE WHEN m.payment_status = 'PAID' THEN 1 ELSE 0 END)"
                        + REPORT
                        + " ORDER BY l.location_code",
                p,
                rs -> {
                    try {
                        sink.row(
                                rs.getString(1) + " - " + rs.getString(2),
                                rs.getLong(3),
                                rs.getLong(4),
                                rs.getLong(5),
                                rs.getLong(6),
                                rs.getLong(7));
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                });
        ExportSink.requireComplete(RowCounts.of(expected), sink);
    }

    private MapSqlParameterSource scoped(AccessContext context, long revisionId) {
        return OfficeScope.of(context, policy.isAtHeadquarters(context)).addValue("id", revisionId);
    }
}
