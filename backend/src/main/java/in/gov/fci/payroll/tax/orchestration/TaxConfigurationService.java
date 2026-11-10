package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.FieldChanges;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.tax.business.TaxConfigurationRules;
import in.gov.fci.payroll.tax.business.model.ItSection;
import in.gov.fci.payroll.tax.business.model.PtState;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The income-tax section master and the Professional Tax state master, two cards of M-18 (Statutory
 * Rate Register) maintained at Headquarters (SRS-7-2, SRS-7-4, SRS-7-20, SRS-7-26; BR-TAX-002,
 * BR-TAX-006; plan 08 D-2): the Rule Administrator proposes, the Rule Approver decides in the
 * Approvals Inbox (routes M18_SECTION and M18_PT_STATE, never the proposer). Nothing is written
 * until approval: the proposal lives in the approval item. Nothing is seeded (OQ-4).
 *
 * <p>A section changes by a new effective-dated version; a state, one row per state, is updated in
 * place with its old and new values audited field by field.
 */
@Service
@Loggable
public class TaxConfigurationService {

    public static final String SCREEN = "M-18";
    public static final String SECTION_ENTITY = "it_section_master";
    public static final String STATE_ENTITY = "pt_state_master";
    public static final String SECTION_ROUTE = "M18_SECTION";
    public static final String STATE_ROUTE = "M18_PT_STATE";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** One version of a section. */
    public record SectionView(
            Long id,
            String code,
            String description,
            BigDecimal limit,
            String regime,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            boolean inForce) {}

    /** What the Rule Approver decides on: a new version of a section. */
    public record SectionProposal(
            String code,
            String description,
            BigDecimal limit,
            String regime,
            LocalDate effectiveFrom,
            String proposedBy,
            Instant proposedAt) {}

    /** One PT state. {@code slabRuleKey} is SYS: set from the applicability, never keyed. */
    public record StateView(
            Long id,
            String code,
            String name,
            boolean ptApplicable,
            String registrationNumber,
            String slabRuleKey,
            String returnPeriodicity,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            boolean inForce) {}

    /** What the Rule Approver decides on: a new state or new values of a state. */
    public record StateProposal(
            String code,
            String name,
            boolean ptApplicable,
            String registrationNumber,
            String returnPeriodicity,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String proposedBy,
            Instant proposedAt) {}

    /** A proposal awaiting the Rule Approver. */
    public record Pending<P>(Long approvalId, P proposal) {}

    private final TaxRepositories.Sections sections;
    private final TaxRepositories.PtStates states;
    private final PtStates cached;
    private final MakerChecker approvals;
    private final ApprovalStatuses statuses;
    private final Authorization policy;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public TaxConfigurationService(
            TaxRepositories.Sections sections,
            TaxRepositories.PtStates states,
            PtStates cached,
            MakerChecker approvals,
            ApprovalStatuses statuses,
            Authorization policy,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.sections = sections;
        this.states = states;
        this.cached = cached;
        this.approvals = approvals;
        this.statuses = statuses;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- sections

    /** M-18 section card: one page of every version, by code and newest first. */
    @Transactional(readOnly = true)
    public Paged<SectionView> sections(AccessContext context, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        LocalDate today = today();
        return Paged.of(sections.findAllByOrderBySectionCodeAscEffectiveFromDesc(page.pageable()))
                .map(s -> view(s, today));
    }

    /** The section versions awaiting the Rule Approver, newest first. */
    @Transactional(readOnly = true)
    public List<Pending<SectionProposal>> pendingSections(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        return statuses.pending(SECTION_ENTITY).stream()
                .map(
                        p ->
                                new Pending<>(
                                        p.approvalId(),
                                        json.readValue(p.payload(), SectionProposal.class)))
                .toList();
    }

    /**
     * M-18: a new version of a section (new or existing code), validated and sent to the Rule
     * Approver. Headquarters only; one open proposal per section.
     */
    @Transactional
    public Pending<SectionProposal> proposeSection(
            AccessContext context, SectionProposal request, String reason, OtpProof otp) {
        policy.require(context, SCREEN, "CREATE");
        requireHeadquarters(context);
        if (request.description() == null || request.description().isBlank()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        TaxConfigurationRules.requireSectionCode(request.code());
        TaxConfigurationRules.requireLimit(request.limit());
        TaxConfigurationRules.requireRegime(request.regime());
        TaxConfigurationRules.requireAfter(
                request.code(), request.effectiveFrom(), latestFrom(request.code()));
        requireNoPending(SECTION_ENTITY, request.code());
        SectionProposal p =
                new SectionProposal(
                        request.code(),
                        request.description().strip(),
                        request.limit(),
                        request.regime(),
                        request.effectiveFrom(),
                        context.loginId(),
                        clock.instant());
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SECTION_ROUTE,
                                SCREEN,
                                "CREATE",
                                SECTION_ENTITY,
                                p.code(),
                                ActionType.CREATE,
                                context.locationId(),
                                json.writeValueAsString(p),
                                p.effectiveFrom(),
                                reason),
                        otp);
        return new Pending<>(item.id(), p);
    }

    // ---------------------------------------------------------------- PT states

    /** M-18 PT state card: one page of the states by code. */
    @Transactional(readOnly = true)
    public Paged<StateView> states(AccessContext context, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        LocalDate today = today();
        return Paged.of(states.findAllByOrderByStateCodeAsc(page.pageable()))
                .map(s -> view(s, today));
    }

    /** The PT state changes awaiting the Rule Approver, newest first. */
    @Transactional(readOnly = true)
    public List<Pending<StateProposal>> pendingStates(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        return statuses.pending(STATE_ENTITY).stream()
                .map(
                        p ->
                                new Pending<>(
                                        p.approvalId(),
                                        json.readValue(p.payload(), StateProposal.class)))
                .toList();
    }

    /**
     * M-18: a new PT state ({@code edit} false, CREATE) or new values of an existing one ({@code
     * edit} true, EDIT), validated and sent to the Rule Approver. Headquarters only; one open
     * proposal per state. The slab rule key follows from the applicability when approved
     * (BR-TAX-006).
     */
    @Transactional
    public Pending<StateProposal> proposeState(
            AccessContext context,
            boolean edit,
            StateProposal request,
            String reason,
            OtpProof otp) {
        String action = edit ? "EDIT" : "CREATE";
        policy.require(context, SCREEN, action);
        requireHeadquarters(context);
        TaxConfigurationRules.requireState(
                request.code(), request.name(), request.effectiveFrom(), request.effectiveTo());
        boolean exists = states.findByStateCode(request.code()).isPresent();
        if (edit && !exists) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        if (!edit && exists) {
            throw new FciException(
                    ApiErrorCode.REFERENCE_CODE_TAKEN,
                    new Object[] {"state_code", request.code()},
                    List.of(),
                    null);
        }
        requireNoPending(STATE_ENTITY, request.code());
        StateProposal p =
                new StateProposal(
                        request.code(),
                        request.name().strip(),
                        request.ptApplicable(),
                        blankToNull(request.registrationNumber()),
                        blankToNull(request.returnPeriodicity()),
                        request.effectiveFrom(),
                        request.effectiveTo(),
                        context.loginId(),
                        clock.instant());
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                STATE_ROUTE,
                                SCREEN,
                                action,
                                STATE_ENTITY,
                                p.code(),
                                edit ? ActionType.UPDATE : ActionType.CREATE,
                                context.locationId(),
                                json.writeValueAsString(p),
                                p.effectiveFrom(),
                                reason),
                        otp);
        return new Pending<>(item.id(), p);
    }

    // ---------------------------------------------------------------- decisions

    /**
     * The decision of the Rule Approver. A section version is checked again (another version may
     * have been approved meanwhile) and the version in force ends the day before; a state is
     * created or updated. Audited with the approval in the same transaction (R-AUD).
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!SCREEN.equals(decision.moduleCode()) || !decision.approved()) {
            return;
        }
        String reason = decision.reason() == null ? "M-18 approved" : decision.reason();
        if (decision.concerns(SECTION_ENTITY)) {
            applySection(
                    decision, json.readValue(decision.payload(), SectionProposal.class), reason);
        } else if (decision.concerns(STATE_ENTITY)) {
            applyState(decision, json.readValue(decision.payload(), StateProposal.class), reason);
        }
    }

    private void applySection(ApprovalDecided decision, SectionProposal p, String reason) {
        List<ItSection> versions = sections.findBySectionCodeOrderByEffectiveFromDesc(p.code());
        TaxConfigurationRules.requireAfter(
                p.code(),
                p.effectiveFrom(),
                versions.stream().findFirst().map(ItSection::getEffectiveFrom));
        versions.stream()
                .filter(
                        v ->
                                v.getEffectiveTo() == null
                                        || !v.getEffectiveTo().isBefore(p.effectiveFrom()))
                .forEach(
                        v -> {
                            v.endBefore(p.effectiveFrom());
                            sections.save(v);
                        });
        sections.save(
                ItSection.approved(
                        p.code(), p.description(), p.limit(), p.regime(), p.effectiveFrom()));
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                SECTION_ENTITY,
                                p.code(),
                                "version",
                                versions.isEmpty()
                                        ? null
                                        : versions.get(0).getEffectiveFrom().toString(),
                                p.effectiveFrom()
                                        + " limit "
                                        + (p.limit() == null ? "none" : p.limit().toPlainString())
                                        + "; regime "
                                        + p.regime(),
                                reason)
                        .approval(decision.approvalId())
                        .module(SCREEN)
                        .effective(p.effectiveFrom()));
    }

    private void applyState(ApprovalDecided decision, StateProposal p, String reason) {
        Optional<PtState> existing = states.findByStateCode(p.code());
        PtState state = existing.orElseGet(() -> PtState.of(p.code()));
        Map<String, String> before = existing.map(PtState::image).orElse(null);
        state.apply(
                p.name(),
                p.ptApplicable(),
                p.registrationNumber(),
                TaxConfigurationRules.slabKeyFor(p.ptApplicable()),
                p.returnPeriodicity(),
                p.effectiveFrom(),
                p.effectiveTo());
        states.save(state);
        // The PT computation's copy of the master reloads on every instance (HLD §3.12).
        cached.changed(decision.checkerLoginId());
        audit.recordAll(
                decision.checkerLoginId(),
                FieldChanges.between(STATE_ENTITY, p.code(), before, state.image(), reason).stream()
                        .map(
                                e ->
                                        e.approval(decision.approvalId())
                                                .module(SCREEN)
                                                .effective(p.effectiveFrom()))
                        .toList());
    }

    // ---------------------------------------------------------------- helpers

    private void requireHeadquarters(AccessContext context) {
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(ApiErrorCode.HQ_ONLY);
        }
    }

    /** One open proposal per section or state: the checker decides on one change at a time. */
    private void requireNoPending(String entity, String code) {
        statuses.pending(entity).stream()
                .filter(p -> code.equals(p.entityKey()))
                .findFirst()
                .ifPresent(
                        p -> {
                            throw new FciException(
                                    ApiErrorCode.REFERENCE_CHANGE_PENDING,
                                    new Object[] {code, p.approvalId()},
                                    List.of(),
                                    null);
                        });
    }

    private Optional<LocalDate> latestFrom(String code) {
        return sections.findBySectionCodeOrderByEffectiveFromDesc(code).stream()
                .findFirst()
                .map(ItSection::getEffectiveFrom);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(INDIA));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static SectionView view(ItSection s, LocalDate today) {
        return new SectionView(
                s.getSectionId(),
                s.getSectionCode(),
                s.getSectionDescription(),
                s.getMaxDeductionAmount(),
                s.getRegimeApplicability(),
                s.getEffectiveFrom(),
                s.getEffectiveTo(),
                s.inForceOn(today));
    }

    private static StateView view(PtState s, LocalDate today) {
        return new StateView(
                s.getPtStateId(),
                s.getStateCode(),
                s.getStateName(),
                s.isPtApplicable(),
                s.getRegistrationNumber(),
                s.getSlabRuleKey(),
                s.getReturnPeriodicity(),
                s.getEffectiveFrom(),
                s.getEffectiveTo(),
                s.inForceOn(today));
    }
}
