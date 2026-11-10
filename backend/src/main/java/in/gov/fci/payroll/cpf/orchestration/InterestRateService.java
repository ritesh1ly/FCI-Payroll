package in.gov.fci.payroll.cpf.orchestration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.ChangeType;
import in.gov.fci.payroll.audit.api.FieldChanges;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.InterestRateRules;
import in.gov.fci.payroll.cpf.business.model.PfInterestRate;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
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
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * M-19 PF Interest Rates (SRS-6-11, BR-CPF-033; SRS M-19 field table; DDL {@code
 * pf_interest_rate}): the FCI-declared rate for active and deputed members and the EPFO-declared
 * rate for inactive members, per financial year and fund, held under maker-checker at Headquarters.
 * No EPFO interface: the EPFO rate is entered by hand.
 *
 * <p><b>Proposals.</b> The Rule Administrator at HQ records a rate (M19_CREATE) or declares one
 * recorded undeclared (M19_EDIT); a different person holding Rule Approver decides it in the
 * approval inbox. A proposal lives only in its approval item until approved; only then is the row
 * written (as M-08, plan 06 D-7), so a returned proposal never holds a date. One change at a time
 * per series (year, fund, source): the approval engine refuses a second pending item on the same
 * key. Every check is made again when the decision is applied: the series may have moved.
 *
 * <p><b>Versions.</b> A new rate of a series starts after the latest approved one and replaces it
 * from that date (the earlier one ends the day before and is shown as Superseded). An undeclared
 * rate is never used by an interest run (C-12, SRS-6-11: no fallback to last year's).
 */
@Service
@Loggable
public class InterestRateService {

    public static final String SCREEN = "M-19";

    /** The approval entity of a rate change; its key is the series (year|fund|source). */
    public static final String ENTITY = "pf_interest_rate";

    static final int NOTE_MAX = 500;
    static final int REASON_MAX = 500;

    /** What a proposal does. */
    public enum Kind {
        RECORD,
        DECLARE
    }

    /** One approved rate; {@code status} is ACTIVE, or SUPERSEDED by a later rate of its series. */
    public record RateView(
            Long id,
            String finYear,
            String fundType,
            String rateSource,
            String memberPopulation,
            BigDecimal ratePercent,
            LocalDate appliesFrom,
            LocalDate appliesTo,
            String applicabilityNote,
            BigDecimal bonusInterestPct,
            boolean declared,
            String status,
            Long approvalId) {}

    /** A change awaiting the Rule Approver, as its approval item carries it. */
    public record PendingChange(
            Long approvalId,
            Kind kind,
            Long rateId,
            String finYear,
            String fundType,
            String rateSource,
            BigDecimal ratePercent,
            LocalDate appliesFrom,
            LocalDate appliesTo,
            BigDecimal bonusInterestPct,
            boolean declared,
            String proposedBy,
            Instant proposedAt) {}

    public record Rates(List<RateView> rates, List<PendingChange> pending) {}

    /** A rate to record (M-19 Edit/Entry). */
    public record RateRequest(
            String finYear,
            String fundType,
            String rateSource,
            BigDecimal ratePercent,
            LocalDate appliesFrom,
            LocalDate appliesTo,
            String applicabilityNote,
            BigDecimal bonusInterestPct,
            boolean declared,
            String reason) {}

    /**
     * A proposal as its approval item carries it (the approver sees exactly what will be written);
     * built only from validated fields, never from client JSON. Read back tolerantly.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RateProposal(
            Kind kind,
            Long rateId,
            String finYear,
            String fundType,
            String rateSource,
            BigDecimal ratePercent,
            LocalDate appliesFrom,
            LocalDate appliesTo,
            String applicabilityNote,
            BigDecimal bonusInterestPct,
            boolean declared,
            String proposedBy,
            Instant proposedAt) {}

    private final CpfRepositories.InterestRates rates;
    private final ApprovalStatuses statuses;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public InterestRateService(
            CpfRepositories.InterestRates rates,
            ApprovalStatuses statuses,
            MakerChecker approvals,
            Authorization policy,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.rates = rates;
        this.statuses = statuses;
        this.approvals = approvals;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    /** M-19 View: every approved rate with its status, and the changes awaiting a decision. */
    @Transactional(readOnly = true)
    public Rates list(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        List<PfInterestRate> all =
                rates
                        .findAllByOrderByFinYearDescFundTypeAscRateSourceAscAppliesFromDescInterestRateIdDesc();
        List<RateView> views = new ArrayList<>();
        for (PfInterestRate r : all) {
            views.add(view(r, superseded(r, all)));
        }
        List<PendingChange> pending = new ArrayList<>();
        for (ApprovalStatuses.Pending p : statuses.pending(ENTITY)) {
            RateProposal c = json.readValue(p.payload(), RateProposal.class);
            pending.add(
                    new PendingChange(
                            p.approvalId(),
                            c.kind(),
                            c.rateId(),
                            c.finYear(),
                            c.fundType(),
                            c.rateSource(),
                            c.ratePercent(),
                            c.appliesFrom(),
                            c.appliesTo(),
                            c.bonusInterestPct(),
                            c.declared(),
                            c.proposedBy(),
                            c.proposedAt()));
        }
        return new Rates(views, pending);
    }

    /** A later approved rate of the same series replaces this one. */
    static boolean superseded(PfInterestRate r, List<PfInterestRate> all) {
        return all.stream()
                .anyMatch(
                        o ->
                                o.getFinYear().equals(r.getFinYear())
                                        && o.getFundType().equals(r.getFundType())
                                        && o.getRateSource().equals(r.getRateSource())
                                        && o.getAppliesFrom().isAfter(r.getAppliesFrom()));
    }

    // ---------------------------------------------------------------- proposing

    /**
     * M-19 New rate: validated (financial year, rate, period within the year, bonus over the Trust
     * rate only, after the approved rate of the series), then submitted to the Rule Approver.
     */
    @Transactional
    public PendingChange recordRate(AccessContext context, RateRequest request, OtpProof otp) {
        policy.require(context, SCREEN, "CREATE");
        requireHeadquarters(context);
        if (request == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        RateProposal proposal = validated(request, context.loginId());
        requireAfterApproved(proposal);
        return submit(context, "CREATE", ActionType.CREATE, proposal, request.reason(), otp);
    }

    /**
     * M-19 Declare: FCI or EPFO declares a rate recorded undeclared, at the rate declared; the
     * declared rate is then the one an interest run applies (SRS-6-11).
     */
    @Transactional
    public PendingChange declare(
            AccessContext context,
            Long rateId,
            BigDecimal declaredRate,
            String reason,
            OtpProof otp) {
        policy.require(context, SCREEN, "EDIT");
        requireHeadquarters(context);
        PfInterestRate rate =
                rates.findById(rateId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (rate.isIsDeclared()) {
            throw new FciException(CpfErrorCode.RATE_DECLARED);
        }
        InterestRateRules.requireRate(declaredRate);
        RateProposal proposal =
                new RateProposal(
                        Kind.DECLARE,
                        rate.getInterestRateId(),
                        rate.getFinYear(),
                        rate.getFundType(),
                        rate.getRateSource(),
                        declaredRate,
                        rate.getAppliesFrom(),
                        rate.getAppliesTo(),
                        rate.getApplicabilityNote(),
                        rate.getBonusInterestPct(),
                        true,
                        context.loginId(),
                        clock.instant());
        return submit(context, "EDIT", ActionType.UPDATE, proposal, reason, otp);
    }

    private PendingChange submit(
            AccessContext context,
            String action,
            ActionType type,
            RateProposal proposal,
            String reason,
            OtpProof otp) {
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                action,
                                ENTITY,
                                InterestRateRules.seriesKey(
                                        proposal.finYear(),
                                        proposal.fundType(),
                                        PfInterestRate.Source.valueOf(proposal.rateSource())),
                                type,
                                context.locationId(),
                                json.writeValueAsString(proposal),
                                proposal.appliesFrom(),
                                reason),
                        otp);
        return new PendingChange(
                item.id(),
                proposal.kind(),
                proposal.rateId(),
                proposal.finYear(),
                proposal.fundType(),
                proposal.rateSource(),
                proposal.ratePercent(),
                proposal.appliesFrom(),
                proposal.appliesTo(),
                proposal.bonusInterestPct(),
                proposal.declared(),
                proposal.proposedBy(),
                proposal.proposedAt());
    }

    /** The rules of a new rate (InterestRateRules), the text cut to its column. */
    static RateProposal validated(RateRequest r, String proposedBy, Instant at) {
        if (r.fundType() == null || !InterestRateRules.FUNDS.contains(r.fundType())) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        PfInterestRate.Source source = source(r.rateSource());
        InterestRateRules.FinYear year = InterestRateRules.finYear(r.finYear());
        InterestRateRules.requireRate(r.ratePercent());
        InterestRateRules.requirePeriod(year, r.appliesFrom(), r.appliesTo());
        InterestRateRules.requireBonus(source, r.bonusInterestPct());
        String note = r.applicabilityNote() == null ? null : r.applicabilityNote().strip();
        if (note != null && (note.isEmpty() || note.length() > NOTE_MAX)) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        return new RateProposal(
                Kind.RECORD,
                null,
                year.code(),
                r.fundType(),
                source.name(),
                r.ratePercent(),
                r.appliesFrom(),
                r.appliesTo(),
                note,
                r.bonusInterestPct(),
                r.declared(),
                proposedBy,
                at);
    }

    private RateProposal validated(RateRequest r, String proposedBy) {
        return validated(r, proposedBy, clock.instant());
    }

    private static PfInterestRate.Source source(String code) {
        for (PfInterestRate.Source s : PfInterestRate.Source.values()) {
            if (s.name().equals(code)) {
                return s;
            }
        }
        throw new FciException(CommonErrorCode.VALIDATION_FAILED);
    }

    private List<PfInterestRate> series(RateProposal p) {
        return rates.findByFinYearAndFundTypeAndRateSourceOrderByAppliesFromDesc(
                p.finYear(), p.fundType(), p.rateSource());
    }

    private void requireAfterApproved(RateProposal p) {
        InterestRateRules.requireAfter(
                p.appliesFrom(),
                series(p).stream().findFirst().map(PfInterestRate::getAppliesFrom));
    }

    /** M-19 is maintained at Headquarters only (SRS M-19; route M19_* at HQ). */
    private void requireHeadquarters(AccessContext context) {
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(ApiErrorCode.HQ_ONLY);
        }
    }

    // ---------------------------------------------------------------- the decision

    /**
     * The Rule Approver's decision, applied in its transaction: an approved new rate is written and
     * ends the rate it replaces the day before; an approved declaration declares the rate. Checked
     * again here (the series may have moved since submission) and audited field by field with old
     * and new values (SRS-20-2).
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY) || !SCREEN.equals(decision.moduleCode())) {
            return;
        }
        if (!decision.approved()) {
            return;
        }
        RateProposal p = json.readValue(decision.payload(), RateProposal.class);
        if (p.kind() == Kind.DECLARE) {
            applyDeclaration(decision, p);
        } else {
            applyNewRate(decision, p);
        }
    }

    private void applyNewRate(ApprovalDecided decision, RateProposal p) {
        requireAfterApproved(p);
        PfInterestRate.Source source = PfInterestRate.Source.valueOf(p.rateSource());
        List<PfInterestRate> previous = series(p);
        String why = why(decision);
        List<AuditService.Entry> entries = new ArrayList<>();
        Map<String, String> before = null;
        for (PfInterestRate open : previous) {
            if (open.getAppliesTo() == null || !open.getAppliesTo().isBefore(p.appliesFrom())) {
                if (before == null) {
                    before = image(open);
                }
                open.endBefore(p.appliesFrom());
                rates.save(open);
                entries.add(
                        AuditService.Entry.change(
                                        ENTITY,
                                        decision.entityKey(),
                                        "applies_to",
                                        null,
                                        open.getAppliesTo().toString(),
                                        why)
                                .type(ChangeType.END_DATE));
            }
        }
        rates.flush();
        PfInterestRate written =
                rates.save(
                        PfInterestRate.approved(
                                p.finYear(),
                                p.fundType(),
                                source,
                                InterestRateRules.population(source),
                                p.ratePercent(),
                                p.appliesFrom(),
                                p.appliesTo(),
                                p.applicabilityNote(),
                                p.bonusInterestPct(),
                                p.declared(),
                                decision.approvalId()));
        entries.addAll(
                FieldChanges.between(ENTITY, decision.entityKey(), before, image(written), why));
        writeAudit(decision, entries, p.appliesFrom());
    }

    private void applyDeclaration(ApprovalDecided decision, RateProposal p) {
        PfInterestRate rate =
                rates.lockById(p.rateId())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (rate.isIsDeclared()) {
            throw new FciException(CpfErrorCode.RATE_DECLARED);
        }
        Map<String, String> before = image(rate);
        rate.declare(p.ratePercent());
        rates.save(rate);
        writeAudit(
                decision,
                FieldChanges.between(
                        ENTITY, decision.entityKey(), before, image(rate), why(decision)),
                rate.getAppliesFrom());
    }

    private void writeAudit(
            ApprovalDecided decision, List<AuditService.Entry> entries, LocalDate from) {
        audit.recordAll(
                decision.checkerLoginId(),
                entries.stream()
                        .map(e -> e.approval(decision.approvalId()).module(SCREEN).effective(from))
                        .toList());
    }

    private static String why(ApprovalDecided decision) {
        return decision.reason() == null || decision.reason().isBlank()
                ? "Approved"
                : decision.reason();
    }

    /** What M-19 shows of a rate: its audited fields (also its History-button versions). */
    static Map<String, String> image(PfInterestRate r) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("rate_percent", plain(r.getRatePercent()));
        image.put("applies_from", String.valueOf(r.getAppliesFrom()));
        image.put("applies_to", r.getAppliesTo() == null ? null : r.getAppliesTo().toString());
        image.put("member_population", r.getMemberPopulation());
        image.put("applicability_note", r.getApplicabilityNote());
        image.put("bonus_interest_pct", plain(r.getBonusInterestPct()));
        image.put("is_declared", r.isIsDeclared() ? "Y" : "N");
        return image;
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static RateView view(PfInterestRate r, boolean superseded) {
        return new RateView(
                r.getInterestRateId(),
                r.getFinYear(),
                r.getFundType(),
                r.getRateSource(),
                r.getMemberPopulation(),
                r.getRatePercent(),
                r.getAppliesFrom(),
                r.getAppliesTo(),
                r.getApplicabilityNote(),
                r.getBonusInterestPct(),
                r.isIsDeclared(),
                superseded ? "SUPERSEDED" : "ACTIVE",
                r.getApprovalId());
    }

    /** One series' rates as History-button versions, oldest first (version 1 = first approved). */
    List<PfInterestRate> seriesOf(String key) {
        String[] parts = key == null ? new String[0] : key.split("\\|", -1);
        if (parts.length != 3) {
            return List.of();
        }
        List<PfInterestRate> found =
                new ArrayList<>(
                        rates.findByFinYearAndFundTypeAndRateSourceOrderByAppliesFromDesc(
                                parts[0], parts[1], parts[2]));
        Collections.reverse(found);
        return found;
    }
}
