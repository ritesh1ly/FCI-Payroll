package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.UndoableTransaction;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Controlled undo of a payroll transaction before EBS posting, and the Gate Override & Undo /
 * Reversal Register (plan 05 phase 05e).
 *
 * <ul>
 *   <li>SRS-15-3 / SRS-15-10, BR-CYC-006: the Payroll Maker requests the undo of a transaction of
 *       the unit's cycle with a reason; route {@code C02_UNDO} (plan 05 D-11) has the Payroll
 *       Checker verify it and the Administrator authorise it with the OTP {@code UNDO_TRANSACTION}
 *       (SRS-G-21); {@link CycleUndoEffect} applies it. Never once the cycle is posted to EBS.
 *       Which transactions are undoable is not named by the SRS (D-10): a module registers its own
 *       ({@link UndoableTransaction}); any other kind is refused.
 *   <li>SRS-15-17, SRS-15-10: the register lists every accepted gate override and every controlled
 *       undo of the unit's cycles, newest first, paged, with an export.
 * </ul>
 */
@Service
@Loggable
public class CycleUndo {

    /** Approval entity of an undo request. */
    public static final String UNDO_ENTITY = "undo_reversal_register";

    /** One row of the register (DDL {@code undo_reversal_register}). */
    public record RegisterEntry(
            long id,
            String type,
            String period,
            String entity,
            String key,
            String stage,
            String reason,
            String requestedBy,
            String checkedBy,
            String authorisedBy,
            Long approvalId,
            Instant executedAt) {}

    private static final String REGISTER =
            "SELECT u.undo_id, u.register_type, u.payroll_yymm, u.transaction_entity,"
                    + " u.transaction_key, u.stage_at_undo, u.reason, u.requested_by,"
                    + " u.checked_by, u.authorised_by, u.approval_id, u.executed_at FROM"
                    + " undo_reversal_register u JOIN payroll_cycle_instance c ON"
                    + " c.cycle_instance_id = u.cycle_instance_id WHERE c.org_unit_id = ?";

    private final JdbcTemplate jdbc;
    private final OrgDirectory org;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final AuditService audit;
    private final CycleLeases leases;
    private final ObjectMapper json;
    private final Map<String, UndoableTransaction> kinds;

    public CycleUndo(
            JdbcTemplate jdbc,
            OrgDirectory org,
            Authorization policy,
            MakerChecker approvals,
            AuditService audit,
            CycleLeases leases,
            ObjectMapper json,
            List<UndoableTransaction> kinds) {
        this.jdbc = jdbc;
        this.org = org;
        this.policy = policy;
        this.approvals = approvals;
        this.audit = audit;
        this.leases = leases;
        this.json = json;
        this.kinds =
                kinds.stream()
                        .collect(
                                Collectors.toMap(UndoableTransaction::entity, Function.identity()));
    }

    /** The registered kind of {@code entity}, if any module registered one. */
    public Optional<UndoableTransaction> kind(String entity) {
        return Optional.ofNullable(kinds.get(entity));
    }

    /**
     * SRS-15-3: requests the undo of the transaction {@code entity} / {@code key} of the cycle of
     * {@code period} at the Maker's unit.
     *
     * @throws FciException CYC-422-UNDO-TYPE (no undo for this kind), NOT_FOUND (no such
     *     transaction, or one of another cycle: SRS-15-6), CYC-409-POSTED (posted to EBS),
     *     CYC-409-LEASE (another action holds the cycle)
     */
    @Transactional
    public long requestUndo(
            AccessContext context, YearMonth period, String entity, String key, String reason) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, "C-02", "UNDO", unit.getId());
        UndoableTransaction kind =
                kind(entity).orElseThrow(() -> new FciException(ApiErrorCode.CYCLE_UNDO_TYPE));
        Map<String, Object> cycle =
                jdbc
                        .queryForList(
                                "SELECT cycle_instance_id, posted_to_ebs_flag FROM"
                                        + " payroll_cycle_instance WHERE payroll_yymm = ? AND"
                                        + " org_unit_id = ?",
                                CycleKeys.yymm(period),
                                unit.getId())
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        long cycleId = ((Number) cycle.get("CYCLE_INSTANCE_ID")).longValue();
        UndoableTransaction.Target target =
                kind.find(key)
                        .filter(t -> t.cycleInstanceId() == cycleId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if ("Y".equals(cycle.get("POSTED_TO_EBS_FLAG"))) {
            throw new FciException(ApiErrorCode.CYCLE_POSTED);
        }
        leases.requireFree(cycleId);
        // The module's label and stage may be empty: no Map.of (it refuses nulls).
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cycleInstanceId", cycleId);
        payload.put("period", period.toString());
        payload.put("unit", unit.getCode());
        payload.put("entity", entity);
        payload.put("key", key);
        payload.put("label", target.label());
        payload.put("stage", target.stage());
        payload.put("reason", reason);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                "C-02",
                                "UNDO",
                                UNDO_ENTITY,
                                entity + ":" + key,
                                ActionType.UPDATE,
                                unit.getId(),
                                json.writeValueAsString(payload),
                                period.atEndOfMonth(),
                                reason),
                        null);
        audit.record(
                login(context),
                AuditService.Entry.change(
                                UNDO_ENTITY,
                                entity + ":" + key,
                                "undo_request",
                                null,
                                "PENDING_APPROVAL",
                                reason)
                        .approval(item.id())
                        .module("C-02"));
        return item.id();
    }

    /** SRS-15-17: the register of the session's unit, newest first, one page. */
    @Transactional(readOnly = true)
    public Paged<RegisterEntry> register(AccessContext context, PageSpec spec) {
        OrgLocation unit = unit(context);
        policy.requireAt(context, "C-01", "VIEW", unit.getId());
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM undo_reversal_register u JOIN payroll_cycle_instance"
                                + " c ON c.cycle_instance_id = u.cycle_instance_id WHERE"
                                + " c.org_unit_id = ?",
                        Long.class,
                        unit.getId());
        List<RegisterEntry> rows =
                jdbc.query(
                        REGISTER
                                + " ORDER BY u.executed_at DESC, u.undo_id DESC OFFSET ? ROWS"
                                + " FETCH NEXT ? ROWS ONLY",
                        (rs, i) -> entry(rs),
                        unit.getId(),
                        (long) spec.page() * spec.size(),
                        spec.size());
        return new Paged<>(rows, spec.page(), spec.size(), CycleSupport.orZero(total));
    }

    /** SRS-G-13: the rows the export will carry (checked against the limit first). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context) {
        return register(context, new PageSpec(0, 1)).total();
    }

    /**
     * SRS-15-17, SRS-G-13: the whole register of the unit in one read-only snapshot, newest first
     * as on screen; the rows written are checked against the snapshot's count.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(AccessContext context, ExportSink sink) throws IOException {
        OrgLocation unit = unit(context);
        policy.requireAt(context, "C-01", "EXPORT", unit.getId());
        Long expected =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM undo_reversal_register u JOIN payroll_cycle_instance"
                                + " c ON c.cycle_instance_id = u.cycle_instance_id WHERE"
                                + " c.org_unit_id = ?",
                        Long.class,
                        unit.getId());
        sink.header(
                "txn",
                "type",
                "period",
                "gate_or_object",
                "key",
                "stage",
                "reason",
                "requested_by",
                "checked_by",
                "authorised_by",
                "approval_id",
                "executed_at");
        CycleSupport.exporting(
                () ->
                        jdbc.query(
                                REGISTER + " ORDER BY u.executed_at DESC, u.undo_id DESC",
                                rs -> {
                                    RegisterEntry e = entry(rs);
                                    CycleSupport.row(
                                            sink,
                                            e.id(),
                                            e.type(),
                                            e.period(),
                                            e.entity(),
                                            e.key(),
                                            e.stage(),
                                            e.reason(),
                                            e.requestedBy(),
                                            e.checkedBy(),
                                            e.authorisedBy(),
                                            e.approvalId(),
                                            e.executedAt());
                                },
                                unit.getId()));
        ExportSink.requireComplete(CycleSupport.orZero(expected), sink);
    }

    private static RegisterEntry entry(ResultSet rs) throws SQLException {
        Timestamp at = rs.getTimestamp(12);
        return new RegisterEntry(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getString(9),
                rs.getString(10),
                rs.getObject(11, Long.class),
                CycleSupport.instant(at));
    }

    private OrgLocation unit(AccessContext context) {
        return CycleSupport.unit(org, context);
    }

    private static String login(AccessContext context) {
        return CycleSupport.login(context);
    }
}
