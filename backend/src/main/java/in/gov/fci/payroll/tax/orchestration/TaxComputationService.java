package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.configuration.api.RuleSet;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.EmployeeDirectory.PanStatus;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.InLists;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.tax.api.TaxDeductions;
import in.gov.fci.payroll.tax.business.DeclarationRules;
import in.gov.fci.payroll.tax.business.EarningsYear;
import in.gov.fci.payroll.tax.business.TaxCalculator;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.TaxableEarnings;
import in.gov.fci.payroll.tax.business.model.ExternalIncomeDeclaration;
import in.gov.fci.payroll.tax.business.model.ItDeclaration;
import in.gov.fci.payroll.tax.business.model.ItDeclarationSubmission;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import in.gov.fci.payroll.tax.business.model.ItSection;
import in.gov.fci.payroll.tax.business.model.SavingsDeclaration;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The income-tax and PT computation of a member's year (C-07; SRS-7-1 … SRS-7-4, SRS-7-11 …
 * SRS-7-14, SRS-7-19, SRS-7-27; BR-TAX-001 … 006, BR-TAX-014, BR-TAX-015; plan 08 phase 08e). The
 * inputs are assembled from what was approved: the frozen regime, the approved declaration round
 * (lines not reversed, at their verified amounts), approved external savings and other income, the
 * approved transport rebate; each section capped at its limit. Earnings come from payroll through
 * the {@link TaxableEarnings} port; every rate from the year's rule versions. Whatever is missing
 * is answered as a reason, never assumed.
 */
@Service
@Loggable
public class TaxComputationService implements TaxDeductions {

    public static final String SCREEN = "C-07";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** One deduction of the year: section, what was claimed, the cap, what counts. */
    public record DeductionView(
            String sectionCode, BigDecimal claimed, BigDecimal limit, BigDecimal allowed) {}

    /** One approved other-income entry (SRS-7-11). */
    public record IncomeView(String head, BigDecimal amount, BigDecimal tdsOtherSource) {}

    /** The inputs the computation reads, all approved (BR-TAX-014, BR-TAX-015). */
    public record Inputs(
            Regime regime,
            PanStatus pan,
            List<DeductionView> deductions,
            BigDecimal transportRebate,
            List<IncomeView> otherIncome) {

        public BigDecimal totalDeductions() {
            return deductions.stream()
                    .map(DeductionView::allowed)
                    .reduce(transportRebate, BigDecimal::add);
        }

        public BigDecimal totalOtherIncome() {
            return otherIncome.stream()
                    .map(IncomeView::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal otherSourceTds() {
            return otherIncome.stream()
                    .map(IncomeView::tdsOtherSource)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /**
     * C-07 / the Income Tax Forecast (SRS-7-12, SRS-7-27): the inputs, the earnings of the year
     * when payroll provides them, the computation, or the reasons it could not be made.
     *
     * @param employmentStatus SRS-7-27: the member's status (IN_SERVICE, SEPARATED, RETIRED), so a
     *     separated or retired member's year is shown as such
     * @param view PROJECTED (in-year) or ACTUAL (every month locked)
     * @param yearEndDifference SRS-7-19: actual tax less TDS deducted (ACTUAL view only)
     */
    public record Forecast(
            Long employeeId,
            String eis,
            String name,
            String employmentStatus,
            String finYear,
            String view,
            Inputs inputs,
            Optional<TaxableEarnings.Year> earnings,
            Optional<TaxCalculator.Result> result,
            Optional<BigDecimal> yearEndDifference,
            List<String> reasons) {}

    private final TaxRepositories.Regimes regimes;
    private final TaxRepositories.Submissions submissions;
    private final TaxRepositories.Declarations lines;
    private final TaxRepositories.Entries entries;
    private final TaxRepositories.Savings savings;
    private final TaxRepositories.Sections sections;
    private final PtStates states;
    private final TaxableEarnings earnings;
    private final EmployeeDirectory employees;
    private final OrgDirectory org;
    private final RuleEngine rules;
    private final ExceptionWorklist worklist;
    private final DeclarationService declarations;
    private final Authorization policy;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public TaxComputationService(
            TaxRepositories.Regimes regimes,
            TaxRepositories.Submissions submissions,
            TaxRepositories.Declarations lines,
            TaxRepositories.Entries entries,
            TaxRepositories.Savings savings,
            TaxRepositories.Sections sections,
            PtStates states,
            TaxableEarnings earnings,
            EmployeeDirectory employees,
            OrgDirectory org,
            RuleEngine rules,
            ExceptionWorklist worklist,
            DeclarationService declarations,
            Authorization policy,
            Clock clock) {
        this.regimes = regimes;
        this.submissions = submissions;
        this.lines = lines;
        this.entries = entries;
        this.savings = savings;
        this.sections = sections;
        this.states = states;
        this.earnings = earnings;
        this.employees = employees;
        this.org = org;
        this.rules = rules;
        this.worklist = worklist;
        this.declarations = declarations;
        this.policy = policy;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- C-07

    /** C-07: the forecast of a member (by EIS, in the user's offices; 404 outside them). */
    @Transactional(readOnly = true)
    public Forecast forecast(AccessContext context, String eis, String finYear) {
        policy.require(context, SCREEN, "VIEW");
        EmployeeDirectory.Member m =
                employees
                        .memberByEis(eis)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireRecordAt(context, SCREEN, "VIEW", m.locationId());
        String year = finYear == null ? DeclarationRules.finYearOf(today()) : finYear;
        return forecast(m, year, rateDate(year));
    }

    /** The year asked for, or the current financial year. */
    String yearOr(String finYear) {
        return finYear == null ? DeclarationRules.finYearOf(today()) : finYear;
    }

    /**
     * SRS-7-28: the forecast of a member's year on the approved inputs ({@code actual}) and on the
     * same inputs with a what-if's changes, both on the rules of the year's rate day. Nothing is
     * stored.
     */
    Forecast[] whatIf(
            EmployeeDirectory.Member m,
            String year,
            Regime regime,
            Map<String, BigDecimal> claims,
            List<IncomeView> extra) {
        Run rates = ratesOn(rateDate(year));
        for (String code : claims.keySet()) {
            if (!rates.sections().containsKey(code)) {
                throw new FciException(
                        TaxErrorCode.WHAT_IF,
                        new Object[] {code + " is not a section in force (M-18)"},
                        List.of(),
                        null);
            }
        }
        Optional<TaxableEarnings.Year> y = earnings.year(m.id(), year);
        Forecast actual = forecastOn(m, year, rates, y);
        Forecast varied =
                forecastOn(
                        m,
                        year,
                        rates,
                        y,
                        inputs(m.id(), year, rates.sections(), regime, claims, extra));
        return new Forecast[] {actual, varied};
    }

    /** The forecast of one member and year, rates as of {@code asOf}. */
    Forecast forecast(EmployeeDirectory.Member m, String year, LocalDate asOf) {
        return forecast(m, year, asOf, ratesOn(asOf));
    }

    /**
     * What every member of one computation run shares, read once when first needed and then held:
     * the rules in force on the day (BR-CYC-011: a generation over a paying unit computes every
     * member on one snapshot) and the section master in force (one read per run, not per member;
     * profiled 2026-10-25). Resolving in the snapshot never goes through the rule engine's
     * transaction, so a rate not configured is answered as a reason and never marks the caller's
     * transaction for rollback.
     */
    public final class Run implements Supplier<RuleSet> {
        private final LocalDate asOf;
        private RuleSet rateSet;
        private Map<String, ItSection> master;

        private Run(LocalDate asOf) {
            this.asOf = asOf;
        }

        /** The rules in force on the run's day. */
        @Override
        public RuleSet get() {
            if (rateSet == null) {
                rateSet = rules.snapshot(asOf);
            }
            return rateSet;
        }

        /** The sections in force on the run's day, by code. */
        Map<String, ItSection> sections() {
            if (master == null) {
                master =
                        sections.inForceOn(asOf).stream()
                                .collect(
                                        Collectors.toMap(
                                                ItSection::getSectionCode, Function.identity()));
            }
            return master;
        }
    }

    /** Payroll provides the member's year (the earnings port): without it nothing is computed. */
    public boolean hasYear(Long employeeId, String year) {
        return earnings.year(employeeId, year).isPresent();
    }

    /**
     * FRS V0.5 §14 Preconditions (l.5545, BR-TAX-001; US-GAP-196): the member's regime for the year
     * is captured and frozen (approved and locked) before a certificate or return is generated. A
     * regime reset and not yet frozen again is not.
     */
    public boolean regimeFrozen(Long employeeId, String year) {
        return regimes.findByEmployeeIdAndFinYear(employeeId, year)
                .filter(r -> "APPROVED".equals(r.getVerifyStatus()) && r.isLocked())
                .isPresent();
    }

    /**
     * Those of {@code employeeIds} whose regime of {@code year} is frozen ({@link #regimeFrozen}),
     * read in chunks of at most {@link InLists#MAX} members (Wave C review: no query per member).
     */
    public java.util.Set<Long> frozenRegimes(java.util.Collection<Long> employeeIds, String year) {
        List<Long> ids = List.copyOf(new java.util.LinkedHashSet<>(employeeIds));
        java.util.Set<Long> frozen = new java.util.HashSet<>();
        for (int from = 0; from < ids.size(); from += InLists.MAX) {
            regimes
                    .findByFinYearAndEmployeeIdIn(
                            year, ids.subList(from, Math.min(ids.size(), from + InLists.MAX)))
                    .stream()
                    .filter(r -> "APPROVED".equals(r.getVerifyStatus()) && r.isLocked())
                    .forEach(r -> frozen.add(r.getEmployeeId()));
        }
        return frozen;
    }

    /** A run on the rules and sections in force on {@code asOf}. */
    public Run ratesOn(LocalDate asOf) {
        return new Run(asOf);
    }

    /** The forecast of one member and year on the rules {@code rates} holds. */
    public Forecast forecast(EmployeeDirectory.Member m, String year, LocalDate asOf, Run rates) {
        return forecastOn(m, year, rates, earnings.year(m.id(), year));
    }

    /** The forecast on a given year of earnings (the payroll run's own month, plan 16 16e-2). */
    Forecast forecastOn(
            EmployeeDirectory.Member m, String year, Run rates, Optional<TaxableEarnings.Year> y) {
        return forecastOn(m, year, rates, y, inputs(m.id(), year, rates.sections()));
    }

    /** The forecast on given inputs (SRS-7-28: a what-if's varied inputs, never stored). */
    Forecast forecastOn(
            EmployeeDirectory.Member m,
            String year,
            Run rates,
            Optional<TaxableEarnings.Year> y,
            Inputs in) {
        List<String> reasons = new ArrayList<>();
        if (in.regime() == null) {
            reasons.add(TaxErrorCode.REGIME_REQUIRED.code());
        }
        if (y.isEmpty()) {
            reasons.add(TaxErrorCode.EARNINGS_NOT_AVAILABLE.code());
        }
        Optional<TaxCalculator.Result> result = Optional.empty();
        if (reasons.isEmpty()) {
            try {
                result = Optional.of(compute(in, y.get(), rates.get()));
            } catch (FciException e) {
                reasons.add(e.getErrorCode().code() + argsOf(e));
            }
        }
        boolean actual = y.map(TaxableEarnings.Year::allActual).orElse(false);
        Optional<BigDecimal> difference =
                actual
                        ? result.map(
                                r ->
                                        TaxCalculator.yearEndDifference(
                                                r.annualTax(),
                                                y.get().tdsDeducted().add(in.otherSourceTds())))
                        : Optional.empty();
        return new Forecast(
                m.id(),
                m.eis(),
                m.name(),
                m.employmentStatus(),
                year,
                actual ? "ACTUAL" : "PROJECTED",
                in,
                y,
                result,
                difference,
                reasons);
    }

    /** The month's TDS of the payroll run (plan 16 phase 16e-2), or why it is not computed. */
    record EngineTds(Optional<BigDecimal> tds, boolean regimeMissing, List<String> reasons) {}

    /**
     * SRS-7-3, SRS-7-13 (plan 16 phase 16e-2, D16-27): the month's TDS of a member while the
     * payroll run computes {@code month}: the year of the written results with {@code month} at the
     * taxable earnings just computed ({@link EarningsYear#withMonth}). Read-only: the X-05 entries
     * (regime, PAN) and the proof deadline stay with {@link #monthly}.
     */
    EngineTds engineTds(
            long employeeId,
            YearMonth month,
            BigDecimal taxableGross,
            BigDecimal employerNps,
            List<TaxableEarnings.Line> lines,
            Run rates) {
        EmployeeDirectory.Member m =
                employees
                        .member(employeeId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        String year = DeclarationRules.finYearOf(month.atDay(1));
        String yymm = String.format("%04d%02d", month.getYear(), month.getMonthValue());
        TaxableEarnings.Year y =
                EarningsYear.withMonth(
                        earnings.year(employeeId, year),
                        year,
                        yymm,
                        taxableGross,
                        employerNps,
                        lines);
        Forecast f = forecastOn(m, year, rates, Optional.of(y));
        return new EngineTds(
                f.result().map(TaxCalculator.Result::monthlyTds),
                f.inputs().regime() == null,
                f.reasons());
    }

    // ---------------------------------------------------------------- the engines (tax.api)

    /**
     * SRS-7-3, SRS-7-4: the month's TDS and PT for the payroll engines. The proof deadline of the
     * year is applied first (SRS-7-14); a missing regime or PAN is raised on X-05 and payroll
     * continues (SRS-7-1, SRS-7-13).
     */
    @Override
    @Transactional
    public Monthly monthly(Long employeeId, YearMonth month, String actor) {
        EmployeeDirectory.Member m =
                employees
                        .member(employeeId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        String year = DeclarationRules.finYearOf(month.atDay(1));
        declarations.reverseUnprovedLines(year, actor);
        LocalDate asOf = month.atDay(1);
        Run rates = ratesOn(asOf);
        Forecast f = forecast(m, year, asOf, rates);
        if (f.inputs().regime() == null) {
            raise(
                    actor,
                    m,
                    "TAX_REGIME_MISSING",
                    "it_regime_detail",
                    "regime_code",
                    "ESS",
                    "E-01",
                    "No approved tax regime for " + year + ": tax not computed");
        }
        if (f.inputs().pan() != PanStatus.VALID) {
            raise(
                    actor,
                    m,
                    "PAN_MISSING",
                    "employee",
                    "pan_number",
                    "HRMS",
                    "I-21",
                    "PAN "
                            + f.inputs().pan().name().toLowerCase(java.util.Locale.ROOT)
                            + ": TDS at the higher rate (SRS-7-13)");
        }
        List<String> reasons = new ArrayList<>(f.reasons());
        Optional<BigDecimal> pt = Optional.empty();
        try {
            String yymm = String.format("%04d%02d", month.getYear(), month.getMonthValue());
            pt =
                    professionalTax(
                            m.locationId(),
                            f.earnings()
                                    .flatMap(
                                            fy ->
                                                    fy.months().stream()
                                                            .filter(x -> yymm.equals(x.yymm()))
                                                            .findFirst())
                                    .map(TaxableEarnings.Month::taxableGross),
                            asOf,
                            rates);
        } catch (FciException e) {
            reasons.add(e.getErrorCode().code() + argsOf(e));
        }
        return new Monthly(
                f.result().map(TaxCalculator.Result::monthlyTds),
                pt,
                f.result().map(TaxCalculator.Result::higherRateApplied).orElse(false),
                List.copyOf(reasons));
    }

    // ---------------------------------------------------------------- assembly

    /**
     * The approved inputs of a member's year (BR-TAX-014, BR-TAX-015), sections as of {@code asOf}.
     */
    Inputs inputs(Long employeeId, String year, LocalDate asOf) {
        return inputs(employeeId, year, ratesOn(asOf).sections());
    }

    /** The approved inputs of a member's year, against the sections of the run. */
    Inputs inputs(Long employeeId, String year, Map<String, ItSection> master) {
        return inputs(employeeId, year, master, null, Map.of(), List.of());
    }

    /**
     * The inputs of a member's year with a what-if's changes (SRS-7-28): another regime, a
     * section's claimed amount instead of the approved one, extra income lines. Without changes,
     * the approved inputs.
     */
    Inputs inputs(
            Long employeeId,
            String year,
            Map<String, ItSection> master,
            Regime regimeChange,
            Map<String, BigDecimal> claimChanges,
            List<IncomeView> extraIncome) {
        Regime regime =
                regimeChange != null
                        ? regimeChange
                        : regimes.findByEmployeeIdAndFinYear(employeeId, year)
                                .filter(r -> "APPROVED".equals(r.getVerifyStatus()))
                                .map(ItRegimeDetail::getRegime)
                                .orElse(null);
        PanStatus pan = employees.panStatus(employeeId).orElse(PanStatus.MISSING);
        Map<String, BigDecimal> claimed = new TreeMap<>();
        submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(employeeId, year).stream()
                .filter(s -> s.status() == ItDeclarationSubmission.Status.APPROVED)
                .findFirst()
                .ifPresent(
                        s ->
                                lines
                                        .findBySubmissionIdOrderByDeclarationId(s.getSubmissionId())
                                        .stream()
                                        .filter(l -> "APPROVED".equals(l.getProofStatus()))
                                        .forEach(
                                                l ->
                                                        claimed.merge(
                                                                l.getSectionCode(),
                                                                amountOf(l),
                                                                BigDecimal::add)));
        BigDecimal rebate = BigDecimal.ZERO;
        for (SavingsDeclaration s :
                savings.findByEmployeeIdAndFinYearOrderBySavingsId(employeeId, year)) {
            if (s.type() == SavingsDeclaration.Type.EXTERNAL
                    && s.getSectionCode() != null
                    && s.getAnnualAmount() != null) {
                claimed.merge(s.getSectionCode(), s.getAnnualAmount(), BigDecimal::add);
            } else if (s.type() == SavingsDeclaration.Type.TRANSPORT_REBATE
                    && s.getMonthlyAmount() != null) {
                rebate = rebate.add(s.getMonthlyAmount());
            }
        }
        claimed.putAll(claimChanges);
        List<DeductionView> deductions = new ArrayList<>();
        claimed.forEach(
                (code, amount) -> {
                    ItSection section = master.get(code);
                    // SRS-7-20: only a section of the master that applies under the regime counts.
                    if (section != null && regime != null && applies(section, regime)) {
                        BigDecimal limit = section.getMaxDeductionAmount();
                        deductions.add(
                                new DeductionView(
                                        code,
                                        amount,
                                        limit,
                                        DeclarationRules.allowed(amount, limit)));
                    }
                });
        List<IncomeView> income =
                entries.findByEmployeeIdAndFinYearOrderByExtIncomeIdDesc(employeeId, year).stream()
                        .filter(e -> e.status() == ExternalIncomeDeclaration.Status.APPROVED)
                        .filter(e -> e.type() == ExternalIncomeDeclaration.Type.OTHER_INCOME)
                        .map(
                                e ->
                                        new IncomeView(
                                                e.getIncomeHead(),
                                                e.getAmount(),
                                                e.getTdsOtherSource()))
                        .collect(Collectors.toCollection(ArrayList::new));
        income.addAll(extraIncome);
        return new Inputs(regime, pan, List.copyOf(deductions), rebate, List.copyOf(income));
    }

    private TaxCalculator.Result compute(Inputs in, TaxableEarnings.Year y, RuleSet asOf) {
        String slabKey = in.regime() == Regime.OLD ? "TAX.SLAB.OLD" : "TAX.SLAB.NEW";
        TaxCalculator.Rates rates =
                new TaxCalculator.Rates(
                        bands(slabKey, asOf, null).orElseThrow(() -> notConfigured(slabKey)),
                        bands("TAX.SURCHARGE", asOf, null),
                        scalar("TAX.CESS", asOf),
                        scalar("TAX.NOPAN.RATE", asOf));
        // SRS-7-27, SRS-7-29: the employer's NPS contribution is income with its matching
        // exemption.
        BigDecimal nps = y.employerNps();
        return TaxCalculator.compute(
                new TaxCalculator.Input(
                        in.regime(),
                        y.gross().add(in.totalOtherIncome()).add(nps),
                        in.totalDeductions().add(nps),
                        in.pan() == PanStatus.VALID,
                        y.tdsDeducted().add(in.otherSourceTds()),
                        y.remainingMonths()),
                rates);
    }

    /**
     * SRS-7-4, BR-TAX-006: the month's PT of a member of {@code locationId} on its taxable gross,
     * by the slab of the office's state (zero where PT does not apply); refused
     * (TAX-422-RATE-NOT-CONFIGURED) when the state or its slab is not configured, or the gross is
     * not known.
     *
     * <p>OPEN-QUESTION (CLR-55, US-GAP-190): SRS-7-4 and BR-TAX-006 say "the employee's state",
     * while each location holds its "State (for Professional Tax)" (SRS l.557); the state of the
     * member's office is used until FCI says which.
     */
    Optional<BigDecimal> professionalTax(
            Long locationId, Optional<BigDecimal> gross, LocalDate asOf, Supplier<RuleSet> rates) {
        String state = org.location(locationId).map(l -> l.getStateCode()).orElse(null);
        Optional<PtStates.View> ptState = state == null ? Optional.empty() : states.state(state);
        if (ptState.isEmpty()) {
            // The office's state is not in the PT state master (M-18): nothing is assumed.
            throw notConfigured(
                    "pt_state_master " + (state == null ? "(no state on the office)" : state));
        }
        if (!ptState.get().ptApplicable() || !ptState.get().inForceOn(asOf)) {
            return Optional.of(BigDecimal.ZERO);
        }
        if (gross.isEmpty()) {
            throw new FciException(TaxErrorCode.EARNINGS_NOT_AVAILABLE);
        }
        List<TaxCalculator.Band> bands =
                bands(ptState.get().slabRuleKey(), rates.get(), state)
                        .orElseThrow(() -> notConfigured("PT.SLAB"));
        return Optional.of(TaxCalculator.professionalTax(true, gross.get(), bands));
    }

    private Optional<List<TaxCalculator.Band>> bands(String key, RuleSet asOf, String qualifier) {
        return resolved(key, asOf)
                .map(
                        r ->
                                r.bands().stream()
                                        .filter(
                                                b ->
                                                        qualifier == null
                                                                ? b.qualifier() == null
                                                                : qualifier.equals(b.qualifier()))
                                        .map(
                                                b ->
                                                        new TaxCalculator.Band(
                                                                b.from(), b.to(), b.rateOrAmount()))
                                        .toList())
                .filter(b -> !b.isEmpty());
    }

    private Optional<BigDecimal> scalar(String key, RuleSet asOf) {
        return resolved(key, asOf).map(RuleEngine.Resolved::scalar);
    }

    private Optional<RuleEngine.Resolved> resolved(String key, RuleSet asOf) {
        try {
            return asOf.resolve(key, RuleEngine.Context.unscoped());
        } catch (FciException notConfigured) {
            return Optional.empty();
        }
    }

    /**
     * SRS-7-19, BR-TAX-005: the difference between the actual tax of a final year and the TDS
     * deducted, rounded to the rupee as the TDS is (R-02), when it is not zero.
     */
    public static Optional<BigDecimal> mismatchOf(Forecast f) {
        return f.yearEndDifference()
                .map(d -> d.setScale(0, java.math.RoundingMode.HALF_UP))
                .filter(d -> d.signum() != 0);
    }

    /**
     * SRS-7-19: a year-end mismatch on X-05, raised by the screen that found it; it blocks the
     * finalisation of Form 16 and of the fourth-quarter return until corrected.
     */
    public void raiseYearEndMismatch(
            String actor,
            EmployeeDirectory.Member m,
            String finYear,
            BigDecimal difference,
            String screen) {
        raise(
                actor,
                m,
                screen,
                "TAX_YEAR_END_MISMATCH",
                "BLOCKING",
                "payroll_result_component",
                "tds",
                "CALC",
                SCREEN,
                "Actual tax for "
                        + finYear
                        + " differs from the TDS deducted by Rs "
                        + difference.toPlainString()
                        + ": correct before Form 16 or the Q4 return (SRS-7-19)");
    }

    private void raise(
            String actor,
            EmployeeDirectory.Member m,
            String code,
            String entity,
            String field,
            String source,
            String correction,
            String detail) {
        raise(actor, m, SCREEN, code, "WARNING", entity, field, source, correction, detail);
    }

    @SuppressWarnings("java:S107") // one argument per column of the exception entry
    private void raise(
            String actor,
            EmployeeDirectory.Member m,
            String screen,
            String code,
            String severity,
            String entity,
            String field,
            String source,
            String correction,
            String detail) {
        worklist.raise(
                actor,
                new ExceptionWorklist.NewEntry(
                        null,
                        m.id(),
                        screen,
                        code,
                        severity,
                        null,
                        null,
                        entity,
                        field,
                        source,
                        correction,
                        detail));
    }

    private static boolean applies(ItSection section, Regime regime) {
        String r = section.getRegimeApplicability();
        return "BOTH".equals(r) || regime.name().equals(r);
    }

    private static BigDecimal amountOf(ItDeclaration l) {
        return l.getVerifiedAmount() != null ? l.getVerifiedAmount() : l.getAllowedAmount();
    }

    private static FciException notConfigured(String key) {
        return new FciException(
                TaxErrorCode.RATE_NOT_CONFIGURED, new Object[] {key}, List.of(), null);
    }

    private static String argsOf(FciException e) {
        Object[] args = e.getMessageArgs();
        return args == null || args.length == 0 ? "" : ":" + args[0];
    }

    /** The rates of a year: today's version while the year runs, else the year's last day. */
    private LocalDate rateDate(String year) {
        int start = Integer.parseInt(year.substring(0, 4));
        LocalDate first = LocalDate.of(start, 4, 1);
        LocalDate last = LocalDate.of(start + 1, 3, 31);
        LocalDate today = today();
        if (today.isBefore(first)) {
            return first;
        }
        return today.isAfter(last) ? last : today;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(INDIA));
    }
}
