package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.InterestRateRules;
import in.gov.fci.payroll.cpf.business.InterestRateRules.FinYear;
import in.gov.fci.payroll.cpf.business.InterestRunRules;
import in.gov.fci.payroll.cpf.business.InterestRunRules.Scope;
import in.gov.fci.payroll.cpf.business.model.CpfInterestFreeze;
import in.gov.fci.payroll.cpf.business.model.CpfInterestRun;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader.MemberStatus;
import in.gov.fci.payroll.cpf.business.model.PfInterestRate;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * C-12 CPF Interest Run - Calculate / Re-Run / Freeze (SRS-6-45, SRS-6-46, SRS-6-11, SRS-6-9; FRS
 * §6.13.2; DDL {@code cpf_interest_run}, {@code cpf_interest_freeze}). The Zonal or Headquarters
 * CPF Division (Manager (CPF)) runs and re-runs the interest of its zone's members for a financial
 * year, for all members or one, any number of times before the freeze; each run is audited
 * individually. The year-end freeze is requested by the Manager (CPF) and decided by the approving
 * role of the Approval Workflow Master (route C12_SUBMIT: AGM (CPF), no OTP); after it no run or
 * adjustment is accepted for that year.
 *
 * <p>A run checks everything the computation needs - the members it covers, the declared rate of
 * each member's state, the interest stop - and records every member it could not calculate with the
 * reason. The computation itself is not in any source (Q-072, decision D-4): every covered member
 * is recorded as not calculated and nothing is credited, until FCI states the method.
 */
@Service
@Loggable
public class InterestRunService {

    public static final String SCREEN = "C-12";
    public static final String RUN_ENTITY = "cpf_interest_run";
    public static final String FREEZE_ENTITY = "cpf_interest_freeze";

    /** The ledgers a year-end run covers (settled and zone-closed ones take interest elsewhere). */
    private static final Set<MemberStatus> RUN =
            EnumSet.of(MemberStatus.ACTIVE, MemberStatus.DEPUTATION, MemberStatus.EX_MEMBER);

    /** A run as the Interest Run Audit list shows it. */
    public record RunView(
            int run,
            String scope,
            String eis,
            String initiatedBy,
            Instant initiatedAt,
            long members,
            long calculated,
            long notCalculated,
            long stopped,
            String reason) {}

    /** OPEN, PENDING (a freeze awaits its decision) or FROZEN. */
    public enum FreezeStatus {
        OPEN,
        PENDING,
        FROZEN
    }

    /** The C-12 tiles and the run audit list of the zone's year. */
    public record Summary(
            String finYear,
            String zone,
            long members,
            long calculated,
            long notYetCalculated,
            int reRuns,
            FreezeStatus freezeStatus,
            String frozenBy,
            Instant frozenAt,
            List<RunView> runs) {}

    /** A member the year's interest is not yet calculated for, and why (SRS-6-45 report). */
    public record NotCalculated(
            String eis,
            String name,
            String memberStatus,
            String reason,
            LocalDate interestEndDate,
            boolean interestStopped) {}

    /** A run as the Manager (CPF) asks for it. */
    public record RunRequest(String finYear, Scope scope, String eis, String reason) {}

    /** What the AGM (CPF) approves: the year as it stands when the freeze is requested. */
    public record FreezeProposal(
            String finYear,
            Long zoneId,
            String zone,
            int runs,
            long members,
            long notYetCalculated) {}

    /** A freeze request as submitted. */
    public record FreezeRequested(Long approvalId, String finYear, String zone) {}

    /** One covered ledger and what the run found for it. */
    private record Evaluated(EmployeePfHeader ledger, String reason, boolean stopped) {}

    private final CpfRepositories.Headers headers;
    private final CpfRepositories.InterestRates rates;
    private final CpfRepositories.InterestRuns runs;
    private final CpfRepositories.InterestFreezes freezes;
    private final CpfZones zones;
    private final InterestLedgers ledgers;
    private final EmployeeDirectory directory;
    private final MakerChecker approvals;
    private final ApprovalStatuses statuses;
    private final Authorization policy;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public InterestRunService(
            CpfRepositories.Headers headers,
            CpfRepositories.InterestRates rates,
            CpfRepositories.InterestRuns runs,
            CpfRepositories.InterestFreezes freezes,
            CpfZones zones,
            EmployeeDirectory directory,
            InterestLedgers ledgers,
            MakerChecker approvals,
            ApprovalStatuses statuses,
            Authorization policy,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.headers = headers;
        this.rates = rates;
        this.runs = runs;
        this.freezes = freezes;
        this.zones = zones;
        this.ledgers = ledgers;
        this.directory = directory;
        this.approvals = approvals;
        this.statuses = statuses;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    /** SRS C-12 field table: the tiles and the Interest Run Audit list of the zone's year. */
    @Transactional(readOnly = true)
    public Summary summary(AccessContext context, String finYear) {
        policy.requireAt(context, SCREEN, "VIEW", context.locationId());
        FinYear year = InterestRateRules.finYear(finYear);
        CpfZones.Zone zone = zones.of(context.locationId());
        // Deep profile 2026-10-27: counted in the database, not by loading every ledger of the
        // zone.
        InterestLedgers.Counts counts =
                ledgers.counts(year.code(), zone.id(), RUN, CpfLedgerService.FUND);
        List<CpfInterestRun> history =
                runs.findByFinYearAndZoneIdOrderByRunNumberDesc(year.code(), zone.id());
        Map<Long, String> eis = eisOf(context, history);
        Optional<CpfInterestFreeze> frozen = freezes.findByFinYearAndZoneId(year.code(), zone.id());
        return new Summary(
                year.code(),
                zone.code(),
                counts.members(),
                counts.calculated(),
                counts.members() - counts.calculated(),
                Math.max(0, history.size() - 1),
                freezeStatus(frozen, year, zone),
                frozen.map(CpfInterestFreeze::getFrozenBy).orElse(null),
                frozen.map(CpfInterestFreeze::getFrozenAt).orElse(null),
                history.stream().map(r -> scopedView(r, eis)).toList());
    }

    /**
     * SRS-6-45: the separate report of members whose interest is not yet calculated, one page in
     * ledger order (paged in the database: deep profile 2026-10-27). Bounded by the session's data
     * scope (SRS-G-29): SRS 14.4 lists the office roles on C-12 (OD-125) and the zone's CPF
     * Division covers the whole zone, so each reads its own members and never another office's.
     */
    @Transactional(readOnly = true)
    public Paged<NotCalculated> notCalculated(
            AccessContext context, String finYear, PageSpec page) {
        policy.requireAt(context, SCREEN, "VIEW", context.locationId());
        FinYear year = InterestRateRules.finYear(finYear);
        CpfZones.Zone zone = zones.of(context.locationId());
        return ledgers.notCalculated(
                year.code(),
                zone.id(),
                RUN,
                CpfLedgerService.FUND,
                year,
                context.dataScope(),
                page);
    }

    // ---------------------------------------------------------------- runs

    /**
     * SRS-6-45: a run or re-run of the zone's year, for all members or one, before the freeze;
     * refused for an undeclared or missing rate (DDL {@code pf_interest_rate}). The run is recorded
     * with its scope, initiator, time, member counts and reason, and audited.
     */
    @Transactional
    public RunView run(AccessContext context, RunRequest request) {
        policy.requireAt(context, SCREEN, "CREATE", context.locationId());
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        InterestRunRules.requireScope(request.scope(), request.eis());
        FinYear year = InterestRateRules.finYear(request.finYear());
        CpfZones.Zone zone = zones.of(context.locationId());
        zones.lock(zone.id());
        requireOpen(year, zone);
        List<EmployeePfHeader> inZone = covered(year, zone.id());
        EmployeeDirectory.Member member = null;
        if (request.scope() == Scope.INDIVIDUAL) {
            member = memberInScope(context, request.eis());
            Long id = member.id();
            inZone = inZone.stream().filter(h -> id.equals(h.getEmployeeId())).toList();
            if (inZone.isEmpty()) {
                throw new FciException(CpfErrorCode.NO_LEDGER);
            }
        }
        Map<PfInterestRate.Source, List<PfInterestRate>> ofYear = ratesOf(year);
        InterestRunRules.requireRates(
                year,
                inZone.stream()
                        .map(
                                h ->
                                        InterestRunRules.sourceOf(
                                                MemberStatus.valueOf(h.getMemberStatus())))
                        .flatMap(Optional::stream)
                        .collect(
                                Collectors.toCollection(
                                        () -> EnumSet.noneOf(PfInterestRate.Source.class))),
                ofYear);
        List<Evaluated> evaluated = evaluate(year, inZone);
        long calculated = evaluated.stream().filter(e -> e.reason() == null).count();
        List<CpfInterestRun> history =
                runs.findByFinYearAndZoneIdOrderByRunNumberDesc(year.code(), zone.id());
        int number = history.isEmpty() ? 1 : history.get(0).getRunNumber() + 1;
        Instant now = clock.instant();
        CpfInterestRun run =
                runs.save(
                        CpfInterestRun.completed(
                                year.code(),
                                zone.id(),
                                number,
                                request.scope().name(),
                                member == null ? null : member.id(),
                                context.loginId(),
                                now,
                                request.reason().strip(),
                                new CpfInterestRun.Counts(
                                        calculated,
                                        evaluated.size() - calculated,
                                        evaluated.stream().filter(Evaluated::stopped).count()),
                                rateOfRecord(ofYear),
                                now));
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                RUN_ENTITY,
                                InterestRunRules.yearKey(year.code(), zone.code()) + "|" + number,
                                "run_scope",
                                null,
                                request.scope().name()
                                        + (member == null ? "" : " " + member.eis())
                                        + "; members "
                                        + evaluated.size()
                                        + ", calculated "
                                        + calculated,
                                request.reason().strip())
                        .module(SCREEN));
        return view(run, member == null ? null : member.eis());
    }

    // ---------------------------------------------------------------- the freeze

    /**
     * SRS-6-46: the Manager (CPF) asks for the year-end freeze; the approving role set in the
     * Approval Workflow Master decides it (route C12_SUBMIT, no OTP). Refused once frozen; a second
     * request while one is pending is refused by the approval engine.
     */
    @Transactional
    public FreezeRequested requestFreeze(AccessContext context, String finYear, String reason) {
        policy.requireAt(context, SCREEN, "SUBMIT", context.locationId());
        FinYear year = InterestRateRules.finYear(finYear);
        CpfZones.Zone zone = zones.of(context.locationId());
        requireOpen(year, zone);
        InterestLedgers.Counts counts =
                ledgers.counts(year.code(), zone.id(), RUN, CpfLedgerService.FUND);
        long notYet = counts.members() - counts.calculated();
        String key = InterestRunRules.yearKey(year.code(), zone.code());
        // OPEN-QUESTION: Q-07-3 - no source says whether a year may be frozen with members not yet
        // calculated or with no run at all; the AGM (CPF) sees both counts and decides.
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                "SUBMIT",
                                FREEZE_ENTITY,
                                key,
                                ActionType.CREATE,
                                context.locationId(),
                                json.writeValueAsString(
                                        new FreezeProposal(
                                                year.code(),
                                                zone.id(),
                                                zone.code(),
                                                runs.findByFinYearAndZoneIdOrderByRunNumberDesc(
                                                                year.code(), zone.id())
                                                        .size(),
                                                counts.members(),
                                                notYet)),
                                year.end(),
                                reason),
                        null);
        return new FreezeRequested(item.id(), year.code(), zone.code());
    }

    /**
     * SRS-6-46: an approved freeze is written with the runs that preceded it, and every ledger of
     * the zone's year is marked frozen; a returned or rejected request changes nothing.
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(FREEZE_ENTITY)
                || !SCREEN.equals(decision.moduleCode())
                || !decision.approved()) {
            return;
        }
        FreezeProposal proposal = json.readValue(decision.payload(), FreezeProposal.class);
        zones.lock(proposal.zoneId());
        if (freezes.findByFinYearAndZoneId(proposal.finYear(), proposal.zoneId()).isPresent()) {
            throw new FciException(CpfErrorCode.INTEREST_FROZEN);
        }
        List<CpfInterestRun> history =
                runs.findByFinYearAndZoneIdOrderByRunNumberDesc(
                        proposal.finYear(), proposal.zoneId());
        freezes.save(
                CpfInterestFreeze.approved(
                        proposal.finYear(),
                        proposal.zoneId(),
                        decision.checkerLoginId(),
                        decision.decidedAt(),
                        history.size(),
                        history.isEmpty() ? null : history.get(0).getInterestRunId(),
                        decision.approvalId()));
        for (EmployeePfHeader h :
                headers.findByFinYearAndZoneIdAndFundTypeOrderByEmployeeIdAscPfHeaderIdAsc(
                        proposal.finYear(), proposal.zoneId(), CpfLedgerService.FUND)) {
            h.freezeInterest();
            headers.save(h);
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                FREEZE_ENTITY,
                                decision.entityKey(),
                                "freeze_status",
                                FreezeStatus.OPEN.name(),
                                FreezeStatus.FROZEN.name(),
                                "Year-end CPF interest freeze after " + history.size() + " run(s)")
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    // ---------------------------------------------------------------- helpers

    private void requireOpen(FinYear year, CpfZones.Zone zone) {
        if (freezes.findByFinYearAndZoneId(year.code(), zone.id()).isPresent()) {
            throw new FciException(CpfErrorCode.INTEREST_FROZEN);
        }
    }

    private FreezeStatus freezeStatus(
            Optional<CpfInterestFreeze> frozen, FinYear year, CpfZones.Zone zone) {
        if (frozen.isPresent()) {
            return FreezeStatus.FROZEN;
        }
        String key = InterestRunRules.yearKey(year.code(), zone.code());
        return statuses.pending(FREEZE_ENTITY).stream().anyMatch(p -> key.equals(p.entityKey()))
                ? FreezeStatus.PENDING
                : FreezeStatus.OPEN;
    }

    /** The zone's ledgers of the year a run covers. */
    private List<EmployeePfHeader> covered(FinYear year, Long zoneId) {
        return headers
                .findByFinYearAndZoneIdAndFundTypeOrderByEmployeeIdAscPfHeaderIdAsc(
                        year.code(), zoneId, CpfLedgerService.FUND)
                .stream()
                .filter(h -> RUN.contains(MemberStatus.valueOf(h.getMemberStatus())))
                .toList();
    }

    /** What the run finds for each ledger; a null reason would mean calculated (Q-072). */
    private List<Evaluated> evaluate(FinYear year, List<EmployeePfHeader> ledgers) {
        Map<Long, EmployeeDirectory.CpfIdentity> identities =
                directory.cpfIdentities(
                        ledgers.stream().map(EmployeePfHeader::getEmployeeId).toList());
        List<Evaluated> out = new ArrayList<>();
        for (EmployeePfHeader h : ledgers) {
            EmployeeDirectory.CpfIdentity identity = identities.get(h.getEmployeeId());
            boolean hasCode =
                    identity != null && identity.cpfCode() != null && !identity.cpfCode().isBlank();
            // OPEN-QUESTION: Q-072 - the interest method; until it is stated no member is
            // calculated (decision D-4).
            out.add(
                    new Evaluated(
                            h,
                            InterestRunRules.notCalculatedReason(hasCode),
                            InterestRunRules.stoppedIn(year, h.getInterestEndDate())));
        }
        return out;
    }

    private Map<PfInterestRate.Source, List<PfInterestRate>> ratesOf(FinYear year) {
        Map<PfInterestRate.Source, List<PfInterestRate>> out = new LinkedHashMap<>();
        for (PfInterestRate r :
                rates.findByFinYearAndFundTypeOrderByAppliesFromAsc(
                        year.code(), CpfLedgerService.FUND)) {
            out.computeIfAbsent(
                            PfInterestRate.Source.valueOf(r.getRateSource()),
                            k -> new ArrayList<>())
                    .add(r);
        }
        return out;
    }

    /** The rate the run row names: the latest FCI-declared rate of the year, else the EPFO one. */
    private static Long rateOfRecord(Map<PfInterestRate.Source, List<PfInterestRate>> ofYear) {
        for (PfInterestRate.Source s :
                List.of(PfInterestRate.Source.FCI_TRUST, PfInterestRate.Source.RPFC)) {
            List<PfInterestRate> series = ofYear.getOrDefault(s, List.of());
            if (!series.isEmpty()) {
                return series.get(series.size() - 1).getInterestRateId();
            }
        }
        return null;
    }

    private EmployeeDirectory.Member memberInScope(AccessContext context, String eis) {
        EmployeeDirectory.Member member =
                directory
                        .memberByEis(eis)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        // Out of scope answers as not found: no enumeration of members.
        if (!policy.canAt(context, SCREEN, "CREATE", member.locationId())) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return member;
    }

    /**
     * The EIS of the members run individually, within the session's data scope only (SRS-G-29). A
     * run for a member of another office stays in the list with its counts, its member and reason
     * held back: the reason of an individual run names that member's case.
     */
    private Map<Long, String> eisOf(AccessContext context, List<CpfInterestRun> history) {
        List<Long> ids =
                history.stream()
                        .map(CpfInterestRun::getScopeEmployeeId)
                        .filter(java.util.Objects::nonNull)
                        .toList();
        Map<Long, String> out = new LinkedHashMap<>();
        directory
                .members(ids)
                .forEach(
                        (id, m) -> {
                            if (context.dataScope().contains(m.locationId())) {
                                out.put(id, m.eis());
                            }
                        });
        return out;
    }

    private static RunView scopedView(CpfInterestRun r, Map<Long, String> eis) {
        Long member = r.getScopeEmployeeId();
        RunView view = view(r, eis.get(member));
        return member == null || eis.containsKey(member)
                ? view
                : new RunView(
                        view.run(),
                        view.scope(),
                        null,
                        view.initiatedBy(),
                        view.initiatedAt(),
                        view.members(),
                        view.calculated(),
                        view.notCalculated(),
                        view.stopped(),
                        null);
    }

    private static RunView view(CpfInterestRun r, String eis) {
        long calculated = r.getMembersCalculated() == null ? 0 : r.getMembersCalculated();
        long notCalculated = r.getMembersNotCalculated() == null ? 0 : r.getMembersNotCalculated();
        return new RunView(
                r.getRunNumber(),
                r.getRunScope(),
                eis,
                r.getInitiatedBy(),
                r.getInitiatedAt(),
                calculated + notCalculated,
                calculated,
                notCalculated,
                r.getMembersInterestStopped() == null ? 0 : r.getMembersInterestStopped(),
                r.getReason());
    }
}
