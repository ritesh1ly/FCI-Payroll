package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * M-16 Reference &amp; Combo List Administration (target DDL v4.0 {@code reference_list} / {@code
 * reference_value}, Hindi labels in {@code label_master}): the HQ System Administrator proposes a
 * new value (CREATE) or a change of one (EDIT: English and Hindi label, order, last day of use);
 * the Payroll Checker at HQ approves in W-01 (workflow actions M16_CREATE / M16_EDIT; never the
 * maker, WF-403-SOD). Nothing is written until the approval: the proposed state travels in the
 * approval item ({@code reference_value} keeps one row per list and code), and {@link
 * ReferenceListEffects} applies it and marks the REFERENCE cache region changed, so every instance
 * serves it within seconds.
 *
 * <p>System lists ({@code is_system_list} = Y) hold codes the platform resolves (CHECK constraints,
 * business rules): their values may be relabelled, reordered or ended, never given a new code. A
 * value is never deleted or renamed; ending it keeps its label for older records.
 */
@Service
@Loggable
public class ReferenceListService {

    static final String SCREEN = "M-16";
    public static final String ENTITY = "reference_value";
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_]{1,40}$");

    /** One value as M-16 shows it, with the change awaiting the checker, if any. */
    public record ValueRow(
            String code,
            String labelEn,
            String labelHi,
            Integer sortOrder,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            boolean active,
            int versionNo,
            String changedBy,
            Instant changedAt,
            PendingChange pending) {}

    public record ListRow(
            String code,
            String name,
            String module,
            boolean system,
            List<ValueRow> values,
            List<PendingChange> pendingNew) {}

    /** A proposal awaiting the checker. */
    public record PendingChange(
            Long approvalId,
            String op,
            String code,
            String labelEn,
            String labelHi,
            Integer sortOrder,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String submittedBy,
            Instant submittedAt) {}

    /**
     * The proposed state of a value. CREATE: code, labels, order, first day of use. EDIT: labels,
     * order and last day of use (null: no end); the code and first day never change.
     */
    public record Proposal(
            String code,
            String labelEn,
            String labelHi,
            Integer sortOrder,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason) {}

    /** What {@link ReferenceListEffects} applies on approval (the approval item's payload). */
    public record Change(
            String op,
            String list,
            String code,
            String labelEn,
            String labelHi,
            Integer sortOrder,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason,
            String maker) {}

    private final JdbcTemplate jdbc;
    private final ReferenceData reference;
    private final MakerChecker approvals;
    private final ApprovalStatuses statuses;
    private final Authorization policy;
    private final ObjectMapper json;
    private final Clock clock;

    public ReferenceListService(
            JdbcTemplate jdbc,
            ReferenceData reference,
            MakerChecker approvals,
            ApprovalStatuses statuses,
            Authorization policy,
            ObjectMapper json,
            Clock clock) {
        this.clock = clock;
        this.jdbc = jdbc;
        this.reference = reference;
        this.approvals = approvals;
        this.statuses = statuses;
        this.policy = policy;
        this.json = json;
    }

    /**
     * Every list with all its values (in force or not) and the changes awaiting the checker; read
     * from the database.
     */
    @Transactional(readOnly = true)
    public List<ListRow> lists(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        LocalDate today = reference.today();
        Map<String, PendingChange> pendingByValue = new HashMap<>();
        Map<String, List<PendingChange>> pendingNew = new HashMap<>();
        for (ApprovalStatuses.Pending item : statuses.pending(ENTITY)) {
            Change c = read(item.payload());
            PendingChange p =
                    new PendingChange(
                            item.approvalId(),
                            c.op(),
                            c.code(),
                            c.labelEn(),
                            c.labelHi(),
                            c.sortOrder(),
                            c.effectiveFrom(),
                            c.effectiveTo(),
                            item.submittedBy(),
                            item.submittedAt());
            if ("CREATE".equals(c.op())) {
                pendingNew.computeIfAbsent(c.list(), k -> new ArrayList<>()).add(p);
            } else {
                pendingByValue.put(c.list() + "." + c.code(), p);
            }
        }
        Map<Long, String> hindi = new HashMap<>();
        jdbc.query(
                "SELECT master_code, label_text FROM label_master WHERE label_type = 'MASTER_VALUE'"
                    + " AND master_entity = ? AND language_code = 'HI' AND scope_level = 'GLOBAL'"
                    + " AND label_status = 'ACTIVE'",
                rs -> {
                    hindi.put(Long.valueOf(rs.getString(1)), rs.getString(2));
                },
                ReferenceData.LABEL_ENTITY);
        Map<String, ListRow> lists = new LinkedHashMap<>();
        Map<String, List<ValueRow>> values = new LinkedHashMap<>();
        jdbc.query(
                "SELECT list_code, list_name, owning_module, is_system_list FROM reference_list"
                        + " ORDER BY list_code",
                rs -> {
                    String code = rs.getString(1);
                    values.put(code, new ArrayList<>());
                    lists.put(
                            code,
                            new ListRow(
                                    code,
                                    rs.getString(2),
                                    rs.getString(3),
                                    "Y".equals(rs.getString(4)),
                                    values.get(code),
                                    pendingNew.getOrDefault(code, List.of())));
                });
        jdbc.query(
                "SELECT l.list_code, v.value_id, v.value_code, v.value_label_en, v.sort_order,"
                    + " v.effective_from, v.effective_to, v.version_no, v.changed_by, v.changed_at"
                    + " FROM reference_value v JOIN reference_list l ON l.list_id = v.list_id ORDER"
                    + " BY l.list_code, v.sort_order, v.value_code",
                rs -> {
                    int sort = rs.getInt(5);
                    Integer sortOrder = rs.wasNull() ? null : sort;
                    LocalDate from = rs.getDate(6).toLocalDate();
                    Date to = rs.getDate(7);
                    LocalDate end = to == null ? null : to.toLocalDate();
                    String list = rs.getString(1);
                    String code = rs.getString(3);
                    values.get(list)
                            .add(
                                    new ValueRow(
                                            code,
                                            rs.getString(4),
                                            hindi.get(rs.getLong(2)),
                                            sortOrder,
                                            from,
                                            end,
                                            !from.isAfter(today)
                                                    && (end == null || !end.isBefore(today)),
                                            rs.getInt(8),
                                            rs.getString(9),
                                            rs.getTimestamp(10) == null
                                                    ? null
                                                    : rs.getTimestamp(10).toInstant(),
                                            pendingByValue.get(list + "." + code)));
                });
        return List.copyOf(lists.values());
    }

    /** Proposes a new value of a list that is not a system list. */
    @Transactional
    public PendingChange proposeNew(
            AccessContext context, String list, Proposal proposal, OtpProof otp) {
        policy.require(context, SCREEN, "CREATE");
        requireHeadquarters(context);
        ListHead head = head(list);
        if (head.system()) {
            throw new FciException(
                    ApiErrorCode.REFERENCE_SYSTEM_LIST, new Object[] {list}, List.of(), null);
        }
        String code = proposal == null ? null : proposal.code();
        if (code == null || !CODE.matcher(code).matches()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        validate(proposal);
        LocalDate today = reference.today();
        if (proposal.effectiveFrom() == null
                || proposal.effectiveFrom().isBefore(today)
                || (proposal.effectiveTo() != null
                        && proposal.effectiveTo().isBefore(proposal.effectiveFrom()))) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (value(head.id(), code).isPresent()) {
            throw new FciException(
                    ApiErrorCode.REFERENCE_CODE_TAKEN, new Object[] {list, code}, List.of(), null);
        }
        requireLabelFree(head, list, code, proposal.labelEn().strip());
        requireNothingPending(list, code);
        Change change =
                new Change(
                        "CREATE",
                        list,
                        code,
                        proposal.labelEn().strip(),
                        blankToNull(proposal.labelHi()),
                        proposal.sortOrder(),
                        proposal.effectiveFrom(),
                        proposal.effectiveTo(),
                        proposal.reason().strip(),
                        context.loginId());
        return submit(context, change, "CREATE", ActionType.CREATE, list + "." + code, otp);
    }

    /** Proposes a change of a value: labels, order, last day of use. */
    @Transactional
    public PendingChange proposeChange(
            AccessContext context, String list, String code, Proposal proposal, OtpProof otp) {
        policy.require(context, SCREEN, "EDIT");
        requireHeadquarters(context);
        ListHead head = head(list);
        ValueState current =
                value(head.id(), code)
                        .orElseThrow(() -> new FciException(ApiErrorCode.MASTER_NOT_IN_CATALOGUE));
        validate(proposal);
        LocalDate today = reference.today();
        if (proposal.effectiveTo() != null
                && (proposal.effectiveTo().isBefore(today)
                        || proposal.effectiveTo().isBefore(current.effectiveFrom()))) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        String labelEn = proposal.labelEn().strip();
        String labelHi = blankToNull(proposal.labelHi());
        boolean unchanged =
                labelEn.equals(current.labelEn())
                        && Objects.equals(labelHi, hindiOf(current.valueId()))
                        && Objects.equals(proposal.sortOrder(), current.sortOrder())
                        && Objects.equals(proposal.effectiveTo(), current.effectiveTo());
        if (unchanged) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        // Only a new text is checked: a list seeded with two equal labels can still be reordered.
        if (!labelEn.equalsIgnoreCase(current.labelEn())) {
            requireLabelFree(head, list, code, labelEn);
        }
        requireNothingPending(list, code);
        Change change =
                new Change(
                        "EDIT",
                        list,
                        code,
                        labelEn,
                        labelHi,
                        proposal.sortOrder(),
                        current.effectiveFrom(),
                        proposal.effectiveTo(),
                        proposal.reason().strip(),
                        context.loginId());
        return submit(context, change, "EDIT", ActionType.UPDATE, list + "." + code, otp);
    }

    // ------------------------------------------------------------------ helpers (also used by the
    // effect)

    record ListHead(long id, boolean system) {}

    record ValueState(
            long valueId,
            String labelEn,
            Integer sortOrder,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {}

    ListHead head(String list) {
        if (list == null || !CODE.matcher(list).matches()) {
            throw new FciException(ApiErrorCode.MASTER_NOT_IN_CATALOGUE);
        }
        return jdbc
                .query(
                        "SELECT list_id, is_system_list FROM reference_list WHERE list_code = ?",
                        (rs, n) -> new ListHead(rs.getLong(1), "Y".equals(rs.getString(2))),
                        list)
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(ApiErrorCode.MASTER_NOT_IN_CATALOGUE));
    }

    Optional<ValueState> value(long listId, String code) {
        return jdbc
                .query(
                        "SELECT value_id, value_label_en, sort_order, effective_from, effective_to"
                                + " FROM reference_value WHERE list_id = ? AND value_code = ?",
                        (rs, n) -> {
                            int sort = rs.getInt(3);
                            Integer sortOrder = rs.wasNull() ? null : sort;
                            Date to = rs.getDate(5);
                            return new ValueState(
                                    rs.getLong(1),
                                    rs.getString(2),
                                    sortOrder,
                                    rs.getDate(4).toLocalDate(),
                                    to == null ? null : to.toLocalDate());
                        },
                        listId,
                        code)
                .stream()
                .findFirst();
    }

    String hindiOf(long valueId) {
        return jdbc
                .query(
                        "SELECT label_text FROM label_master WHERE label_type = 'MASTER_VALUE' AND"
                            + " master_entity = ? AND master_code = ? AND language_code = 'HI' AND"
                            + " scope_level = 'GLOBAL' AND label_status = 'ACTIVE'",
                        (rs, n) -> rs.getString(1),
                        ReferenceData.LABEL_ENTITY,
                        String.valueOf(valueId))
                .stream()
                .findFirst()
                .orElse(null);
    }

    Change read(String payload) {
        try {
            return json.readValue(payload, Change.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unreadable M-16 change", ex);
        }
    }

    private PendingChange submit(
            AccessContext context,
            Change change,
            String action,
            ActionType type,
            // The record key (LIST.CODE), never a description: the approval, its audit rows and
            // X-04 find the record by it (found 2026-10-24: "LIST.CODE · label: …" kept the
            // submission and decision out of the value's history). The reason says what changes.
            String key,
            OtpProof otp) {
        String payload = json.writeValueAsString(change);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                action,
                                ENTITY,
                                key,
                                type,
                                context.locationId(),
                                payload,
                                "CREATE".equals(change.op())
                                        ? change.effectiveFrom()
                                        : reference.today(),
                                change.reason()),
                        otp);
        return new PendingChange(
                item.id(),
                change.op(),
                change.code(),
                change.labelEn(),
                change.labelHi(),
                change.sortOrder(),
                change.effectiveFrom(),
                change.effectiveTo(),
                context.loginId(),
                clock.instant());
    }

    /**
     * SRS l.1450, SC-04-0067: a value is unique within its list, by the text shown in the dropdown
     * as well as by its code, without regard to case; a change awaiting approval counts.
     */
    private void requireLabelFree(ListHead head, String list, String code, String label) {
        Integer held =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM reference_value WHERE list_id = ? AND value_code <> ?"
                                + " AND UPPER(value_label_en) = ?",
                        Integer.class,
                        head.id(),
                        code,
                        label.toUpperCase(Locale.ROOT));
        boolean pending =
                statuses.pending(ENTITY).stream()
                        .map(item -> read(item.payload()))
                        .anyMatch(
                                c ->
                                        c.list().equals(list)
                                                && !c.code().equals(code)
                                                && c.labelEn().equalsIgnoreCase(label));
        if ((held != null && held > 0) || pending) {
            throw new FciException(
                    ApiErrorCode.REFERENCE_LABEL_TAKEN,
                    new Object[] {list, label},
                    List.of(),
                    null);
        }
    }

    private void requireNothingPending(String list, String code) {
        for (ApprovalStatuses.Pending item : statuses.pending(ENTITY)) {
            Change c = read(item.payload());
            if (c.list().equals(list) && c.code().equals(code)) {
                throw new FciException(
                        ApiErrorCode.REFERENCE_CHANGE_PENDING,
                        new Object[] {list + "." + code, item.approvalId()},
                        List.of(),
                        null);
            }
        }
    }

    private void requireHeadquarters(AccessContext context) {
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(CommonErrorCode.ACCESS_DENIED);
        }
    }

    /** English label 1-200, Hindi at most 200, order 0-99999, a reason. */
    static void validate(Proposal p) {
        boolean ok =
                p != null
                        && p.labelEn() != null
                        && !p.labelEn().isBlank()
                        && p.labelEn().strip().length() <= 200
                        && (p.labelHi() == null || p.labelHi().strip().length() <= 200)
                        && (p.sortOrder() == null
                                || (p.sortOrder() >= 0 && p.sortOrder() <= 99_999))
                        && p.reason() != null
                        && !p.reason().isBlank()
                        && p.reason().strip().length() <= 500;
        if (!ok) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
