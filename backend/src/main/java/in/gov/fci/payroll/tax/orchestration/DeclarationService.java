package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
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
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.selfservice.SelfServiceEvents;
import in.gov.fci.payroll.tax.business.DeclarationRules;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.model.ItDeclaration;
import in.gov.fci.payroll.tax.business.model.ItDeclaration.Kind;
import in.gov.fci.payroll.tax.business.model.ItDeclarationSubmission;
import in.gov.fci.payroll.tax.business.model.ItDeclarationSubmission.Status;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import in.gov.fci.payroll.tax.business.model.ItSection;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Income-tax and HRA (rent) declarations with the regime choice (I-07, E-01; SRS-7-1, SRS-7-14,
 * SRS-7-15, SRS-7-16, SRS-7-24, SRS-17-2; BR-TAX-001, BR-TAX-002, BR-TAX-014; plan 08 phase 08c).
 *
 * <p>The member submits in eSeva while the declaration window is open (M-18 {@code
 * TAX.DECLARATION.WINDOW}, not configured until approved). A line above its section limit is capped
 * and the member sees the cap. The Payroll Maker of the member's paying unit verifies every line or
 * returns the round with a reason; the verified round goes to the Payroll Checker (route
 * I07_SUBMIT), whose approval freezes the regime and the rent declaration for the year and makes
 * the round the one TDS reads. A rejection returns it to the member. A frozen regime opens again
 * only through a reset the Checker approves (route I07_RESET). No OTP (none of the 18 codes).
 */
@Service
@Loggable
public class DeclarationService {

    public static final String MEMBER_SCREEN = "E-01";
    public static final String SCREEN = "I-07";
    public static final String SUBMISSION_ENTITY = "it_declaration_submission";
    public static final String REGIME_ENTITY = "it_regime_detail";
    public static final String VERIFY_ROUTE = "I07_SUBMIT";
    public static final String RESET_ROUTE = "I07_RESET";
    public static final String WINDOW_KEY = "TAX.DECLARATION.WINDOW";
    public static final String PROOF_KEY = "TAX.PROOF.WINDOW";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** The particulars of a proof (SRS-G-30) and its file. */
    public record Proof(
            String documentTypeCode,
            String referenceNumber,
            LocalDate documentDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    /** One line as the member declares it, with its proof or none. */
    public record NewLine(Kind kind, String sectionCode, BigDecimal declared, Proof proof) {}

    /** A section the member may declare under, as the form offers it. */
    public record SectionOption(String code, String description, BigDecimal limit, String regime) {}

    /** One line as the screens show it. {@code capped}: the member is told (SRS-7-16). */
    public record LineView(
            Long id,
            Kind kind,
            String sectionCode,
            BigDecimal declared,
            BigDecimal allowed,
            boolean capped,
            String proofStatus,
            BigDecimal verified,
            boolean frozen,
            Long proofId) {}

    /** One round with its lines; the member's identity, never PAN (SRS-SEC-4). */
    public record SubmissionView(
            Long id,
            Long employeeId,
            String eis,
            String name,
            String finYear,
            int number,
            String status,
            Instant submittedAt,
            String verifiedBy,
            String returnReason,
            boolean proofDeadlineReversed,
            Regime regime,
            boolean regimeFrozen,
            List<LineView> lines) {}

    /** E-01: the member's year: the window, the regime, the sections and every round. */
    public record MyDeclarations(
            String finYear,
            boolean windowOpen,
            LocalDate proofsUntil,
            Regime regime,
            boolean regimeFrozen,
            List<SectionOption> sections,
            List<SubmissionView> rounds) {}

    /** The Payroll Maker's amount for one line. */
    public record LineVerification(Long lineId, BigDecimal verified) {}

    /** The proposal of a regime reset, as the Checker decides it. */
    public record ResetProposal(Long regimeDetailId, String finYear, String reason) {}

    private final TaxRepositories.Regimes regimes;
    private final TaxRepositories.Submissions submissions;
    private final TaxRepositories.Declarations lines;
    private final TaxRepositories.Sections sections;
    private final EmployeeDirectory employees;
    private final DocumentStore store;
    private final RuleEngine rules;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final AuditService audit;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public DeclarationService(
            TaxRepositories.Regimes regimes,
            TaxRepositories.Submissions submissions,
            TaxRepositories.Declarations lines,
            TaxRepositories.Sections sections,
            EmployeeDirectory employees,
            DocumentStore store,
            RuleEngine rules,
            MakerChecker approvals,
            Authorization policy,
            AuditService audit,
            ObjectMapper json,
            ApplicationEventPublisher events,
            Clock clock) {
        this.regimes = regimes;
        this.submissions = submissions;
        this.lines = lines;
        this.sections = sections;
        this.employees = employees;
        this.store = store;
        this.rules = rules;
        this.approvals = approvals;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
        this.events = events;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- member (E-01)

    /** E-01: the member's declarations of the current financial year. */
    @Transactional(readOnly = true)
    public MyDeclarations mine(AccessContext context) {
        policy.require(context, MEMBER_SCREEN, "SELF");
        Long me = self(context);
        LocalDate today = today();
        String year = DeclarationRules.finYearOf(today);
        Optional<ItRegimeDetail> regime = regimes.findByEmployeeIdAndFinYear(me, year);
        List<SubmissionView> rounds =
                views(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(me, year));
        return new MyDeclarations(
                year,
                windowOpen(today),
                proofsUntil(today).orElse(null),
                regime.map(ItRegimeDetail::getRegime).orElse(null),
                regime.map(ItRegimeDetail::isLocked).orElse(false),
                sections.inForceOn(today).stream()
                        .map(
                                s ->
                                        new SectionOption(
                                                s.getSectionCode(),
                                                s.getSectionDescription(),
                                                s.getMaxDeductionAmount(),
                                                s.getRegimeApplicability()))
                        .toList(),
                rounds);
    }

    /**
     * E-01: a new round of the member's declarations for the current year (SRS-7-24, SRS-17-2).
     * Refused while the window is closed or a round is at the Checker; a frozen regime or rent
     * declaration stays as approved.
     */
    @Transactional
    public SubmissionView submit(AccessContext context, Regime chosen, List<NewLine> declared) {
        policy.require(context, MEMBER_SCREEN, "CREATE");
        Long me = self(context);
        LocalDate today = today();
        if (!windowOpen(today)) {
            throw new FciException(TaxErrorCode.DECLARATION_WINDOW);
        }
        String year = DeclarationRules.finYearOf(today);
        Instant now = clock.instant();
        // The regime row is the member's lock for the year: two submissions never race.
        Optional<ItRegimeDetail> existing = regimes.lockFor(me, year);
        Regime regime =
                DeclarationRules.requireRegime(
                        chosen,
                        existing.filter(ItRegimeDetail::isLocked).map(ItRegimeDetail::getRegime),
                        year);
        List<NewLine> input = declared == null ? List.of() : declared;
        List<ItDeclarationSubmission> earlier =
                submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(me, year);
        Optional<ItDeclarationSubmission> latest = earlier.stream().findFirst();
        if (latest.isPresent() && !latest.get().replaceable()) {
            throw new FciException(TaxErrorCode.DECLARATION_PENDING);
        }
        List<ItDeclaration> frozenRent = frozenRent(earlier);
        if (!frozenRent.isEmpty() && input.stream().anyMatch(l -> l.kind() == Kind.HRA_RENT)) {
            throw new FciException(TaxErrorCode.RENT_FROZEN, new Object[] {year}, List.of(), null);
        }
        Map<String, ItSection> inForce =
                sections.inForceOn(today).stream()
                        .collect(Collectors.toMap(ItSection::getSectionCode, Function.identity()));
        List<DeclarationRules.Line> checked = new ArrayList<>();
        for (NewLine l : input) {
            if (l == null || l.kind() == null || l.sectionCode() == null) {
                throw new FciException(CommonErrorCode.VALIDATION_FAILED);
            }
            DeclarationRules.requireAmount(l.declared());
            DeclarationRules.requireSection(
                    l.sectionCode(),
                    Optional.ofNullable(inForce.get(l.sectionCode()))
                            .map(ItSection::getRegimeApplicability),
                    regime);
            checked.add(new DeclarationRules.Line(l.kind(), l.sectionCode(), l.declared()));
        }
        DeclarationRules.requireDistinct(checked);

        ItRegimeDetail regimeRow =
                existing.map(
                                r -> {
                                    if (!r.isLocked()) {
                                        r.elect(regime, now);
                                    }
                                    return r;
                                })
                        .orElseGet(() -> ItRegimeDetail.elected(me, year, regime, now));
        regimeRow = regimes.save(regimeRow);
        // A round still open at the Maker is replaced at once; an approved round stays the one TDS
        // reads until its successor is approved (approve() supersedes it then).
        latest.filter(s -> s.status() != Status.APPROVED)
                .ifPresent(
                        s -> {
                            s.supersede();
                            submissions.save(s);
                        });
        ItDeclarationSubmission round =
                submissions.save(
                        ItDeclarationSubmission.submitted(
                                me,
                                year,
                                latest.map(ItDeclarationSubmission::getSubmissionNo).orElse(0) + 1,
                                regimeRow.getRegimeDetailId(),
                                now));
        String key = String.valueOf(round.getSubmissionId());
        Long firstLine = null;
        for (NewLine l : input) {
            ItSection section = inForce.get(l.sectionCode());
            Long proof = l.proof() == null ? null : attach(context, key, l.proof());
            ItDeclaration saved =
                    lines.save(
                            ItDeclaration.declared(
                                    me,
                                    round.getSubmissionId(),
                                    year,
                                    l.kind(),
                                    l.sectionCode(),
                                    l.declared(),
                                    DeclarationRules.allowed(
                                            l.declared(), section.getMaxDeductionAmount()),
                                    proof,
                                    now));
            if (firstLine == null) {
                firstLine = saved.getDeclarationId();
            }
        }
        for (ItDeclaration r : frozenRent) {
            ItDeclaration carried = lines.save(r.carriedInto(round.getSubmissionId(), now));
            if (firstLine == null) {
                firstLine = carried.getDeclarationId();
            }
        }
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                SUBMISSION_ENTITY,
                                key,
                                "submission",
                                latest.map(s -> "round " + s.getSubmissionNo()).orElse(null),
                                "round "
                                        + round.getSubmissionNo()
                                        + " "
                                        + year
                                        + "; regime "
                                        + regime
                                        + "; lines "
                                        + input.size(),
                                "eSeva declaration")
                        .module(SCREEN));
        if (firstLine != null) {
            // SRS-17-2 / SRS-17-18 (plan 14 phase 14a, D14-6): the round in the eSeva log, linked
            // to its first declaration line, a carried rent line for a regime-only round (DDL
            // chk_ess_sub_tax_link); a round with no line at all is on I-07 only.
            events.publishEvent(
                    new SelfServiceEvents.Submitted(
                            me,
                            input.isEmpty()
                                    ? SelfServiceEvents.Type.REGIME_CHOICE
                                    : SelfServiceEvents.Type.TAX_DECLARATION,
                            SUBMISSION_ENTITY,
                            round.getSubmissionId(),
                            firstLine,
                            null,
                            null,
                            "Declarations "
                                    + year
                                    + " round "
                                    + round.getSubmissionNo()
                                    + ", regime "
                                    + regime
                                    + ", "
                                    + input.size()
                                    + " lines",
                            null,
                            context.loginId()));
        }
        return view(round);
    }

    // ---------------------------------------------------------------- Payroll Maker (I-07)

    /** I-07: one page of the rounds of a year in the user's offices, by status (null: all). */
    @Transactional(readOnly = true)
    public Paged<SubmissionView> register(
            AccessContext context, String finYear, String status, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        String year = finYear == null ? DeclarationRules.finYearOf(today()) : finYear;
        if (status != null) {
            Status.valueOf(status);
        }
        if (context.dataScope().isEmpty()) {
            return new Paged<>(List.of(), page.page(), page.size(), 0);
        }
        Paged<ItDeclarationSubmission> rows =
                Paged.of(submissions.inScope(year, context.dataScope(), status, page.pageable()));
        Map<Long, SubmissionView> byId =
                views(rows.items()).stream()
                        .collect(Collectors.toMap(SubmissionView::id, Function.identity()));
        return rows.map(s -> byId.get(s.getSubmissionId()));
    }

    /** I-07: one round, in the user's offices (404 outside them). */
    @Transactional(readOnly = true)
    public SubmissionView get(AccessContext context, Long id) {
        policy.require(context, SCREEN, "VIEW");
        ItDeclarationSubmission s = found(id);
        requireInScope(context, s, "VIEW");
        return view(s);
    }

    /**
     * I-07: the Payroll Maker verifies every open line of a submitted round against its proofs and
     * sends the round to the Payroll Checker (SRS-7-24, BR-TAX-014).
     */
    @Transactional
    public SubmissionView verify(
            AccessContext context, Long id, List<LineVerification> verifications) {
        policy.require(context, SCREEN, "SUBMIT");
        ItDeclarationSubmission s = locked(id);
        EmployeeDirectory.Member member = requireInScope(context, s, "SUBMIT");
        requireNotOwn(context, s);
        requireStatus(s, Status.SUBMITTED);
        List<ItDeclaration> open =
                lines.findBySubmissionIdOrderByDeclarationId(s.getSubmissionId()).stream()
                        .filter(l -> !l.isFrozen())
                        .toList();
        Map<Long, BigDecimal> amounts = new HashMap<>();
        for (LineVerification v :
                verifications == null ? List.<LineVerification>of() : verifications) {
            if (v == null
                    || v.lineId() == null
                    || amounts.putIfAbsent(v.lineId(), v.verified()) != null) {
                throw new FciException(TaxErrorCode.VERIFIED_AMOUNT);
            }
        }
        if (amounts.size() != open.size()
                || !open.stream().allMatch(l -> amounts.containsKey(l.getDeclarationId()))) {
            throw new FciException(TaxErrorCode.VERIFIED_AMOUNT);
        }
        Instant now = clock.instant();
        for (ItDeclaration l : open) {
            BigDecimal amount = amounts.get(l.getDeclarationId());
            DeclarationRules.requireVerified(amount, l.getAllowedAmount());
            l.verified(amount, context.loginId(), now);
            lines.save(l);
        }
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                VERIFY_ROUTE,
                                SCREEN,
                                "SUBMIT",
                                SUBMISSION_ENTITY,
                                String.valueOf(s.getSubmissionId()),
                                ActionType.UPDATE,
                                member.locationId(),
                                null,
                                today(),
                                "Declarations " + s.getFinYear() + " round " + s.getSubmissionNo()),
                        null);
        s.verified(context.loginId(), now, item.id());
        submissions.save(s);
        events.publishEvent(
                new SelfServiceEvents.StatusChanged(
                        SUBMISSION_ENTITY,
                        s.getSubmissionId(),
                        SelfServiceEvents.Status.ROUTED,
                        item.id(),
                        context.loginId()));
        regimes.findById(s.getRegimeDetailId())
                .ifPresent(
                        r -> {
                            if (!r.isLocked()) {
                                r.verified(context.loginId(), now);
                                regimes.save(r);
                            }
                        });
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                SUBMISSION_ENTITY,
                                String.valueOf(s.getSubmissionId()),
                                "submission_status",
                                Status.SUBMITTED.name(),
                                Status.VERIFIED.name(),
                                "verified against the proofs; " + open.size() + " lines")
                        .approval(item.id())
                        .module(SCREEN));
        return view(s);
    }

    /** I-07: the Payroll Maker returns a submitted round to the member with the reason. */
    @Transactional
    public SubmissionView returnToMember(AccessContext context, Long id, String reason) {
        policy.require(context, SCREEN, "SUBMIT");
        ItDeclarationSubmission s = locked(id);
        requireInScope(context, s, "SUBMIT");
        requireNotOwn(context, s);
        requireStatus(s, Status.SUBMITTED);
        returned(s, reason, context.loginId(), null);
        return view(s);
    }

    /**
     * I-07: the Payroll Maker proposes to reset a frozen regime (FRS V0.5 §14.3); the Payroll
     * Checker decides (route I07_RESET).
     */
    @Transactional
    public Long proposeReset(
            AccessContext context, Long employeeId, String finYear, String reason) {
        policy.require(context, SCREEN, "EDIT");
        if (reason == null || reason.isBlank()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        EmployeeDirectory.Member member =
                employees
                        .member(employeeId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireRecordAt(context, SCREEN, "EDIT", member.locationId());
        if (employeeId.equals(context.employeeId())) {
            throw new FciException(TaxErrorCode.OWN_DECLARATION);
        }
        ItRegimeDetail regime =
                regimes.lockFor(employeeId, finYear)
                        .filter(ItRegimeDetail::isLocked)
                        .orElseThrow(
                                () ->
                                        new FciException(
                                                TaxErrorCode.DECLARATION_STATE,
                                                new Object[] {"not frozen"},
                                                List.of(),
                                                null));
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                RESET_ROUTE,
                                SCREEN,
                                "EDIT",
                                REGIME_ENTITY,
                                String.valueOf(regime.getRegimeDetailId()),
                                ActionType.UPDATE,
                                member.locationId(),
                                json.writeValueAsString(
                                        new ResetProposal(
                                                regime.getRegimeDetailId(),
                                                finYear,
                                                reason.strip())),
                                today(),
                                reason.strip()),
                        null);
        return item.id();
    }

    // ---------------------------------------------------------------- decisions

    /**
     * The decision of the Payroll Checker. An approved round freezes the regime and the rent
     * declaration and replaces the earlier approved round; a rejected one goes back to the member
     * with the Checker's reason. An approved reset opens the regime again.
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!SCREEN.equals(decision.moduleCode())) {
            return;
        }
        if (decision.concerns(SUBMISSION_ENTITY)) {
            ItDeclarationSubmission s = locked(Long.valueOf(decision.entityKey()));
            if (decision.approved()) {
                approve(s, decision);
            } else {
                returned(
                        s,
                        decision.reason() == null
                                ? "Rejected by the Payroll Checker"
                                : decision.reason(),
                        decision.checkerLoginId(),
                        decision.approvalId());
            }
        } else if (decision.concerns(REGIME_ENTITY) && decision.approved()) {
            ResetProposal p = json.readValue(decision.payload(), ResetProposal.class);
            regimes.findById(p.regimeDetailId())
                    .ifPresent(
                            r -> {
                                r.reset(decision.approvalId(), p.reason());
                                regimes.save(r);
                                audit.record(
                                        decision.checkerLoginId(),
                                        AuditService.Entry.change(
                                                        REGIME_ENTITY,
                                                        String.valueOf(r.getRegimeDetailId()),
                                                        "locked_flag",
                                                        "Y",
                                                        "N",
                                                        p.reason())
                                                .approval(decision.approvalId())
                                                .module(SCREEN));
                            });
        }
    }

    private void approve(ItDeclarationSubmission s, ApprovalDecided decision) {
        // An earlier approved round of the year is replaced by this one (kept as evidence).
        submissions
                .findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(
                        s.getEmployeeId(), s.getFinYear())
                .stream()
                .filter(o -> o.status() == Status.APPROVED)
                .forEach(
                        o -> {
                            o.supersede();
                            submissions.save(o);
                        });
        s.approved();
        submissions.save(s);
        lines.findBySubmissionIdOrderByDeclarationId(s.getSubmissionId())
                .forEach(
                        l -> {
                            l.approved(decision.approvalId());
                            lines.save(l);
                        });
        regimes.findById(s.getRegimeDetailId())
                .ifPresent(
                        r -> {
                            r.approvedAndFrozen(decision.approvalId());
                            regimes.save(r);
                        });
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                SUBMISSION_ENTITY,
                                String.valueOf(s.getSubmissionId()),
                                "submission_status",
                                Status.VERIFIED.name(),
                                Status.APPROVED.name(),
                                decision.reason() == null
                                        ? "Approved; regime frozen"
                                        : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    private void returned(ItDeclarationSubmission s, String reason, String actor, Long approval) {
        if (reason == null || reason.isBlank()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        String before = s.status().name();
        s.returned(reason.strip());
        submissions.save(s);
        // A returned round is not taken further: the member's next round is a new submission.
        events.publishEvent(
                new SelfServiceEvents.StatusChanged(
                        SUBMISSION_ENTITY,
                        s.getSubmissionId(),
                        SelfServiceEvents.Status.REJECTED,
                        actor));
        lines.findBySubmissionIdOrderByDeclarationId(s.getSubmissionId()).stream()
                .filter(l -> !l.isFrozen())
                .forEach(
                        l -> {
                            l.rejected();
                            lines.save(l);
                        });
        regimes.findById(s.getRegimeDetailId())
                .filter(r -> !r.isLocked())
                .ifPresent(
                        r -> {
                            r.returned();
                            regimes.save(r);
                        });
        audit.record(
                actor,
                AuditService.Entry.change(
                                SUBMISSION_ENTITY,
                                String.valueOf(s.getSubmissionId()),
                                "submission_status",
                                before,
                                Status.RETURNED.name(),
                                reason.strip())
                        .approval(approval)
                        .module(SCREEN));
    }

    // ---------------------------------------------------------------- proof deadline (SRS-7-14)

    /**
     * SRS-7-14: after the proof deadline of its year, the approved lines of a round without a proof
     * lose their provisional exemption. Run by the TDS computation of the cycle (08e), which then
     * recomputes the remaining cycles; nothing is reversed while no deadline is configured.
     *
     * @return the number of lines reversed
     */
    @Transactional
    public int reverseUnprovedLines(String finYear, String actor) {
        LocalDate today = today();
        int reversed = 0;
        for (ItDeclarationSubmission s :
                submissions.findByFinYearAndSubmissionStatus(finYear, Status.APPROVED.name())) {
            Optional<LocalDate> deadline =
                    proofsUntil(LocalDate.ofInstant(s.getSubmittedAt(), INDIA));
            int count = 0;
            for (ItDeclaration l :
                    lines.findBySubmissionIdOrderByDeclarationId(s.getSubmissionId())) {
                if ("APPROVED".equals(l.getProofStatus())
                        && DeclarationRules.reversible(l.hasProof(), deadline, today)) {
                    l.reversed();
                    lines.save(l);
                    count++;
                }
            }
            if (count > 0) {
                s.proofDeadlineReversed();
                submissions.save(s);
                audit.record(
                        actor,
                        AuditService.Entry.change(
                                        SUBMISSION_ENTITY,
                                        String.valueOf(s.getSubmissionId()),
                                        "proof_deadline_reversed",
                                        "N",
                                        "Y",
                                        count
                                                + " lines without proof after "
                                                + deadline.orElseThrow())
                                .module(SCREEN));
                reversed += count;
            }
        }
        return reversed;
    }

    // ---------------------------------------------------------------- proofs

    /** A proof of a round: to the member, or to I-07 in scope (the read is logged). */
    @Transactional
    public DocumentStore.Content proof(
            AccessContext context, String sourceIp, Long submissionId, Long proofId) {
        ItDeclarationSubmission s = found(submissionId);
        if (s.getEmployeeId().equals(context.employeeId())) {
            policy.require(context, MEMBER_SCREEN, "SELF");
        } else {
            // Another member's round does not exist for a caller without I-07 (security review
            // 2026-11-04, W-5: a 403 here told an eSeva member that the id was real).
            if (!policy.can(context, SCREEN, "VIEW")) {
                policy.recordForeignAttempt(
                        context, MEMBER_SCREEN, "SELF", "it_declaration_submission", submissionId);
                throw new FciException(CommonErrorCode.NOT_FOUND);
            }
            requireInScope(context, s, "VIEW");
        }
        boolean listed =
                lines.findBySubmissionIdOrderByDeclarationId(submissionId).stream()
                        .anyMatch(l -> proofId.equals(l.getAttachmentId()));
        if (!listed) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        // A frozen rent line keeps the proof of the round it was approved in.
        for (ItDeclarationSubmission owner :
                submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(
                        s.getEmployeeId(), s.getFinYear())) {
            String key = String.valueOf(owner.getSubmissionId());
            if (store.active(SUBMISSION_ENTITY, key).stream()
                    .anyMatch(a -> proofId.equals(a.id()))) {
                return store.read(context.loginId(), sourceIp, SUBMISSION_ENTITY, key, proofId);
            }
        }
        throw new FciException(CommonErrorCode.NOT_FOUND);
    }

    // ---------------------------------------------------------------- helpers

    private Long attach(AccessContext context, String key, Proof p) {
        return store.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                SUBMISSION_ENTITY,
                                key,
                                p.documentTypeCode(),
                                p.referenceNumber(),
                                p.documentDate(),
                                p.issuingAuthority(),
                                p.fileName(),
                                p.content(),
                                true))
                .id();
    }

    /** The window is open while an approved version of the M-18 rule is in force and holds. */
    private boolean windowOpen(LocalDate day) {
        return inForce(WINDOW_KEY, day).isPresent();
    }

    /** The last day proofs are accepted: the end of the proof window in force on {@code day}. */
    private Optional<LocalDate> proofsUntil(LocalDate day) {
        return inForce(PROOF_KEY, day).map(RuleEngine.Resolved::effectiveTo);
    }

    private Optional<RuleEngine.Resolved> inForce(String key, LocalDate day) {
        try {
            Optional<RuleEngine.Resolved> version =
                    rules.resolve(key, day, RuleEngine.Context.unscoped());
            boolean holds =
                    version.isPresent()
                            && rules.condition(key, day, RuleEngine.Context.unscoped(), Map.of())
                                    .orElse(false);
            return holds ? version : Optional.empty();
        } catch (FciException notConfigured) {
            // No approved version in force: not configured, so closed (R-RULE: nothing assumed).
            return Optional.empty();
        }
    }

    private List<ItDeclaration> frozenRent(List<ItDeclarationSubmission> earlier) {
        return earlier.stream()
                .filter(s -> s.status() == Status.APPROVED)
                .findFirst()
                .map(s -> lines.findBySubmissionIdOrderByDeclarationId(s.getSubmissionId()))
                .orElse(List.of())
                .stream()
                .filter(l -> l.kind() == Kind.HRA_RENT && l.isFrozen())
                .toList();
    }

    private Long self(AccessContext context) {
        if (context.employeeId() == null) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return context.employeeId();
    }

    private ItDeclarationSubmission found(Long id) {
        return submissions
                .findById(id)
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private ItDeclarationSubmission locked(Long id) {
        return submissions
                .lockById(id)
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private EmployeeDirectory.Member requireInScope(
            AccessContext context, ItDeclarationSubmission s, String action) {
        EmployeeDirectory.Member member =
                employees
                        .member(s.getEmployeeId())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireRecordAt(context, SCREEN, action, member.locationId());
        return member;
    }

    private static void requireNotOwn(AccessContext context, ItDeclarationSubmission s) {
        if (s.getEmployeeId().equals(context.employeeId())) {
            throw new FciException(TaxErrorCode.OWN_DECLARATION);
        }
    }

    private static void requireStatus(ItDeclarationSubmission s, Status expected) {
        if (s.status() != expected) {
            throw new FciException(
                    TaxErrorCode.DECLARATION_STATE,
                    new Object[] {s.status().name()},
                    List.of(),
                    null);
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(INDIA));
    }

    private SubmissionView view(ItDeclarationSubmission s) {
        return views(List.of(s)).get(0);
    }

    /**
     * The views of a page of rounds with members, regimes and lines: four reads, never one per row.
     */
    private List<SubmissionView> views(List<ItDeclarationSubmission> rounds) {
        if (rounds.isEmpty()) {
            return List.of();
        }
        Map<Long, EmployeeDirectory.Member> members =
                employees.members(
                        rounds.stream().map(ItDeclarationSubmission::getEmployeeId).toList());
        Map<Long, List<ItDeclaration>> byRound =
                lines
                        .findBySubmissionIdInOrderByDeclarationId(
                                rounds.stream()
                                        .map(ItDeclarationSubmission::getSubmissionId)
                                        .toList())
                        .stream()
                        .collect(Collectors.groupingBy(ItDeclaration::getSubmissionId));
        Map<Long, ItRegimeDetail> regimeById =
                regimes
                        .findByRegimeDetailIdIn(
                                rounds.stream()
                                        .map(ItDeclarationSubmission::getRegimeDetailId)
                                        .distinct()
                                        .toList())
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        ItRegimeDetail::getRegimeDetailId, Function.identity()));
        List<SubmissionView> out = new ArrayList<>();
        for (ItDeclarationSubmission s : rounds) {
            Optional<ItRegimeDetail> regime =
                    Optional.ofNullable(regimeById.get(s.getRegimeDetailId()));
            EmployeeDirectory.Member m = members.get(s.getEmployeeId());
            out.add(
                    new SubmissionView(
                            s.getSubmissionId(),
                            s.getEmployeeId(),
                            m == null ? null : m.eis(),
                            m == null ? null : m.name(),
                            s.getFinYear(),
                            s.getSubmissionNo(),
                            s.status().name(),
                            s.getSubmittedAt(),
                            s.getVerifiedBy(),
                            s.getReturnReason(),
                            s.isProofDeadlineReversed(),
                            regime.map(ItRegimeDetail::getRegime).orElse(null),
                            regime.map(ItRegimeDetail::isLocked).orElse(false),
                            byRound.getOrDefault(s.getSubmissionId(), List.of()).stream()
                                    .map(DeclarationService::line)
                                    .toList()));
        }
        return out;
    }

    private static LineView line(ItDeclaration l) {
        return new LineView(
                l.getDeclarationId(),
                l.kind(),
                l.getSectionCode(),
                l.getDeclaredAmount(),
                l.getAllowedAmount(),
                DeclarationRules.capped(l.getDeclaredAmount(), l.getAllowedAmount()),
                l.getProofStatus(),
                l.getVerifiedAmount(),
                l.isFrozen(),
                l.getAttachmentId());
    }
}
