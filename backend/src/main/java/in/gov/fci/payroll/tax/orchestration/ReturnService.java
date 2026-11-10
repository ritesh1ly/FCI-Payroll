package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.EmployeeDirectory.PanStatus;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.tax.business.PostingLedger;
import in.gov.fci.payroll.tax.business.ReturnValidator;
import in.gov.fci.payroll.tax.business.StatutoryRules;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.TaxableEarnings;
import in.gov.fci.payroll.tax.business.model.FilingRecord;
import in.gov.fci.payroll.tax.business.model.OutputControlTotal;
import in.gov.fci.payroll.tax.business.model.ReturnDeductee;
import in.gov.fci.payroll.tax.business.model.StatutoryReturn;
import in.gov.fci.payroll.tax.business.model.StatutoryReturn.Status;
import in.gov.fci.payroll.tax.business.model.TaxChallan;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * C-11 Statutory Returns - Form 24Q (SRS-7-7, SRS-7-8, SRS-7-13, SRS-7-21, SRS-7-23, SRS-7-31; plan
 * 08 phase 08h). The Payroll Maker of the paying unit imports the challan file, generates the
 * return of a locked quarter under the Active M-17 definition (original, or a correction against a
 * released or filed return), validates it (missing PAN on X-05, challan mapping and remittance,
 * control totals against the posted payroll and the challans, the prescribed utility) and forwards
 * it; the Payroll Checker releases it (route C11_RELEASE); FCI's authorised signatory files it and
 * the Maker records the filing. Nothing is released on an assumed figure or a missing utility.
 */
@Service
@Loggable
public class ReturnService {

    public static final String SCREEN = "C-11";
    public static final String ENTITY = "statutory_return";
    public static final String ROUTE = "C11_RELEASE";
    static final String ORIGINAL = "FORM_24Q";
    static final String CORRECTION = "FORM_24Q_CORRECTION";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** Q-08-3: the columns of the challan file, as its optional first line names them. */
    static final String CHALLAN_HEADER = "cin,bsr,date,amount,reference,period";

    /** One return as C-11 shows it. */
    public record ReturnView(
            Long id,
            String type,
            String finYear,
            String quarter,
            long deductees,
            BigDecimal totalTds,
            String status,
            boolean validated,
            String validationUtility,
            List<String> validationResult,
            boolean correction,
            Long correctsReturnId,
            Instant generatedAt,
            String releasedBy,
            List<FilingView> filings) {}

    /** One deductee as a screen may show it: the PAN masked (SRS-7-23). */
    public record DeducteeView(
            Long employeeId,
            String eis,
            String name,
            String panMasked,
            boolean panValid,
            boolean higherRateApplied,
            BigDecimal amountPaid,
            BigDecimal taxDeducted,
            Long challanId,
            // SRS l.2625: the mapped challan as the department knows it (CIN / BSR / date).
            String challanReference) {}

    public record FilingView(
            LocalDate filingDate,
            String signatoryName,
            String signatoryDesignation,
            String acknowledgementToken,
            Long evidenceId) {}

    /** One imported challan. */
    public record ChallanView(
            Long id,
            String cin,
            String bsr,
            LocalDate date,
            BigDecimal amount,
            String reference,
            String period) {}

    /** What an import did: challans written and duplicates passed over. */
    public record Import(int imported, List<String> duplicates) {}

    /** The filing as the Maker records it (SRS-7-31). */
    public record Filing(
            LocalDate filingDate,
            String signatoryName,
            String signatoryDesignation,
            String token,
            String evidenceType,
            LocalDate evidenceDate,
            String evidenceIssuer,
            String fileName,
            byte[] content,
            // SRS-7-31 (l.2609): the evidence's own reference (null: the acknowledgement token).
            String evidenceReference) {

        /** The filing without an evidence reference of its own. */
        public Filing(
                LocalDate filingDate,
                String signatoryName,
                String signatoryDesignation,
                String token,
                String evidenceType,
                LocalDate evidenceDate,
                String evidenceIssuer,
                String fileName,
                byte[] content) {
            this(
                    filingDate,
                    signatoryName,
                    signatoryDesignation,
                    token,
                    evidenceType,
                    evidenceDate,
                    evidenceIssuer,
                    fileName,
                    content,
                    null);
        }
    }

    private final TaxRepositories.Returns returns;
    private final TaxRepositories.Deductees deductees;
    private final TaxRepositories.Challans challans;
    private final TaxRepositories.Filings filings;
    private final Form16Service units;
    private final TaxComputationService computation;
    private final OutputDefinitionService outputs;
    private final TaxableEarnings earnings;
    private final PostingLedger ledger;
    private final ReturnValidator validator;
    private final EmployeeDirectory employees;
    private final PayrollRecords payroll;
    private final ExceptionWorklist worklist;
    private final DocumentStore store;
    private final MakerChecker approvals;
    private final ApprovalStatuses statuses;
    private final Authorization policy;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final BatchScope batch;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public ReturnService(
            TaxRepositories.Returns returns,
            TaxRepositories.Deductees deductees,
            TaxRepositories.Challans challans,
            TaxRepositories.Filings filings,
            Form16Service units,
            TaxComputationService computation,
            OutputDefinitionService outputs,
            TaxableEarnings earnings,
            PostingLedger ledger,
            ReturnValidator validator,
            EmployeeDirectory employees,
            PayrollRecords payroll,
            ExceptionWorklist worklist,
            DocumentStore store,
            MakerChecker approvals,
            ApprovalStatuses statuses,
            Authorization policy,
            AuditService audit,
            ObjectMapper json,
            Clock clock,
            BatchScope batch) {
        this.batch = batch;
        this.returns = returns;
        this.deductees = deductees;
        this.challans = challans;
        this.filings = filings;
        this.units = units;
        this.computation = computation;
        this.outputs = outputs;
        this.earnings = earnings;
        this.ledger = ledger;
        this.validator = validator;
        this.employees = employees;
        this.payroll = payroll;
        this.worklist = worklist;
        this.store = store;
        this.approvals = approvals;
        this.statuses = statuses;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reads

    /** C-11: one page of the returns of a year of the user's paying unit, newest first. */
    @Transactional(readOnly = true)
    public Paged<ReturnView> register(AccessContext context, String finYear, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        OrgLocation unit = units.unitOf(context);
        Paged<StatutoryReturn> rows =
                Paged.of(
                        returns.findByOrgUnitIdInAndFinYearOrderByReturnIdDesc(
                                Set.of(unit.getId()), finYear, page.pageable()));
        // SRS-NF-6: the filings of the page in one read, not one per return.
        List<Long> ids = rows.items().stream().map(StatutoryReturn::getReturnId).toList();
        Map<Long, List<FilingRecord>> filed =
                ids.isEmpty()
                        ? Map.of()
                        : filings.findByReturnIdInOrderByFilingId(ids).stream()
                                .collect(
                                        java.util.stream.Collectors.groupingBy(
                                                FilingRecord::getReturnId));
        return rows.map(r -> view(r, filed.getOrDefault(r.getReturnId(), List.of())));
    }

    /** C-11: one page of the deductees of a return (PAN masked). */
    @Transactional(readOnly = true)
    public Paged<DeducteeView> deductees(AccessContext context, Long id, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        StatutoryReturn r = found(id);
        // A depot or a hosted DO resolves to its paying unit, but the member lines of the unit are
        // outside its data scope (SRS-G-29): the same check as every unit-based read.
        policy.requireAt(context, SCREEN, "VIEW", requireUnit(context, r).getId());
        Paged<ReturnDeductee> rows =
                Paged.of(
                        deductees.findByReturnIdAndTaxDeductedGreaterThanOrderByDeducteeId(
                                r.getReturnId(), BigDecimal.ZERO, page.pageable()));
        Map<Long, EmployeeDirectory.Member> members =
                employees.members(
                        rows.items().stream().map(ReturnDeductee::getEmployeeId).toList());
        List<Long> challanIds =
                rows.items().stream()
                        .map(ReturnDeductee::getChallanId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
        Map<Long, String> references = new java.util.HashMap<>();
        if (!challanIds.isEmpty()) {
            challans.findByChallanIdIn(challanIds)
                    .forEach(
                            c ->
                                    references.put(
                                            c.getChallanId(),
                                            c.getCinNumber()
                                                    + " / "
                                                    + c.getBsrCode()
                                                    + " / "
                                                    + c.getChallanDate()));
        }
        return rows.map(
                d -> {
                    EmployeeDirectory.Member m = members.get(d.getEmployeeId());
                    return new DeducteeView(
                            d.getEmployeeId(),
                            m == null ? null : m.eis(),
                            m == null ? null : m.name(),
                            d.maskedPan(),
                            d.isPanValid(),
                            d.isHigherRateApplied(),
                            d.getAmountPaid(),
                            d.getTaxDeducted(),
                            d.getChallanId(),
                            d.getChallanId() == null ? null : references.get(d.getChallanId()));
                });
    }

    /** C-11: the challans of a year of the user's paying unit. */
    @Transactional(readOnly = true)
    public List<ChallanView> challans(AccessContext context, String finYear) {
        policy.require(context, SCREEN, "VIEW");
        return challans
                .findByOrgUnitIdAndFinYearOrderByChallanDate(units.unitOf(context).getId(), finYear)
                .stream()
                .map(
                        c ->
                                new ChallanView(
                                        c.getChallanId(),
                                        c.getCinNumber(),
                                        c.getBsrCode(),
                                        c.getChallanDate(),
                                        c.getChallanAmount(),
                                        c.getPaymentReference(),
                                        c.getPeriodCode()))
                .toList();
    }

    // ---------------------------------------------------------------- challans

    /**
     * SRS-7-21: the Payroll Maker imports the challan file of a year (Q-08-3: one challan per line,
     * {@code cin,bsr,date,amount,reference,period}, an optional header line). The whole file is
     * refused on the first wrong line; a challan already imported is passed over and listed.
     */
    @Transactional
    public Import importChallans(AccessContext context, String finYear, byte[] file) {
        policy.require(context, SCREEN, "CREATE");
        OrgLocation unit = units.unitOf(context);
        if (file == null || file.length == 0) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        String[] lines = new String(file, StandardCharsets.US_ASCII).split("\\r?\\n");
        List<StatutoryRules.ChallanLine> parsed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            // The optional header is the column names themselves: a first challan whose CIN
            // starts with the letters "CIN" is a challan, not a header.
            if (line.isEmpty() || (i == 0 && isHeader(line))) {
                continue;
            }
            StatutoryRules.ChallanLine c = StatutoryRules.parseChallan(i + 1, line, finYear);
            if (!seen.add(c.cin() + "|" + c.bsr() + "|" + c.date())) {
                throw new FciException(
                        TaxErrorCode.CHALLAN_FILE,
                        new Object[] {i + 1, "the challan is in the file twice"},
                        List.of(),
                        null);
            }
            parsed.add(c);
        }
        List<String> duplicates = new ArrayList<>();
        int imported = 0;
        for (StatutoryRules.ChallanLine c : parsed) {
            if (challans.findByCinNumberAndBsrCodeAndChallanDate(c.cin(), c.bsr(), c.date())
                    .isPresent()) {
                duplicates.add(c.cin() + " / " + c.bsr() + " / " + c.date());
                continue;
            }
            challans.save(
                    TaxChallan.imported(
                            c.cin(),
                            c.bsr(),
                            c.date(),
                            c.amount(),
                            c.reference(),
                            finYear,
                            c.period(),
                            unit.getId()));
            imported++;
        }
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "tax_challan",
                                unit.getCode() + "|" + finYear,
                                "import",
                                null,
                                imported + " imported, " + duplicates.size() + " already held",
                                "challan file")
                        .module(SCREEN));
        return new Import(imported, List.copyOf(duplicates));
    }

    /** One challan keyed by the Payroll Maker (SRS C-11 field table l.2625, "User Input"). */
    public record ChallanEntry(
            String cin,
            String bsr,
            LocalDate date,
            BigDecimal amount,
            String reference,
            String period) {}

    /**
     * SRS C-11 field table (l.2625): "Challan File Import / User Input" - the Payroll Maker keys a
     * challan the file did not carry. It takes exactly the checks of a file line (SRS-7-21; CIN and
     * BSR letters and digits, a date and period of the year, an amount above zero) and is refused
     * when the same challan (CIN, BSR, date) is already held; it is mapped to the deductees at
     * generation and reconciled to the remittance at validation, as an imported one.
     */
    @Transactional
    public ChallanView enterChallan(AccessContext context, String finYear, ChallanEntry e) {
        String line =
                String.join(
                        ",",
                        nz(e.cin()),
                        nz(e.bsr()),
                        e.date() == null ? "" : e.date().toString(),
                        e.amount() == null ? "" : e.amount().toPlainString(),
                        nz(e.reference()),
                        nz(e.period()));
        Import done = importChallans(context, finYear, line.getBytes(StandardCharsets.US_ASCII));
        if (done.imported() == 0) {
            throw new FciException(
                    TaxErrorCode.CHALLAN_FILE,
                    new Object[] {1, "the challan is already held"},
                    List.of(),
                    null);
        }
        StatutoryRules.ChallanLine c = StatutoryRules.parseChallan(1, line, finYear);
        TaxChallan saved =
                challans.findByCinNumberAndBsrCodeAndChallanDate(c.cin(), c.bsr(), c.date())
                        .orElseThrow();
        return new ChallanView(
                saved.getChallanId(),
                saved.getCinNumber(),
                saved.getBsrCode(),
                saved.getChallanDate(),
                saved.getChallanAmount(),
                saved.getPaymentReference(),
                saved.getPeriodCode());
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }

    // ---------------------------------------------------------------- generate

    /** A return opened for generation: its id, the quarter's months, its end and its challan. */
    public record Opened(
            Long returnId,
            Long unitId,
            String finYear,
            String quarter,
            List<String> months,
            LocalDate quarterEnd,
            Long challanId) {}

    /**
     * SRS-7-7: the return of a locked quarter of the user's paying unit under the Active M-17
     * definition. {@code corrects}: the released or filed return a correction is made against; null
     * for the original. Deductees are the members with TDS in the quarter (earnings port). C-11
     * alternate flow: once the underlying data is corrected the Payroll Maker regenerates the
     * return as a routine function, in place (one row per quarter, DDL uk_statutory_return), while
     * it is neither released nor waiting for the Checker. The whole paying unit also runs as the
     * background job TAX_24Q_RETURN (SRS-NF-2, SRS-NF-7), on the same steps.
     */
    @Transactional
    public ReturnView generate(
            AccessContext context, String finYear, String quarter, Long corrects) {
        policy.require(context, SCREEN, "CREATE");
        OrgLocation unit = units.unitOf(context);
        if (corrects != null) {
            requireUnit(context, found(corrects));
        }
        Opened o = openReturn(context.loginId(), unit.getId(), finYear, quarter, corrects, false);
        TaxComputationService.Run rates = computation.ratesOn(o.quarterEnd());
        batch.begin();
        int done = 0;
        for (EmployeeDirectory.Member m :
                employees.members(units.membersOf(context, unit)).values()) {
            batch.checkpoint(done++);
            deducteeOf(m, o, rates, existingRow(o.returnId(), m.id())).ifPresent(deductees::save);
        }
        return finishReturn(context.loginId(), o.returnId());
    }

    /**
     * Opens the return of a locked quarter of {@code unitId} for generation: an original or a
     * correction (of a released or filed original of the same year and quarter), created, or
     * regenerated in place while it is neither released nor waiting for the Checker. The rows of an
     * earlier generation are emptied (never deleted); the generation writes them again. For the
     * background job ({@code background}) the return stays DRAFT until {@link #finishReturn}.
     */
    @Transactional
    public Opened openReturn(
            String actor,
            Long unitId,
            String finYear,
            String quarter,
            Long corrects,
            boolean background) {
        List<String> months = StatutoryRules.quarterMonths(finYear, quarter);
        StatutoryRules.requireLocked(
                months,
                m -> payroll.cycle(unitId, m).map(PayrollRecords.Cycle::isLocked).orElse(false));
        List<StatutoryReturn> year =
                returns.findByOrgUnitIdAndFinYearOrderByReturnIdDesc(unitId, finYear);
        String type = ORIGINAL;
        Optional<StatutoryReturn> held;
        if (corrects == null) {
            held =
                    year.stream()
                            .filter(r -> !r.isCorrection() && quarter.equals(r.getPeriodCode()))
                            .findFirst();
        } else {
            StatutoryReturn original = found(corrects);
            if (!unitId.equals(original.getOrgUnitId())) {
                throw new FciException(CommonErrorCode.NOT_FOUND);
            }
            if (original.isCorrection()
                    || !original.getFinYear().equals(finYear)
                    || !original.getPeriodCode().equals(quarter)
                    || (original.status() != Status.RELEASED
                            && original.status() != Status.FILED)) {
                throw state("not a released or filed original of " + finYear + " " + quarter);
            }
            held = year.stream().filter(x -> corrects.equals(x.getCorrectsReturnId())).findFirst();
            type = CORRECTION;
        }
        if (held.isPresent() && !held.get().isRegenerable()) {
            // A released or filed return is corrected, never generated again; DDL
            // uk_statutory_return holds one correction per corrected return (Q-08-9).
            throw state(
                    held.get().status().name()
                            + ": return "
                            + held.get().getReturnId()
                            + (corrects == null
                                    ? " is corrected, not generated again"
                                    : " already corrects return " + corrects));
        }
        held.ifPresent(this::requireNotPending);
        LocalDate quarterEnd =
                YearMonth.parse(
                                months.get(2),
                                java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))
                        .atEndOfMonth();
        OutputDefinitionService.Governing g = outputs.requireGoverning(type, quarterEnd);
        StatutoryReturn r;
        if (held.isPresent()) {
            r = locked(held.get().getReturnId());
            r.regenerated(g.version().getReportVersionId(), clock.instant());
            returns.save(r);
            deductees.emptyAll(r.getReturnId());
            record(actor, r, held.get().status(), Status.GENERATED, "regenerating " + type, null);
        } else {
            r =
                    StatutoryReturn.generated(
                            type,
                            finYear,
                            quarter,
                            unitId,
                            g.version().getReportVersionId(),
                            corrects,
                            clock.instant());
        }
        if (background) {
            r.drafting();
        }
        r = returns.save(r);
        Long challan =
                challans.findByOrgUnitIdAndFinYearOrderByChallanDate(unitId, finYear).stream()
                        .filter(
                                c ->
                                        quarter.equals(c.getPeriodCode())
                                                || months.contains(c.getPeriodCode()))
                        .map(TaxChallan::getChallanId)
                        .findFirst()
                        .orElse(null);
        return new Opened(r.getReturnId(), unitId, finYear, quarter, months, quarterEnd, challan);
    }

    /** The deductee row a member already has in the return, or null. */
    public ReturnDeductee existingRow(Long returnId, Long employeeId) {
        return deductees.findByReturnIdAndEmployeeId(returnId, employeeId).orElse(null);
    }

    /**
     * The deductee row of one member of an opened return (SRS-7-7, SRS-7-13): the quarter's locked
     * months of the engines' year, the PAN as HRMS holds it, the higher rate when no valid PAN.
     * Empty when the member had no TDS in the quarter. Nothing is saved here.
     *
     * @param existing the member's row of an earlier generation, or null
     */
    public Optional<ReturnDeductee> deducteeOf(
            EmployeeDirectory.Member m,
            Opened o,
            TaxComputationService.Run rates,
            ReturnDeductee existing) {
        Optional<TaxableEarnings.Year> y = earnings.year(m.id(), o.finYear());
        if (y.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (TaxableEarnings.Month month : y.get().months()) {
            if (o.months().contains(month.yymm()) && month.actual()) {
                paid = paid.add(month.taxableGross());
                tax = tax.add(month.tdsDeducted());
            }
        }
        if (tax.signum() == 0) {
            return Optional.empty();
        }
        PanStatus pan = employees.panStatus(m.id()).orElse(PanStatus.MISSING);
        boolean higher =
                computation
                        .forecast(m, o.finYear(), o.quarterEnd(), rates)
                        .result()
                        .map(x -> x.higherRateApplied())
                        .orElse(false);
        String panNumber = employees.panForStatutoryOutput(m.id()).orElse(null);
        if (existing == null) {
            return Optional.of(
                    ReturnDeductee.of(
                            o.returnId(),
                            m.id(),
                            panNumber,
                            pan == PanStatus.VALID,
                            higher,
                            paid,
                            tax,
                            o.challanId()));
        }
        existing.refigured(panNumber, pan == PanStatus.VALID, higher, paid, tax, o.challanId());
        return Optional.of(existing);
    }

    /** The totals of a generated return from its rows, and the audit record of the generation. */
    @Transactional
    public ReturnView finishReturn(String actor, Long returnId) {
        // The rows refigured in place are still pending when queries do not flush (BatchScope).
        batch.flush();
        StatutoryReturn r = locked(returnId);
        r.generationFinished();
        BigDecimal total = deductees.totalInReturn(returnId);
        r.totals(deductees.countInReturn(returnId), total == null ? BigDecimal.ZERO : total);
        returns.save(r);
        record(
                actor,
                r,
                null,
                Status.GENERATED,
                r.getReturnType()
                        + " "
                        + r.getFinYear()
                        + " "
                        + r.getPeriodCode()
                        + ": "
                        + r.getDeducteeCount()
                        + " deductees",
                null);
        return view(r);
    }

    /** Not regenerated while its release waits for the Checker. */
    private void requireNotPending(StatutoryReturn r) {
        String key = String.valueOf(r.getReturnId());
        statuses.pending(ENTITY).stream()
                .filter(p -> key.equals(p.entityKey()))
                .findFirst()
                .ifPresent(
                        p -> {
                            throw new FciException(
                                    ApiErrorCode.REFERENCE_CHANGE_PENDING,
                                    new Object[] {"return " + key, p.approvalId()},
                                    List.of(),
                                    null);
                        });
    }

    private static FciException state(String what) {
        return new FciException(
                TaxErrorCode.DECLARATION_STATE, new Object[] {what}, List.of(), null);
    }

    // ---------------------------------------------------------------- validate

    /**
     * SRS-7-8: the itemised checks and the prescribed utility. A missing or invalid PAN is raised
     * on X-05 before the return can be validated; unmapped or unremitted challans, control totals
     * that do not reconcile and a utility not configured each fail it.
     */
    @Transactional
    public ReturnView validate(AccessContext context, Long id) {
        policy.require(context, SCREEN, "SUBMIT");
        StatutoryReturn r = locked(id);
        requireUnit(context, r);
        if (r.status() != Status.GENERATED) {
            throw state(r);
        }
        List<String> rejections = new ArrayList<>();
        List<ReturnDeductee> rows =
                deductees.findByReturnIdOrderByDeducteeId(r.getReturnId()).stream()
                        .filter(ReturnDeductee::isInReturn)
                        .toList();
        // Wave C review: the deductees' regimes are read once, in chunks, not one by one.
        java.util.Set<Long> frozen =
                computation.frozenRegimes(
                        rows.stream().map(ReturnDeductee::getEmployeeId).toList(), r.getFinYear());
        for (ReturnDeductee d : rows) {
            PanStatus pan = employees.panStatus(d.getEmployeeId()).orElse(PanStatus.MISSING);
            d.panRefreshed(
                    employees.panForStatutoryOutput(d.getEmployeeId()).orElse(null),
                    pan == PanStatus.VALID);
            deductees.save(d);
            if (!d.isPanValid()) {
                employees.member(d.getEmployeeId()).ifPresent(m -> raisePan(context.loginId(), m));
                rejections.add(
                        "PAN missing or invalid for member "
                                + employees.eisOf(d.getEmployeeId()).orElse("?"));
            }
            if (d.getChallanId() == null) {
                rejections.add(
                        "no challan mapped for member "
                                + employees.eisOf(d.getEmployeeId()).orElse("?"));
            }
            // FRS V0.5 §14 Preconditions (l.5545; US-GAP-196): the regime is frozen first.
            if (!frozen.contains(d.getEmployeeId())) {
                rejections.add(
                        "no frozen tax regime for "
                                + r.getFinYear()
                                + " for member "
                                + employees.eisOf(d.getEmployeeId()).orElse("?"));
            }
        }
        if ("Q4".equals(r.getPeriodCode())) {
            rejections.addAll(yearEndMismatches(context, r));
        }
        List<String> months = StatutoryRules.quarterMonths(r.getFinYear(), r.getPeriodCode());
        List<TaxChallan> quarterChallans =
                challans
                        .findByOrgUnitIdAndFinYearOrderByChallanDate(
                                r.getOrgUnitId(), r.getFinYear())
                        .stream()
                        .filter(
                                c ->
                                        r.getPeriodCode().equals(c.getPeriodCode())
                                                || months.contains(c.getPeriodCode()))
                        .toList();
        BigDecimal challanTotal = BigDecimal.ZERO;
        for (TaxChallan c : quarterChallans) {
            Optional<Boolean> remitted =
                    ledger.remitted(c.getCinNumber(), c.getBsrCode(), c.getChallanDate());
            if (remitted.isEmpty()) {
                rejections.add(
                        "challan "
                                + c.getCinNumber()
                                + ": remittances are not available (posting interface not yet"
                                + " connected)");
            } else if (!remitted.get()) {
                rejections.add("challan " + c.getCinNumber() + " maps to no posted remittance");
            } else {
                challanTotal = challanTotal.add(c.getChallanAmount());
            }
        }
        // The tolerance of the version the return was generated under, not of today's version.
        BigDecimal tolerance =
                outputs.controlsOf(r.getDefinitionVersionId()).stream()
                        .filter(OutputControlTotal::isReleaseGate)
                        .map(OutputControlTotal::getToleranceAmount)
                        .min(BigDecimal::compareTo)
                        .orElse(BigDecimal.ZERO);
        rejections.addAll(
                StatutoryRules.differences(
                        r.getTotalAmount(),
                        ledger.postedTds(r.getOrgUnitId(), months),
                        challanTotal,
                        tolerance));
        Optional<ReturnValidator.Verdict> verdict = validator.validate(r.getReturnId());
        if (verdict.isEmpty()) {
            rejections.add("the prescribed validation utility is not configured");
        } else if (!verdict.get().passed()) {
            rejections.addAll(verdict.get().rejections());
        }
        boolean passed = rejections.isEmpty();
        r.validated(
                passed,
                verdict.map(ReturnValidator.Verdict::utility).orElse(null),
                passed ? "PASS" : String.join("\n", rejections));
        returns.save(r);
        record(
                context.loginId(),
                r,
                Status.GENERATED,
                r.status(),
                passed ? "validated" : rejections.size() + " rejections",
                null);
        return view(r);
    }

    /**
     * SRS-7-19, BR-TAX-005: before the fourth-quarter return is finalised, the actual tax of every
     * member of the paying unit whose year is final is reconciled to the TDS deducted; each
     * mismatch is raised on X-05 for correction and fails the validation.
     */
    private List<String> yearEndMismatches(AccessContext context, StatutoryReturn r) {
        OrgLocation unit = units.unitOf(context);
        LocalDate yearEnd =
                LocalDate.of(Integer.parseInt(r.getFinYear().substring(0, 4)) + 1, 3, 31);
        TaxComputationService.Run rates = computation.ratesOn(yearEnd);
        List<String> out = new ArrayList<>();
        batch.begin();
        int done = 0;
        for (EmployeeDirectory.Member m :
                employees.members(units.membersOf(context, unit)).values()) {
            batch.checkpoint(done++);
            if (earnings.year(m.id(), r.getFinYear()).isEmpty()) {
                continue;
            }
            TaxComputationService.Forecast f =
                    computation.forecast(m, r.getFinYear(), yearEnd, rates);
            Optional<BigDecimal> mismatch = TaxComputationService.mismatchOf(f);
            if (mismatch.isPresent()) {
                computation.raiseYearEndMismatch(
                        context.loginId(), m, r.getFinYear(), mismatch.get(), SCREEN);
                out.add(
                        "year-end difference of Rs "
                                + mismatch.get().toPlainString()
                                + " for member "
                                + m.eis()
                                + " (SRS-7-19)");
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- release and filing

    /** C-11: a validated return to the Payroll Checker (route C11_RELEASE). */
    @Transactional
    public Long requestRelease(AccessContext context, Long id) {
        policy.require(context, SCREEN, "SUBMIT");
        StatutoryReturn r = locked(id);
        OrgLocation unit = requireUnit(context, r);
        if (r.status() != Status.VALIDATED) {
            throw state(r);
        }
        String key = String.valueOf(r.getReturnId());
        statuses.pending(ENTITY).stream()
                .filter(p -> key.equals(p.entityKey()))
                .findFirst()
                .ifPresent(
                        p -> {
                            throw new FciException(
                                    ApiErrorCode.REFERENCE_CHANGE_PENDING,
                                    new Object[] {"return " + key, p.approvalId()},
                                    List.of(),
                                    null);
                        });
        return approvals
                .submit(
                        context,
                        new Submission(
                                        ROUTE,
                                        SCREEN,
                                        "SUBMIT",
                                        ENTITY,
                                        key,
                                        ActionType.UPDATE,
                                        unit.getId(),
                                        null,
                                        LocalDate.now(clock.withZone(INDIA)),
                                        r.getReturnType()
                                                + " "
                                                + r.getFinYear()
                                                + " "
                                                + r.getPeriodCode())
                                .withImpact(r.getTotalAmount()),
                        null)
                .id();
    }

    /** The Checker's approval: a still-validated return is released. */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!SCREEN.equals(decision.moduleCode())
                || !decision.concerns(ENTITY)
                || !decision.approved()) {
            return;
        }
        StatutoryReturn r = locked(Long.valueOf(decision.entityKey()));
        if (r.status() != Status.VALIDATED) {
            throw state(r);
        }
        r.released(decision.checkerLoginId(), clock.instant());
        returns.save(r);
        record(
                decision.checkerLoginId(),
                r,
                Status.VALIDATED,
                Status.RELEASED,
                decision.reason() == null ? "released" : decision.reason(),
                decision.approvalId());
    }

    /** SRS-7-31: the filing by the authorised signatory, recorded by the Payroll Maker. */
    @Transactional
    public ReturnView recordFiling(AccessContext context, Long id, Filing filing) {
        policy.require(context, SCREEN, "EDIT");
        StatutoryReturn r = locked(id);
        requireUnit(context, r);
        if (r.status() != Status.RELEASED) {
            throw state(r);
        }
        if (filing == null
                || filing.filingDate() == null
                || filing.filingDate().isAfter(LocalDate.now(clock.withZone(INDIA)))
                || blank(filing.signatoryName())
                || blank(filing.signatoryDesignation())) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        Long evidence = null;
        if (filing.content() != null) {
            evidence =
                    store.attach(
                                    context.loginId(),
                                    new DocumentStore.NewAttachment(
                                            ENTITY,
                                            String.valueOf(r.getReturnId()),
                                            filing.evidenceType(),
                                            blank(filing.evidenceReference())
                                                    ? filing.token()
                                                    : filing.evidenceReference().strip(),
                                            filing.evidenceDate(),
                                            filing.evidenceIssuer(),
                                            filing.fileName(),
                                            filing.content(),
                                            false))
                            .id();
        }
        filings.save(
                FilingRecord.of(
                        r.getReturnId(),
                        filing.filingDate(),
                        filing.signatoryName().strip(),
                        filing.signatoryDesignation().strip(),
                        blank(filing.token()) ? null : filing.token().strip(),
                        evidence,
                        context.loginId(),
                        clock.instant()));
        r.filed();
        returns.save(r);
        record(
                context.loginId(),
                r,
                Status.RELEASED,
                Status.FILED,
                "filed " + filing.filingDate(),
                null);
        return view(r);
    }

    /** The filing evidence of a return, to C-11 in the unit (the read is logged). */
    @Transactional
    public DocumentStore.Content evidence(
            AccessContext context, String sourceIp, Long id, Long evidenceId) {
        policy.require(context, SCREEN, "VIEW");
        StatutoryReturn r = found(id);
        requireUnit(context, r);
        boolean listed =
                filings.findByReturnIdOrderByFilingId(id).stream()
                        .anyMatch(f -> evidenceId.equals(f.getEvidenceAttachmentId()));
        if (!listed) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return store.read(context.loginId(), sourceIp, ENTITY, String.valueOf(id), evidenceId);
    }

    // ---------------------------------------------------------------- helpers

    private void raisePan(String actor, EmployeeDirectory.Member m) {
        worklist.raise(
                actor,
                new ExceptionWorklist.NewEntry(
                        null,
                        m.id(),
                        SCREEN,
                        "PAN_MISSING",
                        "BLOCKING",
                        null,
                        null,
                        "employee",
                        "pan_number",
                        "HRMS",
                        "I-21",
                        "PAN missing or invalid: Form 24Q cannot be validated (SRS-7-13)"));
    }

    private static boolean isHeader(String line) {
        return CHALLAN_HEADER.equals(line.replace(" ", "").toLowerCase(java.util.Locale.ROOT));
    }

    private OrgLocation requireUnit(AccessContext context, StatutoryReturn r) {
        OrgLocation unit = units.unitOf(context);
        if (!unit.getId().equals(r.getOrgUnitId())) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return unit;
    }

    private StatutoryReturn found(Long id) {
        return returns.findById(id).orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private StatutoryReturn locked(Long id) {
        return returns.lockById(id).orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private void record(
            String actor,
            StatutoryReturn r,
            Status before,
            Status after,
            String reason,
            Long approval) {
        audit.record(
                actor,
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(r.getReturnId()),
                                "return_status",
                                before == null ? null : before.name(),
                                after.name(),
                                reason)
                        .approval(approval)
                        .module(SCREEN));
    }

    private static FciException state(StatutoryReturn r) {
        return new FciException(
                TaxErrorCode.DECLARATION_STATE, new Object[] {r.status().name()}, List.of(), null);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private ReturnView view(StatutoryReturn r) {
        return view(r, filings.findByReturnIdOrderByFilingId(r.getReturnId()));
    }

    private ReturnView view(StatutoryReturn r, List<FilingRecord> filed) {
        return new ReturnView(
                r.getReturnId(),
                r.getReturnType(),
                r.getFinYear(),
                r.getPeriodCode(),
                r.getDeducteeCount() == null ? 0 : r.getDeducteeCount(),
                r.getTotalAmount(),
                r.status().name(),
                r.isValidated(),
                r.getValidationUtility(),
                r.getValidationResult() == null || "PASS".equals(r.getValidationResult())
                        ? List.of()
                        : List.of(r.getValidationResult().split("\n")),
                r.isCorrection(),
                r.getCorrectsReturnId(),
                r.getGeneratedAt(),
                r.getReleasedBy(),
                filed.stream()
                        .map(
                                f ->
                                        new FilingView(
                                                f.getFilingDate(),
                                                f.getSignatoryName(),
                                                f.getSignatoryDesignation(),
                                                f.getAcknowledgementToken(),
                                                f.getEvidenceAttachmentId()))
                        .toList());
    }
}
