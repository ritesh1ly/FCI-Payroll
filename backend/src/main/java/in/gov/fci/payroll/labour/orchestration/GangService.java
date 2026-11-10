package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
import in.gov.fci.payroll.labour.business.LabourRules;
import in.gov.fci.payroll.labour.business.LabourRules.GangRole;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * M-09 Gang / Shed Structure (plan 20 phase 20a; SRS-2-1, SRS-2-31, SRS-2-46, SRS-2-48;
 * BR-LABM-001, BR-LABM-002, BR-LABM-011; LC-15 … LC-18). The Establishment / HR Maker forms a
 * handling gang at a depot of the user's offices with exactly one Sardar, one Mandal and its
 * members, its name unique among the depot's active gangs, and its sanctioned strength; the Payroll
 * Checker of the office approves (route M09_GANG). The depot's Ancillary Pool is created by the
 * system with the depot's first gang (one per depot, no Sardar or Mandal, no strength). Membership
 * and strength changes go through the same route. A labour belongs to one gang at a time; a labour
 * past the retirement date is never assigned (LC-11).
 */
@Service
@Loggable
public class GangService {

    static final String SCREEN = "M-09";
    static final String ENTITY = "labour_gang";
    static final String ROUTE = "M09_GANG";

    /** One member as the maker assigns them. */
    public record Member(long employeeId, GangRole role) {}

    /** A new gang (M-09). */
    public record NewGang(
            Long depotLocationId,
            String payrollType,
            String gangName,
            Integer sanctionedStrength,
            LocalDate effectiveFrom,
            List<Member> members,
            String reason) {}

    /** One gang as M-09 lists it. */
    public record Row(
            long gangId,
            String gangCode,
            String gangName,
            String depot,
            String payrollType,
            String gangType,
            String status,
            Integer sanctionedStrength,
            long members,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {}

    /** One member of a gang on a day. */
    public record MemberRow(long employeeId, String labourCode, String name, String role) {}

    /** What the approval carries. */
    record Proposal(String kind, NewGang gang, Long gangId) {}

    private static final String FROM =
            " FROM labour_gang g JOIN org_location l ON l.location_id = g.depot_location_id WHERE"
                    + " (:all = 'Y' OR l.location_id IN (:locations) OR l.parent_location_id IN"
                    + " (:locations)) AND (:depot IS NULL OR g.depot_location_id = :depot) AND"
                    + " (:type IS NULL OR g.payroll_type = :type) AND (:gangType IS NULL OR"
                    + " g.gang_type = :gangType) AND (:status IS NULL OR g.gang_status = :status)"
                    + " AND (:text IS NULL OR UPPER(g.gang_code) LIKE :text OR UPPER(g.gang_name)"
                    + " LIKE :text)";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final OrgDirectory org;
    private final MakerChecker approvals;
    private final SystemParameters parameters;
    private final AuditService audit;
    private final ObjectMapper json;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public GangService(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate named,
            Authorization policy,
            OrgDirectory org,
            MakerChecker approvals,
            SystemParameters parameters,
            AuditService audit,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.named = named;
        this.policy = policy;
        this.org = org;
        this.approvals = approvals;
        this.parameters = parameters;
        this.audit = audit;
        this.json = json;
    }

    /**
     * A gang search (I-01 l.4541 "gangs at the selected depot", SRS-2-46 the Ancillary Pool,
     * SRS-2-31 a target gang of the labour's payroll type): each criterion optional, always within
     * the session's depots; the text matches the gang code or name, any case.
     */
    public record Search(
            Long depot, String payrollType, String gangType, String status, String text) {}

    /** M-09: the gangs of the session's depots; paged. */
    @Transactional(readOnly = true)
    public Paged<Row> page(AccessContext context, Long depotId, PageSpec spec) {
        return page(context, new Search(depotId, null, null, null, null), spec);
    }

    /** {@link #page(AccessContext, Long, PageSpec)} with every search criterion (server side). */
    @Transactional(readOnly = true)
    public Paged<Row> page(AccessContext context, Search search, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        return rows(OfficeScope.of(context, policy.isAtHeadquarters(context)), search, spec);
    }

    /**
     * M-02 transfer: the receiving gangs a transfer may name (SRS-2-6 "between gangs, depots and
     * Divisional Offices"; SRS l.4429, l.4937 "the destination gang or Divisional Office"): the
     * active handling gangs of the payroll type in any office, read only by a user who may start a
     * transfer (M-02 EDIT; OD-88). Only the gang's code, name, depot and counts are shown; the
     * members of another office's gang stay in its scope.
     */
    @Transactional(readOnly = true)
    public Paged<Row> transferTargets(
            AccessContext context, String payrollType, String text, PageSpec spec) {
        policy.require(context, "M-02", "EDIT");
        if (payrollType == null || !LabourRules.PAYROLL_TYPES.contains(payrollType)) {
            throw invalid("PAYROLL_TYPE");
        }
        return rows(
                OfficeScope.of(context, true),
                new Search(null, payrollType, "HANDLING", "ACTIVE", text),
                spec);
    }

    private Paged<Row> rows(MapSqlParameterSource scope, Search search, PageSpec spec) {
        String text =
                search.text() == null || search.text().isBlank()
                        ? null
                        : "%" + search.text().strip().toUpperCase(java.util.Locale.ROOT) + "%";
        MapSqlParameterSource p =
                scope.addValue("depot", search.depot(), Types.BIGINT)
                        .addValue("type", search.payrollType(), Types.VARCHAR)
                        .addValue("gangType", search.gangType(), Types.VARCHAR)
                        .addValue("status", search.status(), Types.VARCHAR)
                        .addValue("text", text, Types.VARCHAR)
                        .addValue("today", Date.valueOf(parameters.today()));
        Long total = named.queryForObject("SELECT COUNT(*)" + FROM, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<Row> rows =
                named.query(
                        "SELECT g.gang_id, g.gang_code, g.gang_name, l.location_code,"
                                + " l.location_name, g.payroll_type, g.gang_type, g.gang_status,"
                                + " g.effective_from, g.effective_to, (SELECT s.sanctioned_strength"
                                + " FROM labour_gang_strength s WHERE s.gang_id = g.gang_id AND"
                                + " s.effective_from <= :today AND (s.effective_to IS NULL OR"
                                + " s.effective_to >= :today)) AS strength, (SELECT COUNT(*) FROM"
                                + " labour_gang_membership m WHERE m.gang_id = g.gang_id AND"
                                + " m.effective_from <= :today AND (m.effective_to IS NULL OR"
                                + " m.effective_to >= :today)) AS members"
                                + FROM
                                + " ORDER BY l.location_code, g.gang_type, g.gang_name, g.gang_id"
                                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                        p,
                        (rs, i) -> {
                            int strength = rs.getInt("strength");
                            boolean none = rs.wasNull();
                            return new Row(
                                    rs.getLong("gang_id"),
                                    rs.getString("gang_code"),
                                    rs.getString("gang_name"),
                                    rs.getString("location_code")
                                            + " - "
                                            + rs.getString("location_name"),
                                    rs.getString("payroll_type"),
                                    rs.getString("gang_type"),
                                    rs.getString("gang_status"),
                                    none ? null : strength,
                                    rs.getLong("members"),
                                    rs.getDate("effective_from").toLocalDate(),
                                    rs.getDate("effective_to") == null
                                            ? null
                                            : rs.getDate("effective_to").toLocalDate());
                        });
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** M-09: the members of a gang today. */
    @Transactional(readOnly = true)
    public List<MemberRow> members(AccessContext context, long gangId) {
        return members(context, gangId, null);
    }

    /**
     * The members of a gang on a day (today when none): I-02 l.4632 "the gang's active labour,
     * including labour attached for the date" (an attachment is a membership for its dates,
     * SRS-2-47); a labour past the retirement date that day is left out (SRS-2-2), as the
     * work-slip's own check (SRS-2-4).
     */
    @Transactional(readOnly = true)
    public List<MemberRow> members(AccessContext context, long gangId, LocalDate on) {
        Map<String, Object> g = gang(gangId);
        LocalDate day = on == null ? parameters.today() : on;
        // At the depot: the Labour Dealing Hand's scope is the depot (SRS role matrix: own depot).
        policy.requireRecordAt(
                context, SCREEN, "VIEW", ((Number) g.get("DEPOT_LOCATION_ID")).longValue());
        return jdbc.query(
                "SELECT e.employee_id, p.labour_number, e.first_name, e.last_name, m.gang_role FROM"
                        + " labour_gang_membership m JOIN employee e ON e.employee_id ="
                        + " m.employee_id JOIN labour_profile p ON p.employee_id = e.employee_id"
                        + " WHERE m.gang_id = ? AND m.effective_from <= ? AND (m.effective_to IS"
                        + " NULL OR m.effective_to >= ?) AND (? = 'N' OR e.retirement_date IS NULL"
                        + " OR e.retirement_date >= ?) ORDER BY CASE m.gang_role WHEN 'SARDAR'"
                        + " THEN 0 WHEN 'MANDAL' THEN 1 ELSE 2 END, p.labour_number,"
                        + " e.employee_id",
                (rs, i) -> {
                    String last = rs.getString("last_name");
                    return new MemberRow(
                            rs.getLong("employee_id"),
                            rs.getString("labour_number"),
                            last == null || last.isBlank()
                                    ? rs.getString("first_name")
                                    : rs.getString("first_name") + " " + last,
                            rs.getString("gang_role"));
                },
                gangId,
                Date.valueOf(day),
                Date.valueOf(day),
                on == null ? "N" : "Y",
                Date.valueOf(day));
    }

    /** M-09: a new handling gang for the Payroll Checker (SRS-2-1, SRS-2-31). */
    @Transactional
    public SubmittedApproval create(AccessContext context, NewGang g) {
        OrgLocation office = checked(context, g);
        return submit(
                context,
                office,
                "NEW-" + UUID.randomUUID(),
                new Proposal("CREATE", g, null),
                ActionType.CREATE,
                g.reason());
    }

    /**
     * SRS-2-31 / SRS-2-46 (M-09 gang assignment): a labour joins the gang from a date as handling
     * labour, or as ancillary labour of the depot's Ancillary Pool; the labour is active, of the
     * depot and payroll type, not retired and in no gang from that date (LBR-20e-1).
     */
    @Transactional
    public SubmittedApproval addMember(
            AccessContext context, long gangId, long employeeId, LocalDate from, String reason) {
        Map<String, Object> g = gang(gangId);
        if (!"ACTIVE".equals(g.get("GANG_STATUS"))) {
            throw invalid("GANG_NOT_ACTIVE");
        }
        requireReason(reason);
        OrgLocation office = officeOf(g);
        policy.requireRecordAt(context, SCREEN, "EDIT", office.getId());
        if (from == null || from.isBefore(parameters.today())) {
            throw invalid("EFFECTIVE_FROM");
        }
        GangRole role =
                "ANCILLARY_POOL".equals(g.get("GANG_TYPE"))
                        ? GangRole.ANCILLARY
                        : GangRole.HANDLING;
        Member m = new Member(employeeId, role);
        member(
                m,
                ((Number) g.get("DEPOT_LOCATION_ID")).longValue(),
                (String) g.get("PAYROLL_TYPE"),
                from);
        NewGang change = new NewGang(null, null, null, null, from, List.of(m), reason.strip());
        return submit(
                context,
                office,
                gangId + ":" + employeeId,
                new Proposal("ADD", change, gangId),
                ActionType.UPDATE,
                reason);
    }

    /**
     * SRS-2-1 / BR-LABM-001: a new Sardar or Mandal from a date, a handling member of the gang; the
     * outgoing one stays in the gang as handling labour, so the gang keeps exactly one of each.
     */
    @Transactional
    public SubmittedApproval changeLeader(
            AccessContext context,
            long gangId,
            GangRole role,
            long employeeId,
            LocalDate from,
            String reason) {
        Map<String, Object> g = gang(gangId);
        if (!"HANDLING".equals(g.get("GANG_TYPE")) || !"ACTIVE".equals(g.get("GANG_STATUS"))) {
            throw invalid("NOT_AN_ACTIVE_HANDLING_GANG");
        }
        if (role != GangRole.SARDAR && role != GangRole.MANDAL) {
            throw invalid("ROLE");
        }
        requireReason(reason);
        OrgLocation office = officeOf(g);
        policy.requireRecordAt(context, SCREEN, "EDIT", office.getId());
        if (from == null || from.isBefore(parameters.today())) {
            throw invalid("EFFECTIVE_FROM");
        }
        Integer handling =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_gang_membership WHERE gang_id = ? AND"
                            + " employee_id = ? AND gang_role = 'HANDLING' AND effective_from <= ?"
                            + " AND (effective_to IS NULL OR effective_to >= ?)",
                        Integer.class,
                        gangId,
                        employeeId,
                        Date.valueOf(from),
                        Date.valueOf(from));
        if (handling == null || handling == 0) {
            throw invalid("NOT_A_HANDLING_MEMBER " + employeeId);
        }
        NewGang change =
                new NewGang(
                        null,
                        null,
                        null,
                        null,
                        from,
                        List.of(new Member(employeeId, role)),
                        reason.strip());
        return submit(
                context,
                office,
                gangId + ":" + role.name(),
                new Proposal("LEADER", change, gangId),
                ActionType.UPDATE,
                reason);
    }

    /**
     * SRS-2-7 / SRS-2-31 / BR-LABM-006: a handling gang ends at a date, never deleted: refused
     * while a work-slip of the gang exists after the date or a member stays after it (move the
     * members first, D20-14). The Payroll Checker approves.
     */
    @Transactional
    public SubmittedApproval endDate(
            AccessContext context, long gangId, LocalDate to, String reason) {
        Map<String, Object> g = gang(gangId);
        if (!"HANDLING".equals(g.get("GANG_TYPE")) || !"ACTIVE".equals(g.get("GANG_STATUS"))) {
            throw invalid("NOT_AN_ACTIVE_HANDLING_GANG");
        }
        requireReason(reason);
        OrgLocation office = officeOf(g);
        policy.requireRecordAt(context, SCREEN, "EDIT", office.getId());
        if (to == null || to.isBefore(parameters.today().minusDays(1))) {
            throw invalid("END_DATE");
        }
        Integer slips =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM work_slip WHERE gang_id = ? AND work_slip_date > ?"
                                + " AND slip_status NOT IN ('AMENDED', 'CANCELLED')",
                        Integer.class,
                        gangId,
                        Date.valueOf(to));
        if (slips != null && slips > 0) {
            throw invalid("WORK_SLIPS_AFTER");
        }
        Integer members =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_gang_membership WHERE gang_id = ? AND"
                                + " (effective_to IS NULL OR effective_to > ?)",
                        Integer.class,
                        gangId,
                        Date.valueOf(to));
        if (members != null && members > 0) {
            throw invalid("MEMBERS_AFTER");
        }
        NewGang change = new NewGang(null, null, null, null, to, List.of(), reason.strip());
        return submit(
                context,
                office,
                String.valueOf(gangId),
                new Proposal("END", change, gangId),
                ActionType.END_DATE,
                reason);
    }

    /** M-09: a new sanctioned strength from a date (SRS-2-48). */
    @Transactional
    public SubmittedApproval strength(
            AccessContext context, long gangId, int strength, LocalDate from, String reason) {
        Map<String, Object> g = gang(gangId);
        if (!"HANDLING".equals(g.get("GANG_TYPE")) || !"ACTIVE".equals(g.get("GANG_STATUS"))) {
            throw invalid("NOT_AN_ACTIVE_HANDLING_GANG");
        }
        if (strength < 1 || strength > 9999 || from == null || from.isBefore(parameters.today())) {
            throw invalid("STRENGTH");
        }
        requireReason(reason);
        OrgLocation office = officeOf(g);
        policy.requireRecordAt(context, SCREEN, "EDIT", office.getId());
        NewGang change = new NewGang(null, null, null, strength, from, List.of(), reason.strip());
        return submit(
                context,
                office,
                String.valueOf(gangId),
                new Proposal("STRENGTH", change, gangId),
                ActionType.UPDATE,
                reason);
    }

    /** The Payroll Checker's approval forms the gang, its pool and its members. */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY) || !decision.approved()) {
            return;
        }
        Proposal p;
        try {
            p = json.readValue(decision.payload(), Proposal.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unreadable gang proposal", ex);
        }
        if ("ADD".equals(p.kind())) {
            Member m = p.gang().members().getFirst();
            jdbc.update(
                    "INSERT INTO labour_gang_membership (gang_id, employee_id, gang_role,"
                            + " effective_from, approval_id) VALUES (?, ?, ?, ?, ?)",
                    p.gangId(),
                    m.employeeId(),
                    m.role().name(),
                    Date.valueOf(p.gang().effectiveFrom()),
                    decision.approvalId());
            return;
        }
        if ("LEADER".equals(p.kind())) {
            Member m = p.gang().members().getFirst();
            LocalDate from = p.gang().effectiveFrom();
            // The outgoing leader becomes handling labour; the incoming handling member, the
            // leader.
            List<Long> outgoing =
                    jdbc.queryForList(
                            "SELECT employee_id FROM labour_gang_membership WHERE gang_id = ? AND"
                                + " gang_role = ? AND effective_from < ? AND (effective_to IS NULL"
                                + " OR effective_to >= ?)",
                            Long.class,
                            p.gangId(),
                            m.role().name(),
                            Date.valueOf(from),
                            Date.valueOf(from));
            end(p.gangId(), m.employeeId(), from, "LEADER_CHANGE");
            for (Long employee : outgoing) {
                end(p.gangId(), employee, from, "LEADER_CHANGE");
            }
            for (Long employee : outgoing) {
                join(p.gangId(), employee, GangRole.HANDLING, from, decision.approvalId());
            }
            join(p.gangId(), m.employeeId(), m.role(), from, decision.approvalId());
            return;
        }
        if ("END".equals(p.kind())) {
            jdbc.update(
                    "UPDATE labour_gang SET gang_status = 'END_DATED', effective_to = ?,"
                            + " row_version = row_version + 1 WHERE gang_id = ? AND gang_status ="
                            + " 'ACTIVE'",
                    Date.valueOf(p.gang().effectiveFrom()),
                    p.gangId());
            audit.record(
                    decision.checkerLoginId(),
                    AuditService.Entry.change(
                                    ENTITY,
                                    String.valueOf(p.gangId()),
                                    "gang_status",
                                    "ACTIVE",
                                    "END_DATED",
                                    p.gang().reason())
                            .approval(decision.approvalId())
                            .module(SCREEN)
                            .effective(p.gang().effectiveFrom()));
            return;
        }
        if ("STRENGTH".equals(p.kind())) {
            setStrength(
                    p.gangId(),
                    p.gang().sanctionedStrength(),
                    p.gang().effectiveFrom(),
                    p.gang().reason(),
                    decision);
            return;
        }
        NewGang g = p.gang();
        // BR-LABM-002: the name is checked again when the gang is formed.
        Integer named =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_gang WHERE depot_location_id = ? AND"
                                + " gang_status = 'ACTIVE' AND UPPER(gang_name) = ?",
                        Integer.class,
                        g.depotLocationId(),
                        g.gangName().strip().toUpperCase(java.util.Locale.ROOT));
        if (named != null && named > 0) {
            throw invalid("NAME_TAKEN");
        }
        ensurePool(
                g.depotLocationId(), g.payrollType(), g.effectiveFrom(), decision.checkerLoginId());
        String code = gangCode(g.depotLocationId());
        jdbc.update(
                "INSERT INTO labour_gang (gang_code, gang_name, depot_location_id, payroll_type,"
                    + " gang_type, gang_status, effective_from, approval_id) VALUES (?, ?, ?, ?,"
                    + " 'HANDLING', 'ACTIVE', ?, ?)",
                code,
                g.gangName().strip(),
                g.depotLocationId(),
                g.payrollType(),
                Date.valueOf(g.effectiveFrom()),
                decision.approvalId());
        long gangId = gangIdOf(code, g.effectiveFrom());
        for (Member m : g.members()) {
            jdbc.update(
                    "INSERT INTO labour_gang_membership (gang_id, employee_id, gang_role,"
                            + " effective_from, approval_id) VALUES (?, ?, ?, ?, ?)",
                    gangId,
                    m.employeeId(),
                    m.role().name(),
                    Date.valueOf(g.effectiveFrom()),
                    decision.approvalId());
        }
        if (g.sanctionedStrength() != null) {
            setStrength(gangId, g.sanctionedStrength(), g.effectiveFrom(), g.reason(), decision);
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                code,
                                "gang_status",
                                null,
                                "ACTIVE",
                                "Gang " + g.gangName() + " formed")
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    // ---------------------------------------------------------------- checks

    /** SRS-2-1 / SRS-2-31 / LC-11 / LC-16: the gang, its depot in scope and its members. */
    private OrgLocation checked(AccessContext context, NewGang g) {
        if (g == null || !LabourRules.PAYROLL_TYPES.contains(g.payrollType())) {
            throw invalid("PAYROLL_TYPE");
        }
        if (!LabourRules.gangName(g.gangName())) {
            throw invalid("NAME");
        }
        if (g.effectiveFrom() == null || g.effectiveFrom().isBefore(parameters.today())) {
            throw invalid("EFFECTIVE_FROM");
        }
        // BR-LABM-011 / M-09 l.4498: a handling gang has its Sanctioned Strength (the pool, made by
        // ensurePool, has none).
        if (g.sanctionedStrength() == null
                || g.sanctionedStrength() < 1
                || g.sanctionedStrength() > 9999) {
            throw invalid("STRENGTH");
        }
        requireReason(g.reason());
        OrgLocation depot =
                org.location(java.util.Objects.requireNonNullElse(g.depotLocationId(), -1L))
                        .filter(l -> "DEPOT".equals(l.getType().name()))
                        .orElseThrow(() -> invalid("DEPOT"));
        OrgLocation office =
                org.cycleUnitOf(depot.getId()).orElseThrow(() -> invalid("DEPOT_OFFICE"));
        policy.requireAt(context, SCREEN, "CREATE", office.getId());
        List<Member> members = g.members() == null ? List.of() : g.members();
        LabourRules.gangRoles(members.stream().map(Member::role).toList(), false)
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        Integer taken =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_gang WHERE depot_location_id = ? AND"
                                + " gang_status = 'ACTIVE' AND UPPER(gang_name) = ?",
                        Integer.class,
                        depot.getId(),
                        g.gangName().strip().toUpperCase(java.util.Locale.ROOT));
        if ((taken != null && taken > 0) || pendingName(depot.getId(), g.gangName())) {
            throw invalid("NAME_TAKEN");
        }
        if (new HashSet<>(members.stream().map(Member::employeeId).toList()).size()
                != members.size()) {
            throw invalid("MEMBER_TWICE");
        }
        for (Member m : members) {
            member(m, depot.getId(), g.payrollType(), g.effectiveFrom());
        }
        return office;
    }

    /**
     * BR-LABM-002 (SRS-2-31 "unique within a depot"): a gang of the depot with the same name
     * already waits for the Payroll Checker.
     */
    private boolean pendingName(long depotId, String name) {
        String wanted = name.strip().toUpperCase(java.util.Locale.ROOT);
        for (String payload :
                jdbc.queryForList(
                        "SELECT payload FROM approval_queue WHERE entity_name = ? AND status ="
                                + " 'PENDING_APPROVAL' AND entity_key LIKE 'NEW-%'",
                        String.class, ENTITY)) {
            Proposal other;
            try {
                other = json.readValue(payload, Proposal.class);
            } catch (JacksonException ex) {
                continue;
            }
            if (other.gang() != null
                    && other.gang().gangName() != null
                    && Long.valueOf(depotId).equals(other.gang().depotLocationId())
                    && wanted.equals(
                            other.gang().gangName().strip().toUpperCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** A member: active labour of the depot and payroll type, not retired, in no other gang. */
    private void member(Member m, long depotId, String payrollType, LocalDate from) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT p.labour_status, p.depot_location_id, p.payroll_type,"
                                + " e.retirement_date FROM labour_profile p JOIN employee e ON"
                                + " e.employee_id = p.employee_id WHERE p.employee_id = ?",
                        m.employeeId());
        if (rows.isEmpty()) {
            throw invalid("MEMBER_UNKNOWN " + m.employeeId());
        }
        Map<String, Object> r = rows.getFirst();
        if (!"ACTIVE".equals(r.get("LABOUR_STATUS"))
                || r.get("DEPOT_LOCATION_ID") == null
                || ((Number) r.get("DEPOT_LOCATION_ID")).longValue() != depotId
                || !payrollType.equals(r.get("PAYROLL_TYPE"))) {
            throw invalid("MEMBER_NOT_OF_DEPOT " + m.employeeId());
        }
        Object retire = r.get("RETIREMENT_DATE");
        if (retire != null
                && LabourRules.retired(
                        new java.sql.Date(((java.util.Date) retire).getTime()).toLocalDate(),
                        from)) {
            throw invalid("MEMBER_RETIRED " + m.employeeId());
        }
        Integer busy =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_gang_membership WHERE employee_id = ? AND"
                                + " (effective_to IS NULL OR effective_to >= ?)",
                        Integer.class,
                        m.employeeId(),
                        Date.valueOf(from));
        if (busy != null && busy > 0) {
            throw invalid("MEMBER_IN_ANOTHER_GANG " + m.employeeId());
        }
    }

    // ---------------------------------------------------------------- effects

    /** SRS-2-46: the depot's Ancillary Pool, created once by the system. */
    private void ensurePool(long depotId, String payrollType, LocalDate from, String by) {
        Integer pools =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ancillary_pool WHERE depot_location_id = ? AND"
                                + " pool_status = 'ACTIVE'",
                        Integer.class,
                        depotId);
        if (pools != null && pools > 0) {
            return;
        }
        String code = gangCode(depotId);
        jdbc.update(
                "INSERT INTO labour_gang (gang_code, gang_name, depot_location_id, payroll_type,"
                    + " gang_type, gang_status, effective_from) VALUES (?, 'Ancillary Pool', ?, ?,"
                    + " 'ANCILLARY_POOL', 'ACTIVE', ?)",
                code,
                depotId,
                payrollType,
                Date.valueOf(from));
        long gang = gangIdOf(code, from);
        jdbc.update(
                "INSERT INTO ancillary_pool (depot_location_id, pool_gang_id, pool_status,"
                        + " effective_from, created_by) VALUES (?, ?, 'ACTIVE', ?, ?)",
                depotId,
                gang,
                Date.valueOf(from),
                by);
    }

    private void setStrength(
            long gangId, int strength, LocalDate from, String reason, ApprovalDecided d) {
        jdbc.update(
                "UPDATE labour_gang_strength SET effective_to = ? WHERE gang_id = ? AND"
                        + " effective_to IS NULL AND effective_from < ?",
                Date.valueOf(from.minusDays(1)),
                gangId,
                Date.valueOf(from));
        Integer version =
                jdbc.queryForObject(
                        "SELECT COALESCE(MAX(version_no), 0) FROM labour_gang_strength WHERE"
                                + " gang_id = ?",
                        Integer.class,
                        gangId);
        jdbc.update(
                "INSERT INTO labour_gang_strength (gang_id, sanctioned_strength, version_no,"
                    + " effective_from, reason, approval_id, changed_by) VALUES (?, ?, ?, ?, ?, ?,"
                    + " ?)",
                gangId,
                strength,
                (version == null ? 0 : version) + 1,
                Date.valueOf(from),
                reason,
                d.approvalId(),
                d.checkerLoginId());
        audit.record(
                d.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(gangId),
                                "sanctioned_strength",
                                null,
                                String.valueOf(strength),
                                reason)
                        .approval(d.approvalId())
                        .module(SCREEN)
                        .effective(from));
    }

    /** The depot's next gang code: the depot code and a sequence. */
    private String gangCode(long depotId) {
        Map<String, Object> depot =
                jdbc.queryForMap(
                        "SELECT location_code FROM org_location WHERE location_id = ?", depotId);
        Long count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_gang WHERE depot_location_id = ?",
                        Long.class,
                        depotId);
        String code =
                depot.get("LOCATION_CODE") + "-G" + String.format("%03d", RowCounts.of(count) + 1);
        return code.length() <= 20 ? code : code.substring(code.length() - 20);
    }

    private SubmittedApproval submit(
            AccessContext context,
            OrgLocation office,
            String key,
            Proposal p,
            ActionType type,
            String reason) {
        String payload;
        try {
            payload = json.writeValueAsString(p);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Gang payload", ex);
        }
        return approvals.submit(
                context,
                new Submission(
                        ROUTE,
                        SCREEN,
                        type == ActionType.CREATE ? "CREATE" : "EDIT",
                        ENTITY,
                        key,
                        type,
                        office.getId(),
                        payload,
                        parameters.today(),
                        reason.strip()),
                null);
    }

    /** The id of the gang just written under its code and date. */
    private long gangIdOf(String code, LocalDate from) {
        Long id =
                jdbc.queryForObject(
                        "SELECT gang_id FROM labour_gang WHERE gang_code = ? AND effective_from ="
                                + " ?",
                        Long.class,
                        code,
                        Date.valueOf(from));
        if (id == null) {
            throw new IllegalStateException("The gang just written is not found: " + code);
        }
        return id;
    }

    /** Ends the labour's membership of the gang the day before the date. */
    private void end(long gangId, long employeeId, LocalDate from, String why) {
        jdbc.update(
                "UPDATE labour_gang_membership SET effective_to = ?, end_reason = ? WHERE gang_id ="
                    + " ? AND employee_id = ? AND effective_from < ? AND (effective_to IS NULL OR"
                    + " effective_to >= ?)",
                Date.valueOf(from.minusDays(1)),
                why,
                gangId,
                employeeId,
                Date.valueOf(from),
                Date.valueOf(from));
    }

    private void join(long gangId, long employeeId, GangRole role, LocalDate from, Long approval) {
        jdbc.update(
                "INSERT INTO labour_gang_membership (gang_id, employee_id, gang_role,"
                        + " effective_from, approval_id) VALUES (?, ?, ?, ?, ?)",
                gangId,
                employeeId,
                role.name(),
                Date.valueOf(from),
                approval);
    }

    private Map<String, Object> gang(long gangId) {
        return jdbc
                .queryForList(
                        "SELECT gang_id, gang_code, depot_location_id, gang_type, gang_status,"
                                + " payroll_type FROM labour_gang WHERE gang_id = ?",
                        gangId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private OrgLocation officeOf(Map<String, Object> gang) {
        return org.cycleUnitOf(((Number) gang.get("DEPOT_LOCATION_ID")).longValue())
                .orElseThrow(() -> invalid("DEPOT_OFFICE"));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw invalid("REASON");
        }
    }

    private static FciException invalid(String defect) {
        return new FciException(
                LabourErrorCode.GANG, new Object[] {defect}, new ArrayList<>(), null);
    }
}
