package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.business.MasterImages;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The checker's final decision on an M-16 change, applied in the decision's transaction (a failure
 * rolls the decision back): an approved new value is inserted, an approved change updates the value
 * (version + 1, the approval recorded on the row); the Hindi label is versioned in {@code
 * label_master} (the previous one SUPERSEDED or WITHDRAWN, never deleted). Then the REFERENCE cache
 * region is marked changed, so every instance serves the new state on its next check. A rejected or
 * returned proposal changes nothing (nothing was written).
 */
@Component
public class ReferenceListEffects {

    private final JdbcTemplate jdbc;
    private final ReferenceListService lists;
    private final ReferenceData reference;
    private final MasterAudit audit;

    public ReferenceListEffects(
            JdbcTemplate jdbc,
            ReferenceListService lists,
            ReferenceData reference,
            MasterAudit audit) {
        this.jdbc = jdbc;
        this.lists = lists;
        this.reference = reference;
        this.audit = audit;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ReferenceListService.ENTITY) || !decision.approved()) {
            return;
        }
        ReferenceListService.Change change = lists.read(decision.payload());
        ReferenceListService.ListHead head = lists.head(change.list());
        Timestamp now = Timestamp.from(decision.decidedAt());
        long valueId;
        Map<String, String> before = null;
        LocalDate appliesFrom = change.effectiveFrom();
        if ("CREATE".equals(change.op())) {
            if (lists.value(head.id(), change.code()).isPresent()) {
                throw new FciException(
                        ApiErrorCode.REFERENCE_CODE_TAKEN,
                        new Object[] {change.list(), change.code()},
                        List.of(),
                        null);
            }
            jdbc.update(
                    "INSERT INTO reference_value (list_id, value_code, value_label_en, sort_order,"
                        + " version_no, effective_from, effective_to, approval_id, changed_by,"
                        + " changed_at, change_reason) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)",
                    head.id(),
                    change.code(),
                    change.labelEn(),
                    change.sortOrder(),
                    Date.valueOf(change.effectiveFrom()),
                    change.effectiveTo() == null ? null : Date.valueOf(change.effectiveTo()),
                    decision.approvalId(),
                    change.maker(),
                    now,
                    change.reason());
            valueId = lists.value(head.id(), change.code()).orElseThrow().valueId();
        } else {
            ReferenceListService.ValueState current =
                    lists.value(head.id(), change.code())
                            .orElseThrow(
                                    () -> new FciException(ApiErrorCode.MASTER_NOT_IN_CATALOGUE));
            before =
                    MasterImages.referenceValue(
                            current.labelEn(),
                            current.sortOrder(),
                            current.effectiveTo(),
                            lists.hindiOf(current.valueId()));
            appliesFrom = reference.today();
            jdbc.update(
                    "UPDATE reference_value SET value_label_en = ?, sort_order = ?, effective_to ="
                            + " ?, version_no = version_no + 1, approval_id = ?, changed_by = ?,"
                            + " changed_at = ?, change_reason = ? WHERE value_id = ?",
                    change.labelEn(),
                    change.sortOrder(),
                    change.effectiveTo() == null ? null : Date.valueOf(change.effectiveTo()),
                    decision.approvalId(),
                    change.maker(),
                    now,
                    change.reason(),
                    current.valueId());
            valueId = current.valueId();
        }
        hindi(valueId, change, decision, now);
        // SRS-20-2: the applied change, field by field, in this transaction (key LIST.CODE).
        audit.applied(
                decision,
                ReferenceListService.ENTITY,
                change.list() + "." + change.code(),
                ReferenceListService.SCREEN,
                appliesFrom,
                before,
                MasterImages.referenceValue(
                        change.labelEn(),
                        change.sortOrder(),
                        change.effectiveTo(),
                        change.labelHi()),
                List.of(),
                change.reason());
        reference.changed(decision.checkerLoginId());
    }

    /**
     * Versions the Hindi label: unchanged → nothing; blank → WITHDRAWN; new text → the old
     * SUPERSEDED, the new ACTIVE from today.
     */
    private void hindi(
            long valueId,
            ReferenceListService.Change change,
            ApprovalDecided decision,
            Timestamp now) {
        LocalDate today = reference.today();
        String key = "REF." + change.list() + "." + change.code();
        List<Object[]> active =
                jdbc.query(
                        "SELECT label_id, effective_from, label_text FROM label_master WHERE"
                            + " label_type = 'MASTER_VALUE' AND master_entity = ? AND master_code ="
                            + " ? AND language_code = 'HI' AND scope_level = 'GLOBAL' AND"
                            + " label_status = 'ACTIVE'",
                        (rs, n) ->
                                new Object[] {
                                    rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getString(3)
                                },
                        ReferenceData.LABEL_ENTITY,
                        String.valueOf(valueId));
        Object[] existing = active.isEmpty() ? null : active.get(0);
        if (existing != null && Objects.equals(existing[2], change.labelHi())) {
            return;
        }
        if (existing != null) {
            LocalDate from = (LocalDate) existing[1];
            if (change.labelHi() != null && !from.isBefore(today)) {
                // Started today (or later): correct the text in place; no second version on the
                // same day.
                jdbc.update(
                        "UPDATE label_master SET label_text = ?, approval_id = ?, changed_by = ?,"
                                + " changed_at = ?, change_reason = ?, version_no = version_no + 1"
                                + " WHERE label_id = ?",
                        change.labelHi(),
                        decision.approvalId(),
                        change.maker(),
                        now,
                        change.reason(),
                        existing[0]);
                return;
            }
            LocalDate end = from.isBefore(today) ? today.minusDays(1) : from;
            jdbc.update(
                    "UPDATE label_master SET label_status = ?, effective_to = ?, changed_by = ?,"
                            + " changed_at = ? WHERE label_id = ?",
                    change.labelHi() == null ? "WITHDRAWN" : "SUPERSEDED",
                    Date.valueOf(end),
                    change.maker(),
                    now,
                    existing[0]);
        }
        if (change.labelHi() != null) {
            Integer next =
                    jdbc.queryForObject(
                            "SELECT COALESCE(MAX(version_no), 0) + 1 FROM label_master"
                                    + " WHERE label_key = ? AND language_code = 'HI'",
                            Integer.class,
                            key);
            int version = Objects.requireNonNullElse(next, 1);
            jdbc.update(
                    "INSERT INTO label_master (label_key, label_type, master_entity, master_code,"
                        + " language_code, scope_level, label_text, is_localisable, version_no,"
                        + " effective_from, label_status, approval_id, changed_by, changed_at,"
                        + " change_reason) VALUES (?, 'MASTER_VALUE', ?, ?, 'HI', 'GLOBAL', ?, 'N',"
                        + " ?, ?, 'ACTIVE', ?, ?, ?, ?)",
                    key,
                    ReferenceData.LABEL_ENTITY,
                    String.valueOf(valueId),
                    change.labelHi(),
                    version,
                    Date.valueOf(today),
                    decision.approvalId(),
                    change.maker(),
                    now,
                    change.reason());
        }
    }
}
