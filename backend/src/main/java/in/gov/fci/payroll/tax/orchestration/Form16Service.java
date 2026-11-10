package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.FieldChanges;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
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
import in.gov.fci.payroll.tax.business.SavingsRules;
import in.gov.fci.payroll.tax.business.StatutoryRules;
import in.gov.fci.payroll.tax.business.TaxCalculator;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.model.Form16Certificate;
import in.gov.fci.payroll.tax.business.model.Form16Certificate.Status;
import in.gov.fci.payroll.tax.business.model.OutputControlTotal;
import in.gov.fci.payroll.tax.business.model.StatutoryReturn;
import in.gov.fci.payroll.tax.business.model.TaxChallan;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * C-10 Annual Tax Certificate - Form 16 (SRS-7-5, SRS-7-9, SRS-7-19, SRS-7-21, SRS-7-23; BR-TAX-010
 * … 012; plan 08 phase 08g). Only the Payroll Maker of the paying unit generates Part B, uploads
 * Part A and reissues; the Payroll Checker releases (route C10_RELEASE). Part B is generated only
 * for a fully locked year under an Active M-17 definition, from the actual year (earnings port);
 * release follows the reconciliation to the posted TDS and the remitted challans. A released
 * certificate is never changed: it is reissued against a correction return. The rendering of the
 * prescribed file belongs to the output engine (module 15); the certificate holds its figures and
 * the governing version.
 */
@Service
@Loggable
public class Form16Service {

    public static final String SCREEN = "C-10";
    public static final String MEMBER_SCREEN = "E-01";
    public static final String ENTITY = "form16";
    public static final String ROUTE = "C10_RELEASE";
    static final String OUTPUT = "FORM16_PART_B";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** One certificate as C-10 and E-01 show it; PAN never (SRS-7-23). */
    public record CertificateView(
            Long id,
            Long employeeId,
            String eis,
            String name,
            String finYear,
            BigDecimal grossSalary,
            BigDecimal taxableIncome,
            BigDecimal taxDeducted,
            String partAStatus,
            String status,
            Instant partBGeneratedAt,
            Instant releasedAt,
            Long partADocumentId,
            // SRS l.2589 / SRS-7-23 (C-10 PAN, HRMS): masked, with its state; on the register only.
            String panMasked,
            String panStatus) {}

    /** What a generation did: certificates written and members skipped with the reason. */
    public record Generation(int generated, List<Skipped> skipped) {}

    public record Skipped(String eis, String reason) {}

    /** The release proposal the Checker decides on. */
    public record ReleaseProposal(
            Long orgUnitId, String finYear, List<Long> certificateIds, String proposedBy) {}

    /** The Part A document uploaded by the Payroll Maker. */
    public record PartA(
            String documentTypeCode,
            String reference,
            LocalDate documentDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    private final TaxRepositories.Form16s certificates;
    private final TaxRepositories.Returns returns;
    private final TaxRepositories.Challans challans;
    private final TaxComputationService computation;
    private final OutputDefinitionService outputs;
    private final in.gov.fci.payroll.tax.business.PostingLedger ledger;
    private final EmployeeDirectory employees;
    private final OrgDirectory org;
    private final PayrollRecords payroll;
    private final DocumentStore store;
    private final MakerChecker approvals;
    private final ApprovalStatuses statuses;
    private final Authorization policy;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final BatchScope batch;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public Form16Service(
            TaxRepositories.Form16s certificates,
            TaxRepositories.Returns returns,
            TaxRepositories.Challans challans,
            TaxComputationService computation,
            OutputDefinitionService outputs,
            in.gov.fci.payroll.tax.business.PostingLedger ledger,
            EmployeeDirectory employees,
            OrgDirectory org,
            PayrollRecords payroll,
            DocumentStore store,
            MakerChecker approvals,
            ApprovalStatuses statuses,
            Authorization policy,
            AuditService audit,
            ObjectMapper json,
            Clock clock,
            BatchScope batch) {
        this.batch = batch;
        this.certificates = certificates;
        this.returns = returns;
        this.challans = challans;
        this.computation = computation;
        this.outputs = outputs;
        this.ledger = ledger;
        this.employees = employees;
        this.org = org;
        this.payroll = payroll;
        this.store = store;
        this.approvals = approvals;
        this.statuses = statuses;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reads

    /**
     * C-10: one page of the certificates of a year in the user's offices, by status (null: all).
     */
    @Transactional(readOnly = true)
    public Paged<CertificateView> register(
            AccessContext context, String finYear, String status, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        if (status != null) {
            Status.valueOf(status);
        }
        if (context.dataScope().isEmpty()) {
            return new Paged<>(List.of(), page.page(), page.size(), 0);
        }
        Paged<Form16Certificate> rows =
                Paged.of(
                        certificates.inScope(
                                finYear, context.dataScope(), status, page.pageable()));
        List<Long> ids = rows.items().stream().map(Form16Certificate::getEmployeeId).toList();
        Map<Long, EmployeeDirectory.Member> members = employees.members(ids);
        Map<Long, EmployeeDirectory.MaskedPan> pans = employees.maskedPans(ids);
        return rows.map(
                f ->
                        view(
                                f,
                                members.get(f.getEmployeeId()),
                                pans.getOrDefault(
                                        f.getEmployeeId(), EmployeeDirectory.maskedPanOf(null))));
    }

    /** E-01: the member's released certificates (SRS-7-9: locked periods only, merged). */
    @Transactional(readOnly = true)
    public List<CertificateView> mine(AccessContext context) {
        policy.require(context, MEMBER_SCREEN, "SELF");
        if (context.employeeId() == null) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        EmployeeDirectory.Member m = employees.member(context.employeeId()).orElse(null);
        return certificates
                .findByEmployeeIdAndReleaseStatusOrderByFinYearDesc(
                        context.employeeId(), Status.RELEASED.name())
                .stream()
                .map(f -> view(f, m))
                .toList();
    }

    // ---------------------------------------------------------------- generate

    /**
     * C-10: Part B of every member of the user's paying unit for a fully locked year, under the
     * Active FORM16_PART_B definition; a member whose actual year is not available, or whose
     * certificate is already released, is listed with the reason.
     */
    @Transactional
    public Generation generate(AccessContext context, String finYear) {
        return generate(context, finYear, null);
    }

    /**
     * C-10: Part B for the member population the Payroll Maker selects ({@code eis}: the members by
     * EIS; null or empty: every member of the paying unit in the user's offices). A member whose
     * actual tax does not match the TDS deducted is not certified: the difference is raised on X-05
     * for correction first (SRS-7-19, BR-TAX-005).
     */
    @Transactional
    public Generation generate(AccessContext context, String finYear, Set<String> eis) {
        policy.require(context, SCREEN, "CREATE");
        OrgLocation unit = unitOf(context);
        StatutoryRules.requireLocked(SavingsRules.monthsOf(finYear), m -> locked(unit.getId(), m));
        LocalDate yearEnd = LocalDate.of(Integer.parseInt(finYear.substring(0, 4)) + 1, 3, 31);
        OutputDefinitionService.Governing g = outputs.requireGoverning(OUTPUT, yearEnd);
        Instant now = clock.instant();
        int generated = 0;
        List<Skipped> skipped = new ArrayList<>();
        Set<Long> members = membersOf(context, unit);
        if (eis != null && !eis.isEmpty()) {
            Set<Long> chosen =
                    employees.members(members).values().stream()
                            .filter(m -> eis.contains(m.eis()))
                            .map(EmployeeDirectory.Member::id)
                            .collect(Collectors.toSet());
            if (chosen.size() != eis.size()) {
                // A member outside the paying unit or the user's offices reads as unknown.
                throw new FciException(CommonErrorCode.NOT_FOUND);
            }
            members = chosen;
        }
        Map<Long, Form16Certificate> existing =
                certificates.findByFinYearAndEmployeeIdIn(finYear, members).stream()
                        .collect(Collectors.toMap(Form16Certificate::getEmployeeId, f -> f));
        // Every member on one snapshot of the year-end rules.
        TaxComputationService.Run rates = computation.ratesOn(yearEnd);
        batch.begin();
        int done = 0;
        for (EmployeeDirectory.Member m : employees.members(members).values()) {
            batch.checkpoint(done++);
            PartB p =
                    partBOf(
                            context.loginId(),
                            m,
                            finYear,
                            yearEnd,
                            g.version().getReportVersionId(),
                            rates,
                            existing.get(m.id()),
                            now);
            if (p.certificate() == null) {
                skipped.add(p.skipped());
            } else {
                certificates.save(p.certificate());
                generated++;
            }
        }
        recordPartBRun(
                context.loginId(),
                unit.getCode(),
                finYear,
                generated,
                skipped.size(),
                g.version().getVersionNo());
        return new Generation(generated, List.copyOf(skipped));
    }

    /**
     * The audit record of a Part B run, from the screen or the background job: how many
     * certificates were generated and how many members were passed over, under which version.
     */
    @Transactional
    public void recordPartBRun(
            String actor,
            String unitCode,
            String finYear,
            long generated,
            long skipped,
            int versionNo) {
        audit.record(
                actor,
                AuditService.Entry.change(
                                ENTITY,
                                unitCode + "|" + finYear,
                                "part_b",
                                null,
                                generated
                                        + " generated, "
                                        + skipped
                                        + " skipped (v"
                                        + versionNo
                                        + ")",
                                "Form 16 Part B")
                        .module(SCREEN));
    }

    /** What Part B does with one member: the certificate to write, or why there is none. */
    public record PartB(Form16Certificate certificate, Skipped skipped) {}

    /** A Part B run checked once: the year locked, the governing version and the rules' day. */
    public record Prepared(LocalDate yearEnd, Long versionId, int versionNo) {}

    /**
     * C-10 / TAX_FORM16_PART_B: the year of the paying unit is locked and a FORM16_PART_B version
     * governs its last day (SRS-7-8, SRS M-17); otherwise nothing is generated.
     */
    @Transactional(readOnly = true)
    public Prepared preparePartB(Long unitId, String finYear) {
        StatutoryRules.requireLocked(SavingsRules.monthsOf(finYear), m -> locked(unitId, m));
        LocalDate yearEnd = LocalDate.of(Integer.parseInt(finYear.substring(0, 4)) + 1, 3, 31);
        OutputDefinitionService.Governing g = outputs.requireGoverning(OUTPUT, yearEnd);
        return new Prepared(yearEnd, g.version().getReportVersionId(), g.version().getVersionNo());
    }

    /**
     * Part B of one member on the run's rules and the governing version (SRS-7-5, SRS-7-19): the
     * screen and the background job compute it the same way. A released certificate is not changed;
     * a member without the year's earnings, a year not final or a year-end mismatch (raised on X-05
     * by {@code actor}) gives no certificate. Nothing is saved here.
     *
     * @param existing the member's certificate of the year, or null
     */
    public PartB partBOf(
            String actor,
            EmployeeDirectory.Member m,
            String finYear,
            LocalDate yearEnd,
            Long versionId,
            TaxComputationService.Run rates,
            Form16Certificate existing,
            Instant now) {
        if (existing != null && existing.status() == Status.RELEASED) {
            return skip(m, "released: reissue against a correction return");
        }
        if (!computation.hasYear(m.id(), finYear)) {
            // No earnings from payroll (labour without TDS, a member not paid in the year):
            // nothing to certify, and no forecast is read for it.
            return skip(m, TaxErrorCode.EARNINGS_NOT_AVAILABLE.code());
        }
        TaxComputationService.Forecast actual = computation.forecast(m, finYear, yearEnd, rates);
        if (!"ACTUAL".equals(actual.view()) || actual.result().isEmpty()) {
            return skip(
                    m,
                    actual.reasons().isEmpty()
                            ? "the year is not final"
                            : String.join(", ", actual.reasons()));
        }
        Optional<BigDecimal> mismatch = TaxComputationService.mismatchOf(actual);
        if (mismatch.isPresent()) {
            computation.raiseYearEndMismatch(actor, m, finYear, mismatch.get(), SCREEN);
            return skip(
                    m,
                    "actual tax differs from the TDS deducted by Rs "
                            + mismatch.get().toPlainString()
                            + " (SRS-7-19): correct it first");
        }
        TaxCalculator.Result r = actual.result().get();
        BigDecimal gross = actual.earnings().get().gross().add(actual.inputs().totalOtherIncome());
        BigDecimal tds =
                actual.earnings().get().tdsDeducted().add(actual.inputs().otherSourceTds());
        if (existing == null) {
            return new PartB(
                    Form16Certificate.generated(
                            m.id(), finYear, gross, r.taxableIncome(), tds, versionId, now),
                    null);
        }
        existing.regenerate(gross, r.taxableIncome(), tds, versionId, now);
        return new PartB(existing, null);
    }

    private static PartB skip(EmployeeDirectory.Member m, String reason) {
        return new PartB(null, new Skipped(m.eis(), reason));
    }

    // ---------------------------------------------------------------- Part A

    /** SRS-7-9: the Part A of the tax department, uploaded by the Payroll Maker and merged. */
    @Transactional
    public CertificateView uploadPartA(AccessContext context, Long id, PartA partA) {
        policy.require(context, SCREEN, "EDIT");
        Form16Certificate f = locked(id);
        EmployeeDirectory.Member m = inScope(context, f, "EDIT");
        if (f.status() != Status.PENDING_PART_A && f.status() != Status.MERGED) {
            throw state(f);
        }
        if (partA == null || partA.content() == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        // R-PII: a Part A naming another employee's PAN is never merged into this certificate.
        requirePartAOf(f, partA.content());
        DocumentStore.AttachmentView doc =
                store.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                ENTITY,
                                String.valueOf(f.getForm16Id()),
                                partA.documentTypeCode(),
                                partA.reference(),
                                partA.documentDate(),
                                partA.issuingAuthority(),
                                partA.fileName(),
                                partA.content(),
                                true));
        String before = f.status().name();
        f.partAMerged(doc.id(), clock.instant());
        certificates.save(f);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(f.getForm16Id()),
                                "release_status",
                                before,
                                f.status().name(),
                                "Part A received and merged")
                        .module(SCREEN));
        return view(f, m);
    }

    // ---------------------------------------------------------------- release

    /**
     * C-10: the merged certificates of a year of the paying unit, to the Payroll Checker, once they
     * reconcile to the posted TDS and the remitted challans (BR-TAX-012).
     */
    @Transactional
    public Long requestRelease(AccessContext context, String finYear) {
        policy.require(context, SCREEN, "SUBMIT");
        OrgLocation unit = unitOf(context);
        List<Form16Certificate> merged =
                certificates
                        .findByFinYearAndEmployeeIdIn(finYear, membersOf(context, unit))
                        .stream()
                        .filter(f -> f.status() == Status.MERGED)
                        .toList();
        if (merged.isEmpty()) {
            throw new FciException(
                    TaxErrorCode.DECLARATION_STATE, new Object[] {"not merged"}, List.of(), null);
        }
        requireReconciled(unit.getId(), finYear, merged);
        String key = unit.getId() + "|" + finYear;
        statuses.pending(ENTITY).stream()
                .filter(p -> key.equals(p.entityKey()))
                .findFirst()
                .ifPresent(
                        p -> {
                            throw new FciException(
                                    ApiErrorCode.REFERENCE_CHANGE_PENDING,
                                    new Object[] {"Form 16 release " + finYear, p.approvalId()},
                                    List.of(),
                                    null);
                        });
        ReleaseProposal p =
                new ReleaseProposal(
                        unit.getId(),
                        finYear,
                        merged.stream().map(Form16Certificate::getForm16Id).toList(),
                        context.loginId());
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
                                        json.writeValueAsString(p),
                                        clock.instant().atZone(INDIA).toLocalDate(),
                                        merged.size() + " Form 16 certificates " + finYear)
                                .withImpact(
                                        merged.stream()
                                                .map(Form16Certificate::getTaxDeducted)
                                                .reduce(BigDecimal.ZERO, BigDecimal::add)),
                        null)
                .id();
    }

    /** The Checker's approval: reconciled again, then the certificates are released. */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!SCREEN.equals(decision.moduleCode())
                || !decision.concerns(ENTITY)
                || !decision.approved()) {
            return;
        }
        ReleaseProposal p = json.readValue(decision.payload(), ReleaseProposal.class);
        List<Form16Certificate> merged =
                p.certificateIds().stream()
                        .map(this::locked)
                        .filter(f -> f.status() == Status.MERGED)
                        .toList();
        requireReconciled(p.orgUnitId(), p.finYear(), merged);
        Instant now = clock.instant();
        for (Form16Certificate f : merged) {
            // OD-153: the certificate as released is rendered once and frozen; every later print
            // serves these bytes. A certificate without its governing version cannot be issued.
            Form16Pdf.Merged issued = null;
            if (issuedCopies != null) {
                if (f.getDefinitionVersionId() == null) {
                    throw state(f);
                }
                issued = rendered(f, decision.checkerLoginId(), null);
            }
            f.released(now);
            certificates.save(f);
            if (issued != null) {
                issuedCopies.freeze(
                        f.getForm16Id(),
                        f.getDefinitionVersionId(),
                        f.getFinYear(),
                        p.orgUnitId(),
                        decision.checkerLoginId(),
                        issued);
            }
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                p.orgUnitId() + "|" + p.finYear(),
                                "release_status",
                                Status.MERGED.name(),
                                Status.RELEASED.name() + " (" + merged.size() + ")",
                                decision.reason() == null ? "released" : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    // ---------------------------------------------------------------- reissue

    /**
     * An issued certificate is never changed: the Payroll Maker reissues it against a correction
     * return of the year (released or filed); Part B is regenerated and goes to release again.
     */
    @Transactional
    public CertificateView reissue(AccessContext context, Long id, String reason) {
        policy.require(context, SCREEN, "EDIT");
        if (reason == null || reason.isBlank()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        Form16Certificate f = locked(id);
        EmployeeDirectory.Member m = inScope(context, f, "EDIT");
        if (f.status() != Status.RELEASED) {
            throw state(f);
        }
        OrgLocation unit = unitOf(context);
        boolean corrected =
                returns
                        .findByOrgUnitIdAndFinYearOrderByReturnIdDesc(unit.getId(), f.getFinYear())
                        .stream()
                        .anyMatch(
                                r ->
                                        r.isCorrection()
                                                && (r.status() == StatutoryReturn.Status.RELEASED
                                                        || r.status()
                                                                == StatutoryReturn.Status.FILED));
        if (!corrected) {
            throw new FciException(
                    TaxErrorCode.DECLARATION_STATE,
                    new Object[] {"without a correction return"},
                    List.of(),
                    null);
        }
        LocalDate yearEnd =
                LocalDate.of(Integer.parseInt(f.getFinYear().substring(0, 4)) + 1, 3, 31);
        OutputDefinitionService.Governing g = outputs.requireGoverning(OUTPUT, yearEnd);
        TaxComputationService.Forecast actual = computation.forecast(m, f.getFinYear(), yearEnd);
        if (actual.result().isEmpty() || actual.earnings().isEmpty()) {
            throw new FciException(TaxErrorCode.EARNINGS_NOT_AVAILABLE);
        }
        Map<String, String> before = f.image();
        f.regenerate(
                actual.earnings().get().gross().add(actual.inputs().totalOtherIncome()),
                actual.result().get().taxableIncome(),
                actual.earnings().get().tdsDeducted().add(actual.inputs().otherSourceTds()),
                g.version().getReportVersionId(),
                clock.instant());
        certificates.save(f);
        audit.recordAll(
                context.loginId(),
                FieldChanges.between(
                                ENTITY,
                                String.valueOf(f.getForm16Id()),
                                before,
                                f.image(),
                                reason.strip())
                        .stream()
                        .map(e -> e.module(SCREEN))
                        .toList());
        return view(f, m);
    }

    // ---------------------------------------------------------------- documents

    /** The Part A of a certificate: to its member once released, or to C-10 in scope (logged). */
    @Transactional
    public DocumentStore.Content partA(AccessContext context, String sourceIp, Long id) {
        Form16Certificate f =
                certificates
                        .findById(id)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (f.getEmployeeId().equals(context.employeeId())) {
            policy.require(context, MEMBER_SCREEN, "SELF");
            if (f.status() != Status.RELEASED) {
                throw new FciException(CommonErrorCode.NOT_FOUND);
            }
        } else {
            policy.require(context, SCREEN, "VIEW");
            inScope(context, f, "VIEW");
        }
        if (f.getPartAAttachmentId() == null) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return store.read(
                context.loginId(),
                sourceIp,
                ENTITY,
                String.valueOf(f.getForm16Id()),
                f.getPartAAttachmentId());
    }

    /**
     * What the Part B PDF prints (OD-144, LIB-1; SRS-7-5, SRS-7-9, SRS-7-23, SRS-7-30): the
     * certificate, the employer office, the employee's PAN in full (SRS-7-23: "in full only on the
     * statutory certificate or return that prescribes it"; Form 16 prescribes the PAN of the
     * employee; PENDING F16-3) and the form's figures from the actual year.
     */
    public record PartBDocument(
            CertificateView certificate,
            String employer,
            @in.gov.fci.payroll.platform.logging.Sensitive String pan,
            Form16PartB.Sheet sheet) {
        @Override
        public String toString() {
            return "PartBDocument[" + certificate.id() + "]";
        }
    }

    /** A certificate as one PDF: Part A (when merged) first, then Part B (SRS-7-9). */
    public record CertificatePdf(String fileName, byte[] pdf, int pages, boolean partAIncluded) {}

    /** FY 2026-27 onward: the Income-tax Act 2025 certificate (PENDING F16-2). */
    static final int FIRST_YEAR_OF_NEW_ACT = 2026;

    /**
     * The Part B of a certificate for its PDF, under the same rule as {@link #partA}: the member's
     * own once released (E-01 SELF), any generated certificate of a member in the user's C-10 scope
     * (C-10 VIEW; another office's reads as not found). A certificate whose Part B is not generated
     * has nothing to print; a tax year under the Income-tax Act 2025 is refused (its form is not
     * notified in the sources, PENDING F16-2). The figures are recomputed from the actual year on
     * the rules of the year end, exactly as {@link #partBOf} generated them (deterministic,
     * BR-CYC-011).
     */
    @Transactional(readOnly = true)
    public PartBDocument partB(AccessContext context, Long id) {
        Form16Certificate f = readable(context, id);
        return document(f);
    }

    /**
     * The certificate as one PDF for download or delivery (OD-144, OD-148): the uploaded Part A
     * (TRACES) as the first pages once merged, then the generated Part B. Same access rule as
     * {@link #partB}; reading Part A is logged by the document store. A released certificate is
     * served as issued: the copy frozen at its release, byte for byte (OD-153); one released before
     * copies were kept is refused (TAX-409-NO-ISSUED-COPY), never recomputed; one not yet released
     * is rendered on demand.
     */
    @Transactional(readOnly = true)
    public CertificatePdf certificatePdf(AccessContext context, String sourceIp, Long id) {
        Form16Certificate f = readable(context, id);
        Form16Pdf.Merged merged =
                issuedCopy(f, context.loginId(), sourceIp)
                        .orElseGet(() -> rendered(f, context.loginId(), sourceIp));
        return new CertificatePdf(
                "form16-" + f.getFinYear(), merged.pdf(), merged.pages(), merged.partAIncluded());
    }

    /**
     * OD-153: the frozen copy of a released certificate (refused when none is on file); empty for a
     * certificate not released.
     */
    private Optional<Form16Pdf.Merged> issuedCopy(
            Form16Certificate f, String actor, String sourceIp) {
        if (issuedCopies == null || f.status() != Status.RELEASED) {
            return Optional.empty();
        }
        // OD-153 ("Disable PDF with reason"): a released certificate with no copy on file is
        // refused, never recomputed.
        return Optional.of(
                issuedCopies
                        .issued(f.getForm16Id(), actor, sourceIp)
                        .orElseThrow(() -> new FciException(TaxErrorCode.NO_ISSUED_COPY)));
    }

    /**
     * The certificate rendered now (OD-144): Part A (as received, once merged) first, then Part B
     * from the actual year; {@code actor} is named in the Part A access log.
     */
    private Form16Pdf.Merged rendered(Form16Certificate f, String actor, String sourceIp) {
        PartBDocument document = document(f);
        byte[] partB =
                renderGate == null
                        ? Form16Pdf.of(document)
                        : renderGate.render(
                                "form16-" + f.getFinYear(), () -> Form16Pdf.of(document));
        byte[] partA = null;
        if (f.getPartAAttachmentId() != null && f.status() != Status.PENDING_PART_A) {
            DocumentStore.Content a =
                    store.read(
                            actor,
                            sourceIp,
                            ENTITY,
                            String.valueOf(f.getForm16Id()),
                            f.getPartAAttachmentId());
            partA = a.bytes();
            requirePartAOf(f, partA);
        }
        return Form16Pdf.merge(partA, partB);
    }

    /** OD-153: the issued copies (setter-injected: unit tests built by hand do without it). */
    private Form16IssuedCopies issuedCopies;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setIssuedCopies(Form16IssuedCopies issuedCopies) {
        this.issuedCopies = issuedCopies;
    }

    /**
     * R-PII (SRS-7-9, SRS-7-23): the Part A received from TRACES must be this employee's: when its
     * text names PANs and none is the employee's, it is refused (409), so another person's tax
     * certificate is never merged or sent. A Part A whose text cannot be read (a scan, an image) or
     * an employee without a PAN on record is merged as received (PENDING F16-PARTA: FCI to say
     * whether the TAN and the totals are checked too).
     */
    private void requirePartAOf(Form16Certificate f, byte[] partA) {
        java.util.Set<String> named = Form16Pdf.pansIn(partA);
        String own = employees.panForStatutoryOutput(f.getEmployeeId()).orElse(null);
        if (own == null || named.isEmpty() || named.contains(own)) {
            return;
        }
        // The refusal is logged by @Loggable (code only, never the PANs).
        throw new FciException(TaxErrorCode.PART_A_MISMATCH);
    }

    /** OD-144: the PDF bulkhead; set by Spring (absent in unit tests built by hand). */
    private in.gov.fci.payroll.platform.export.PdfRenderGate renderGate;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRenderGate(in.gov.fci.payroll.platform.export.PdfRenderGate renderGate) {
        this.renderGate = renderGate;
    }

    // ---------------------------------------------------------------- bulk (C-10 job, OD-149)

    /** The owner of a bulk job's output in the document store (the job request). */
    public static final String OUTPUT_ENTITY = "batch_job_output";

    /** One certificate of a bulk file: its id and the staff number its file is named by. */
    public record BulkCertificate(long form16Id, String eis) {}

    /** The C-10 bulk file of a year: its job and state. */
    public record BulkFile(long jobRequestId, String status, long written, long leftOut) {}

    /** A finished C-10 bulk file. */
    public record BulkDownload(String name, byte[] zip, long files) {}

    /**
     * OD-149 (the C-10 bulk job): the certificates of a paying unit's year whose Part B is
     * generated, in staff-number order (a fixed order, the same file every time).
     */
    @Transactional(readOnly = true)
    public List<BulkCertificate> bulkCertificates(long unitId, String finYear) {
        Set<Long> locations =
                org.subtree(unitId).stream()
                        .filter(
                                l ->
                                        org.cycleUnitOf(l)
                                                .map(u -> u.getId().equals(unitId))
                                                .orElse(false))
                        .collect(Collectors.toSet());
        Set<Long> members = employees.idsAt(locations);
        Map<Long, EmployeeDirectory.Member> byId = employees.members(members);
        return certificates.findByFinYearAndEmployeeIdIn(finYear, members).stream()
                .filter(f -> f.getPartBGeneratedAt() != null)
                .map(
                        f ->
                                new BulkCertificate(
                                        f.getForm16Id(),
                                        byId.containsKey(f.getEmployeeId())
                                                ? byId.get(f.getEmployeeId()).eis()
                                                : String.valueOf(f.getEmployeeId())))
                .sorted(
                        java.util.Comparator.comparing(BulkCertificate::eis)
                                .thenComparing(BulkCertificate::form16Id))
                .toList();
    }

    /**
     * OD-149 (the C-10 bulk job on the batch node): one certificate as the desk downloads it (Part
     * A first once merged, then Part B), for a job whose unit and requester are already resolved;
     * {@code actor} is named in the Part A access log. A tax year under the new Act is refused as
     * on the screen. In its own read-only transaction (SRS-NF-7: a certificate that cannot be
     * printed stops only itself, never the job's chunk).
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public byte[] certificatePdfForJob(long form16Id, String actor) {
        Form16Certificate f =
                certificates
                        .findById(form16Id)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (Integer.parseInt(f.getFinYear().substring(0, 4)) >= FIRST_YEAR_OF_NEW_ACT) {
            throw new FciException(
                    TaxErrorCode.CERTIFICATE_FORMAT_NOT_NOTIFIED,
                    new Object[] {f.getFinYear()},
                    List.of(),
                    null);
        }
        // OD-153: a released certificate goes into the ZIP as issued.
        Optional<Form16Pdf.Merged> issued = issuedCopy(f, actor, null);
        if (issued.isPresent()) {
            return issued.get().pdf();
        }
        byte[] partB = Form16Pdf.of(document(f));
        byte[] partA = null;
        if (f.getPartAAttachmentId() != null && f.status() != Status.PENDING_PART_A) {
            partA =
                    store.read(
                                    actor,
                                    null,
                                    ENTITY,
                                    String.valueOf(f.getForm16Id()),
                                    f.getPartAAttachmentId())
                            .bytes();
            requirePartAOf(f, partA);
        }
        return Form16Pdf.merge(partA, partB).pdf();
    }

    private static Map<String, String> bulkParams(long unitId, String finYear) {
        return Map.of("locationId", String.valueOf(unitId), "finYear", finYear);
    }

    /**
     * SRS-NF-2, OD-149: queues the C-10 Bulk PDF of the user's paying unit for a year on the batch
     * instance (C-10 VIEW); 409 while the same request is still open.
     */
    @Transactional
    public BulkFile requestBulk(AccessContext context, String finYear) {
        policy.require(context, SCREEN, "VIEW");
        OrgLocation unit = unitOf(context);
        long id =
                jobs.enqueue(
                        context,
                        in.gov.fci.payroll.jobs.api.BackgroundJobs.Kind.FORM16_BULK,
                        unit.getId(),
                        bulkParams(unit.getId(), finYear));
        return new BulkFile(id, "PENDING", 0, 0);
    }

    /** The user's newest C-10 bulk file of the year, if one was asked for. */
    @Transactional(readOnly = true)
    public Optional<BulkFile> bulkStatus(AccessContext context, String finYear) {
        policy.require(context, SCREEN, "VIEW");
        OrgLocation unit = unitOf(context);
        return jobs.latest(
                        in.gov.fci.payroll.jobs.api.BackgroundJobs.Kind.FORM16_BULK,
                        context.loginId(),
                        bulkParams(unit.getId(), finYear))
                .map(j -> new BulkFile(j.id(), j.status(), j.written(), j.skipped()));
    }

    /** The ZIP of the user's own C-10 bulk job: only its requester, only once completed. */
    @Transactional(readOnly = true)
    public BulkDownload bulkFile(AccessContext context, String sourceIp, long jobRequestId) {
        policy.require(context, SCREEN, "VIEW");
        in.gov.fci.payroll.jobs.api.BackgroundJobs.Job job =
                jobs.find(in.gov.fci.payroll.jobs.api.BackgroundJobs.Kind.FORM16_BULK, jobRequestId)
                        .filter(j -> context.loginId().equals(j.requestedByLogin()))
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireRecordAt(context, SCREEN, "VIEW", job.locationId());
        if (!job.completed()) {
            throw new FciException(
                    in.gov.fci.payroll.platform.error.ApiErrorCode.PDF_BULK_NOT_READY,
                    new Object[] {job.status()},
                    List.of(),
                    null);
        }
        DocumentStore.AttachmentView newest =
                store.active(OUTPUT_ENTITY, String.valueOf(jobRequestId)).stream()
                        .reduce((a, b) -> b)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        byte[] zip =
                store.read(
                                context.loginId(),
                                sourceIp,
                                OUTPUT_ENTITY,
                                String.valueOf(jobRequestId),
                                newest.id())
                        .bytes();
        return new BulkDownload(
                "form16-" + job.params().getOrDefault("finYear", ""), zip, job.written());
    }

    /** The bulk job queue (setter-injected: unit tests built by hand do without it). */
    private in.gov.fci.payroll.jobs.api.BackgroundJobs jobs;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBackgroundJobs(in.gov.fci.payroll.jobs.api.BackgroundJobs jobs) {
        this.jobs = jobs;
    }

    private Form16Certificate readable(AccessContext context, Long id) {
        Form16Certificate f =
                certificates
                        .findById(id)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (f.getEmployeeId().equals(context.employeeId())) {
            policy.require(context, MEMBER_SCREEN, "SELF");
            if (f.status() != Status.RELEASED) {
                throw new FciException(CommonErrorCode.NOT_FOUND);
            }
        } else {
            policy.require(context, SCREEN, "VIEW");
            inScope(context, f, "VIEW");
        }
        if (f.getPartBGeneratedAt() == null) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        int start = Integer.parseInt(f.getFinYear().substring(0, 4));
        if (start >= FIRST_YEAR_OF_NEW_ACT) {
            throw new FciException(
                    TaxErrorCode.CERTIFICATE_FORMAT_NOT_NOTIFIED,
                    new Object[] {f.getFinYear()},
                    List.of(),
                    null);
        }
        return f;
    }

    private PartBDocument document(Form16Certificate f) {
        EmployeeDirectory.Member m =
                employees
                        .member(f.getEmployeeId())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        LocalDate yearEnd =
                LocalDate.of(Integer.parseInt(f.getFinYear().substring(0, 4)) + 1, 3, 31);
        TaxComputationService.Forecast actual =
                computation.forecast(m, f.getFinYear(), yearEnd, computation.ratesOn(yearEnd));
        Form16PartB.Sheet sheet =
                Form16PartB.of(actual)
                        .orElseThrow(() -> new FciException(TaxErrorCode.EARNINGS_NOT_AVAILABLE));
        EmployeeDirectory.MaskedPan pan =
                employees
                        .maskedPans(List.of(f.getEmployeeId()))
                        .getOrDefault(f.getEmployeeId(), EmployeeDirectory.maskedPanOf(null));
        String employer =
                m.locationId() == null
                        ? null
                        : org.cycleUnitOf(m.locationId()).map(OrgLocation::getName).orElse(null);
        return new PartBDocument(
                view(f, m, pan),
                employer,
                employees.panForStatutoryOutput(f.getEmployeeId()).orElse(null),
                sheet);
    }

    // ---------------------------------------------------------------- helpers

    private void requireReconciled(Long unitId, String finYear, List<Form16Certificate> merged) {
        BigDecimal tds =
                merged.stream()
                        .map(Form16Certificate::getTaxDeducted)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal challanTotal =
                challans.findByOrgUnitIdAndFinYearOrderByChallanDate(unitId, finYear).stream()
                        .filter(
                                c ->
                                        ledger.remitted(
                                                        c.getCinNumber(),
                                                        c.getBsrCode(),
                                                        c.getChallanDate())
                                                .orElse(false))
                        .map(TaxChallan::getChallanAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        // SRS-7-8: within the release-gate tolerance of the versions the certificates were
        // generated under (none: exact).
        BigDecimal tolerance =
                merged.stream()
                        .map(Form16Certificate::getDefinitionVersionId)
                        .distinct()
                        .flatMap(v -> outputs.controlsOf(v).stream())
                        .filter(OutputControlTotal::isReleaseGate)
                        .map(OutputControlTotal::getToleranceAmount)
                        .min(BigDecimal::compareTo)
                        .orElse(BigDecimal.ZERO);
        List<String> differences =
                StatutoryRules.differences(
                        tds,
                        ledger.postedTds(unitId, SavingsRules.monthsOf(finYear)),
                        challanTotal,
                        tolerance);
        if (!differences.isEmpty()) {
            throw new FciException(
                    TaxErrorCode.NOT_RECONCILED,
                    new Object[] {String.join("; ", differences)},
                    List.of(),
                    null);
        }
    }

    /** The paying unit of the user's office (the cycle unit, OD-11). */
    OrgLocation unitOf(AccessContext context) {
        return org.cycleUnitOf(context.locationId())
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    /** The members of the paying unit in the user's offices. */
    Set<Long> membersOf(AccessContext context, OrgLocation unit) {
        Set<Long> offices =
                context.dataScope().stream()
                        .filter(
                                l ->
                                        org.cycleUnitOf(l)
                                                .map(u -> u.getId().equals(unit.getId()))
                                                .orElse(false))
                        .collect(Collectors.toSet());
        return employees.idsAt(offices);
    }

    private boolean locked(Long unitId, String yymm) {
        return payroll.cycle(unitId, yymm).map(PayrollRecords.Cycle::isLocked).orElse(false);
    }

    private EmployeeDirectory.Member inScope(
            AccessContext context, Form16Certificate f, String action) {
        EmployeeDirectory.Member m =
                employees
                        .member(f.getEmployeeId())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireRecordAt(context, SCREEN, action, m.locationId());
        return m;
    }

    private Form16Certificate locked(Long id) {
        return certificates
                .lockById(id)
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private static FciException state(Form16Certificate f) {
        return new FciException(
                TaxErrorCode.DECLARATION_STATE, new Object[] {f.status().name()}, List.of(), null);
    }

    private static CertificateView view(Form16Certificate f, EmployeeDirectory.Member m) {
        return view(f, m, null);
    }

    private static CertificateView view(
            Form16Certificate f, EmployeeDirectory.Member m, EmployeeDirectory.MaskedPan pan) {
        return new CertificateView(
                f.getForm16Id(),
                f.getEmployeeId(),
                m == null ? null : m.eis(),
                m == null ? null : m.name(),
                f.getFinYear(),
                f.getGrossSalary(),
                f.getTaxableIncome(),
                f.getTaxDeducted(),
                f.getPartAAttachmentId() == null ? "PENDING" : "RECEIVED",
                f.status().name(),
                f.getPartBGeneratedAt(),
                f.getReleasedAt(),
                f.getPartAAttachmentId(),
                pan == null ? null : pan.masked(),
                pan == null ? null : pan.status().name());
    }
}
