package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.FieldChanges;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.configuration.api.RuleSet;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.InterestRateRules.FinYear;
import in.gov.fci.payroll.cpf.business.InterestStop;
import in.gov.fci.payroll.cpf.business.LedgerBalances;
import in.gov.fci.payroll.cpf.business.MemberTransitions;
import in.gov.fci.payroll.cpf.business.model.CpfMemberIdHistory;
import in.gov.fci.payroll.cpf.business.model.EmployeePfDetail;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader.MemberStatus;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader.OpeningSource;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member CPF ledger (FRS V0.5 §13 ledger maintenance; SRS-6-57, SRS-6-59, SRS-6-9, BR-CPF-025):
 * one ledger per member, financial year and CPF Member ID ({@code employee_pf_header}) with monthly
 * rows ({@code employee_pf_detail}). {@link #post} is the ONE path that changes a closing balance -
 * contributions, adjustments, withdrawals, recoveries and receipts all go through it, under the
 * ledger's row lock, so SRS-6-57 (no part ever negative) is checked once, for every path, and two
 * postings on one ledger never interleave.
 *
 * <p>The ledger also follows the member's approved life-cycle events (separation, reinstatement,
 * deputation; {@link MemberTransitions}): the ex-member interest stop of SRS-6-9 is set and cleared
 * here. A CPF problem met while following an event never fails the event's own approval: it is
 * raised on X-05 instead.
 */
@Service
@Loggable
public class CpfLedgerService {

    /** The fund this ledger keeps (GPF is a distinct scheme, never merged: DDL). */
    static final String FUND = "CPF";

    /** The audit entity of a ledger; its key is {@code <employeeId>|<financial year>}. */
    static final String ENTITY = "employee_pf_header";

    /** The X-05 condition of a life-cycle event the ledger could not follow. */
    static final String EVENT_NOT_FOLLOWED = "CPF_EVENT_NOT_FOLLOWED";

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * One posting to a member's ledger.
     *
     * @param day the day it applies to (its financial year and month)
     * @param type the monthly row it writes, or null for a movement with no monthly row (a
     *     withdrawal debit, a recovery)
     * @param change the signed change per part (a debit is negative)
     */
    public record Posting(
            Long employeeId,
            LocalDate day,
            EmployeePfDetail.PaymentType type,
            LedgerBalances change,
            BigDecimal wageBase,
            BigDecimal eps,
            BigDecimal pohw,
            BigDecimal rounding,
            String actor,
            String reason) {}

    private final CpfRepositories.Headers headers;
    private final CpfRepositories.Details details;
    private final CpfRepositories.MemberIds memberIds;
    private final CpfMembers members;
    private final RuleEngine engine;
    private final CpfExceptions exceptions;
    private final AuditService audit;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public CpfLedgerService(
            CpfRepositories.Headers headers,
            CpfRepositories.Details details,
            CpfRepositories.MemberIds memberIds,
            CpfMembers members,
            RuleEngine engine,
            CpfExceptions exceptions,
            AuditService audit,
            Clock clock) {
        this.headers = headers;
        this.details = details;
        this.memberIds = memberIds;
        this.members = members;
        this.engine = engine;
        this.exceptions = exceptions;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- the ledger

    /**
     * The member's ledger of {@code year}, opened if it has none yet: the opening balance is the
     * closing balance of the member's latest earlier ledger (or zero for a first ledger), and the
     * member's state and interest stop carry over. A ledger closed by a parent-zone change is not
     * carried: its balance moved to the new Member ID (SRS-6-52, phase 07k).
     */
    @Transactional
    public Long ledger(CpfMembers.Member member, FinYear year) {
        List<Long> found = headers.idsOf(member.employeeId(), year.code(), FUND);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        Optional<EmployeePfHeader> earlier =
                headers
                        .findByEmployeeIdAndFundTypeOrderByFinYearDescPfHeaderIdDesc(
                                member.employeeId(), FUND)
                        .stream()
                        .filter(h -> h.getFinYear().compareTo(year.code()) < 0)
                        .filter(
                                h ->
                                        !MemberStatus.CLOSED_ZONE_CHANGE
                                                .name()
                                                .equals(h.getMemberStatus()))
                        .findFirst();
        EmployeePfHeader opened =
                EmployeePfHeader.open(
                        member.employeeId(),
                        year.code(),
                        FUND,
                        member.zoneId(),
                        memberIdOn(member.employeeId(), year.start()),
                        earlier.map(EmployeePfHeader::closing).orElse(LedgerBalances.ZERO),
                        OpeningSource.SYSTEM_COMPUTED,
                        earlier.map(h -> MemberStatus.valueOf(h.getMemberStatus()))
                                .orElse(MemberStatus.ACTIVE),
                        earlier.map(EmployeePfHeader::isIsDeputation).orElse(false));
        earlier.filter(h -> h.getInterestEndDate() != null)
                .ifPresent(
                        h ->
                                opened.stopInterest(
                                        h.getInterestEndDate(),
                                        EmployeePfHeader.InterestEndReason.valueOf(
                                                h.getInterestEndReason()),
                                        clock.instant()));
        return headers.save(opened).getPfHeaderId();
    }

    /**
     * SRS-6-57, BR-CPF-025: posts one change to the member's ledger of the posting's year, under
     * the ledger's lock; refused when any part would go negative, when the ledger is closed, or
     * when the member has no CPF code (raised on X-05). Returns the new closing balance. Audited
     * per changed part (SRS-20-2).
     */
    @Transactional
    public LedgerBalances post(Posting p) {
        CpfMembers.Member member = members.require(p.employeeId(), p.actor());
        FinYear year = FinYear.containing(p.day());
        EmployeePfHeader header = headers.lockById(ledger(member, year)).orElseThrow();
        MemberStatus status = MemberStatus.valueOf(header.getMemberStatus());
        if (status == MemberStatus.FINALLY_SETTLED || status == MemberStatus.CLOSED_ZONE_CHANGE) {
            throw new FciException(
                    CpfErrorCode.LEDGER_CLOSED, new Object[] {status.name()}, List.of(), null);
        }
        LedgerBalances before = header.closing();
        LedgerBalances after = before.apply(p.change());
        header.close(after);
        headers.save(header);
        if (p.type() != null) {
            details.save(
                    EmployeePfDetail.of(
                            header.getPfHeaderId(),
                            p.employeeId(),
                            p.day().format(YYMM),
                            p.type(),
                            p.wageBase(),
                            p.change().employee(),
                            p.change().employer(),
                            p.change().vpf(),
                            p.eps() == null ? BigDecimal.ZERO : p.eps(),
                            p.pohw(),
                            p.rounding(),
                            header.getZoneId(),
                            p.day().withDayOfMonth(1)));
        }
        audit.recordAll(
                p.actor(),
                FieldChanges.between(
                                ENTITY, key(header), closing(before), closing(after), p.reason())
                        .stream()
                        .map(e -> e.module(FUND).effective(p.day()))
                        .toList());
        return after;
    }

    /**
     * SRS-6-43, SRS-6-44: applies an approved I-22 correction of the opening balance of {@code
     * finYear}. The established opening stays as it was (the correction rows hold the old and new
     * amounts); the year's closing moves by the difference as an ADJUSTMENT row of the as-at month,
     * and every later year carries it, opening and closing. Refused, all or nothing, when any part
     * of any year would go negative (SRS-6-57).
     */
    @Transactional
    public void applyCorrection(
            Long employeeId,
            String finYear,
            LocalDate asAt,
            LedgerBalances difference,
            String actor,
            String reason) {
        post(
                new Posting(
                        employeeId,
                        asAt,
                        EmployeePfDetail.PaymentType.ADJUSTMENT,
                        difference,
                        null,
                        null,
                        null,
                        null,
                        actor,
                        reason));
        for (EmployeePfHeader later :
                headers.findByEmployeeIdAndFundTypeOrderByFinYearDescPfHeaderIdDesc(
                        employeeId, FUND)) {
            if (later.getFinYear().compareTo(finYear) > 0) {
                EmployeePfHeader h = headers.lockById(later.getPfHeaderId()).orElseThrow();
                LedgerBalances before = h.closing();
                h.carry(h.opening().apply(difference), before.apply(difference));
                headers.save(h);
                audit.recordAll(
                        actor,
                        FieldChanges.between(
                                        ENTITY,
                                        key(h),
                                        closing(before),
                                        closing(h.closing()),
                                        reason)
                                .stream()
                                .map(e -> e.module(FUND).effective(asAt))
                                .toList());
            }
        }
    }

    /**
     * The interest already credited to the member's ledgers from the year of {@code from} on
     * (SRS-6-33: a corrected opening balance changes it only if some was credited).
     */
    @Transactional(readOnly = true)
    public BigDecimal interestCreditedFrom(Long employeeId, LocalDate from) {
        String year = FinYear.containing(from).code();
        return headers
                .findByEmployeeIdAndFundTypeOrderByFinYearDescPfHeaderIdDesc(employeeId, FUND)
                .stream()
                .filter(h -> h.getFinYear().compareTo(year) >= 0)
                .map(EmployeePfHeader::getInterestCredited)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---------------------------------------------------------------- life-cycle events

    /**
     * SRS-6-9 / BR-CPF-016: an approved life-cycle event moves the member's ledgers of its year and
     * later (the year's ledger is opened if needed, so a later year carries the state).
     */
    @EventListener
    public void on(EmployeeEvents.EmployeeLifecycleApplied event) {
        String actor = event.decidedBy();
        CpfMembers.Member member;
        try {
            member = members.require(event.employeeId(), actor);
        } catch (FciException notFollowed) {
            // Not a CPF member (nothing to follow), or no CPF code: already raised on X-05.
            return;
        }
        FinYear year = FinYear.containing(event.eventDate());
        ledger(member, year);
        for (EmployeePfHeader header :
                headers.findByEmployeeIdAndFundTypeOrderByFinYearDescPfHeaderIdDesc(
                        member.employeeId(), FUND)) {
            if (header.getFinYear().compareTo(year.code()) >= 0) {
                follow(event, header);
            }
        }
    }

    private void follow(EmployeeEvents.EmployeeLifecycleApplied event, EmployeePfHeader header) {
        Optional<MemberTransitions.Transition> next =
                MemberTransitions.after(
                        event.eventType(),
                        MemberStatus.valueOf(header.getMemberStatus()),
                        header.isIsDeputation());
        if (next.isEmpty()) {
            return;
        }
        MemberTransitions.Transition t = next.get();
        Map<String, String> before = state(header);
        if (t.stop() == MemberTransitions.Stop.CLEAR && header.getInterestEndDate() != null) {
            header.clearInterestStop(clock.instant());
        }
        header.status(t.status(), t.deputation());
        if (t.stop() == MemberTransitions.Stop.SET) {
            stopAt(event, header);
        }
        headers.save(header);
        audit.recordAll(
                event.decidedBy(),
                FieldChanges.between(
                                ENTITY,
                                key(header),
                                before,
                                state(header),
                                event.eventType() + " approved on I-17")
                        .stream()
                        .map(e -> e.module(FUND).effective(event.eventDate()))
                        .toList());
    }

    /**
     * The ex-member stop from the rule in force on the separation day; a rule not configured (or a
     * value that is not whole years) is raised on X-05 and no stop is guessed. The rule is read
     * from a snapshot so that its refusal never marks the event's transaction for rollback.
     */
    private void stopAt(EmployeeEvents.EmployeeLifecycleApplied event, EmployeePfHeader header) {
        Optional<LocalDate> end;
        try {
            RuleSet rules = engine.snapshot(event.eventDate());
            end =
                    InterestStop.exMemberEnd(
                            event.eventDate(),
                            rules.resolve(InterestStop.YEARS_RULE, RuleEngine.Context.unscoped())
                                    .map(RuleEngine.Resolved::scalar)
                                    .orElse(null),
                            header.isIsDeputation());
        } catch (FciException notConfigured) {
            end = Optional.empty();
        }
        if (end.isPresent()) {
            header.stopInterest(
                    end.get(),
                    EmployeePfHeader.InterestEndReason.EX_MEMBER_3_YEARS,
                    clock.instant());
            return;
        }
        exceptions.raise(
                event.decidedBy(),
                new ExceptionWorklist.NewEntry(
                        null,
                        header.getEmployeeId(),
                        CpfMembers.MODULE,
                        EVENT_NOT_FOLLOWED,
                        "BLOCKING",
                        null,
                        null,
                        ENTITY,
                        "interest_end_date",
                        "RULE",
                        "M-19",
                        "The ex-member interest stop could not be set: the rule "
                                + InterestStop.YEARS_RULE
                                + " is not configured as whole years (SRS-6-9)."));
    }

    // ---------------------------------------------------------------- helpers

    /** The member's CPF Member ID in use on {@code day}, if HRMS has sent one. */
    private Long memberIdOn(Long employeeId, LocalDate day) {
        return memberIds
                .findByEmployeeIdAndIdTypeOrderByEffectiveFromDesc(employeeId, FUND)
                .stream()
                .filter(id -> id.activeOn(day))
                .map(CpfMemberIdHistory::getMemberIdHistId)
                .findFirst()
                .orElse(null);
    }

    static String key(EmployeePfHeader header) {
        return header.getEmployeeId() + "|" + header.getFinYear();
    }

    static Map<String, String> closing(LedgerBalances b) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("closing_balance_employee", plain(b.employee()));
        image.put("closing_balance_employer", plain(b.employer()));
        image.put("closing_balance_vpf", plain(b.vpf()));
        return image;
    }

    static Map<String, String> state(EmployeePfHeader h) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("member_status", h.getMemberStatus());
        image.put("is_deputation", h.isIsDeputation() ? "Y" : "N");
        image.put(
                "interest_end_date",
                h.getInterestEndDate() == null ? null : h.getInterestEndDate().toString());
        image.put("interest_end_reason", h.getInterestEndReason());
        return image;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
