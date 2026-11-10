package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.business.FallbackRules;
import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.general.business.Inputs;
import in.gov.fci.payroll.general.business.model.FallbackEnableScope;
import in.gov.fci.payroll.general.business.model.FallbackEnablement;
import in.gov.fci.payroll.general.business.model.FallbackRecord;
import in.gov.fci.payroll.general.business.model.FallbackUpload;
import in.gov.fci.payroll.general.business.model.FallbackUploadLine;
import in.gov.fci.payroll.general.config.FallbackFormatProperties;
import in.gov.fci.payroll.general.persistence.GeneralRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * X-07 HRMS Fallback Upload (SRS-G-46, SRS-G-47, SRS-EH-11, BR-INT-011; SRS v0.5 use case "Enable,
 * Upload and Approve an HRMS Fallback Upload").
 *
 * <ol>
 *   <li><b>Enable</b> (Administrator at HQ, X-07 CREATE): type, month, offices, reason.
 *       Maker-checker X07_CREATE (decision D-3); the enablement row is created only when approved
 *       ({@link FallbackEffects}).
 *   <li><b>Upload</b> (Payroll Maker of an enabled office, X-07 SUBMIT): refused without an OPEN
 *       enablement covering the office (SRS-EH-11) or once the office's cycle is locked; the file
 *       is read through the configured HRMS export layout (D-2), every record validated and stored
 *       as a line, the source file kept read-only. A newer upload supersedes an unsubmitted one.
 *   <li><b>Order copy</b> (fallback (b) only): the suspension / absconding / revocation order under
 *       the order control (SRS-G-30).
 *   <li><b>Submit</b> (X07_SUBMIT): to the Payroll Checker of the paying unit, who approves the
 *       ACCEPTED lines with OTP HRMS_FALLBACK_UPLOAD_APPROVAL, or returns / rejects with a reason.
 *   <li><b>Cancel</b>: before approval, never deleted (SRS-G-37).
 * </ol>
 *
 * Out of scope here (tracker): the effect of approved data on the computation (module 16) and on
 * suspension events (I-17), and the matching with HRMS when it is back (SRS-G-47, module 23; the
 * reconciliation table of what was matched is {@link FallbackReconciliationService}).
 */
@Service
@Loggable
public class FallbackService {

    public static final String SCREEN = "X-07";
    static final String ENABLEMENT = "hrms_fallback_enablement";
    static final String UPLOAD = "hrms_fallback_upload";
    static final String CREATE_ACTION = "X07_CREATE";
    static final String SUBMIT_ACTION = "X07_SUBMIT";

    /** What HQ asks to enable (the approval payload until the Checker decides). */
    public record EnablementProposal(
            String type,
            String payrollYymm,
            List<Long> officeIds,
            String reason,
            Instant hrmsDownSince,
            String proposedBy) {}

    public record EnablementView(
            Long id,
            String reference,
            String type,
            String payrollYymm,
            List<String> offices,
            String reason,
            String enabledBy,
            Instant enabledAt,
            String status,
            String withdrawnReason,
            List<String> officeNames,
            Instant hrmsRestoredAt) {}

    public record UploadView(
            Long id,
            String batchNo,
            Long enablementId,
            String type,
            String office,
            String payrollYymm,
            String fileName,
            int recordCount,
            int acceptedCount,
            int rejectedCount,
            String uploadedBy,
            Instant uploadedAt,
            String status,
            String checkerUser,
            String decisionReason,
            String reconStatus,
            int orderCopies,
            Long sourceAttachmentId,
            Long approvalId,
            String enablementReference) {}

    /** One reason records of an upload were rejected for, with how many (X-07 "Validation"). */
    public record Rejection(String reason, long count) {}

    public record LineView(
            int lineNo,
            String employeeNumber,
            String recordType,
            LocalDate periodFrom,
            LocalDate periodTo,
            String payDays,
            String lwpDays,
            String halfPayDays,
            LocalDate eventEffectiveDate,
            String orderNo,
            String validationStatus,
            String rejectionReason,
            String reconStatus) {}

    private final GeneralRepositories.Enablements enablements;
    private final GeneralRepositories.EnableScopes scopes;
    private final GeneralRepositories.Uploads uploads;
    private final GeneralRepositories.UploadLines lines;
    private final FallbackFormatProperties formats;
    private final EmployeeDirectory employees;
    private final OrgDirectory org;
    private final PayrollRecords payroll;
    private final DocumentStore documents;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    private final ExportReader exportReader;

    public FallbackService(
            ExportReader exportReader,
            GeneralRepositories.Enablements enablements,
            GeneralRepositories.EnableScopes scopes,
            GeneralRepositories.Uploads uploads,
            GeneralRepositories.UploadLines lines,
            FallbackFormatProperties formats,
            EmployeeDirectory employees,
            OrgDirectory org,
            PayrollRecords payroll,
            DocumentStore documents,
            MakerChecker approvals,
            Authorization policy,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.exportReader = exportReader;
        this.enablements = enablements;
        this.scopes = scopes;
        this.uploads = uploads;
        this.lines = lines;
        this.formats = formats;
        this.employees = employees;
        this.org = org;
        this.payroll = payroll;
        this.documents = documents;
        this.approvals = approvals;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 1. enable (HQ)

    /**
     * Proposes an enablement; nothing is enabled until the HQ Checker approves it. Refused when an
     * office's cycle for the month is already locked (X-07 field table: the cycle "must not be
     * locked").
     *
     * @return the approval item id
     */
    @Transactional
    public Long proposeEnablement(
            AccessContext context,
            String type,
            String payrollYymm,
            List<String> officeCodes,
            String reason,
            Instant hrmsDownSince) {
        policy.require(context, SCREEN, "CREATE");
        requireHeadquarters(context);
        FallbackEnablement.Type fallbackType = type(type);
        YearMonth month = Inputs.month(payrollYymm, GeneralErrorCode.FALLBACK_PERIOD);
        if (officeCodes == null || officeCodes.isEmpty()) {
            throw new FciException(GeneralErrorCode.FALLBACK_OFFICES);
        }
        List<Long> offices = new ArrayList<>();
        for (String code : officeCodes.stream().distinct().toList()) {
            OrgLocation office =
                    org.byCode(code)
                            .orElseThrow(() -> new FciException(GeneralErrorCode.FALLBACK_OFFICES));
            requireCycleOpen(office.getId(), month);
            offices.add(office.getId());
        }
        // SRS X-07 field table (l.876, l.5191): the enable reason is at most 500 characters.
        String why = Inputs.text(reason, 500, GeneralErrorCode.QUERY_TEXT);
        EnablementProposal proposal =
                new EnablementProposal(
                        fallbackType.name(),
                        Inputs.yymm(month),
                        offices,
                        why,
                        hrmsDownSince,
                        context.loginId());
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                CREATE_ACTION,
                                SCREEN,
                                "CREATE",
                                ENABLEMENT,
                                "proposal-" + UUID.randomUUID(),
                                ActionType.CREATE,
                                context.locationId(),
                                payload(proposal),
                                month.atDay(1),
                                why),
                        null);
        return item.id();
    }

    /**
     * Creates the approved enablement and its office scope (called from {@link FallbackEffects}).
     */
    @Transactional
    public FallbackEnablement openApproved(String payloadJson, Long approvalId, String checker) {
        EnablementProposal p = read(payloadJson);
        String year = p.payrollYymm().substring(0, 4);
        // FBE-yyyy-nnn (DDL comment on enablement_ref); unique by uk_hrms_fallback_enable_ref.
        String reference =
                String.format(
                        "FBE-%s-%03d",
                        year, enablements.countByReferenceStartingWith("FBE-" + year + "-") + 1);
        FallbackEnablement e =
                enablements.saveAndFlush(
                        FallbackEnablement.open(
                                reference,
                                FallbackEnablement.Type.valueOf(p.type()),
                                p.payrollYymm(),
                                p.reason(),
                                p.proposedBy(),
                                p.hrmsDownSince(),
                                approvalId,
                                clock.instant()));
        YearMonth month = Inputs.month(p.payrollYymm(), GeneralErrorCode.FALLBACK_PERIOD);
        for (Long office : p.officeIds()) {
            Long cycle =
                    cycleOf(office, month).map(PayrollRecords.Cycle::cycleInstanceId).orElse(null);
            scopes.save(FallbackEnableScope.of(e.getId(), office, cycle));
        }
        audit.record(
                checker,
                AuditService.Entry.change(
                                ENABLEMENT,
                                e.getReference(),
                                "enablement_status",
                                null,
                                e.getStatus().name(),
                                p.reason())
                        .approval(approvalId)
                        .module(SCREEN));
        return e;
    }

    /**
     * SRS role table (System Administrator): "At Headquarters, enables the HRMS fallback upload for
     * a cycle and office (X-07, BR-INT-011)". Enabling and withdrawing are Headquarters acts
     * (security review 2026-10-31: an administrator of another office could withdraw any office's
     * window by its id).
     */
    private void requireHeadquarters(AccessContext context) {
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(ApiErrorCode.HQ_ONLY);
        }
    }

    /** Withdraws an enablement with a reason, before any upload of it is approved. */
    @Transactional
    public EnablementView withdraw(AccessContext context, Long enablementId, String reason) {
        policy.require(context, SCREEN, "CREATE");
        requireHeadquarters(context);
        FallbackEnablement e =
                enablements
                        .findById(enablementId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (!e.acceptsUploads()
                || uploads.findByEnablementId(e.getId()).stream()
                        .anyMatch(
                                u ->
                                        u.getStatus() == FallbackUpload.Status.APPROVED
                                                || u.getStatus()
                                                        == FallbackUpload.Status
                                                                .PENDING_APPROVAL)) {
            throw new FciException(
                    GeneralErrorCode.FALLBACK_STATUS,
                    new Object[] {e.getStatus().name()},
                    List.of(),
                    null);
        }
        String before = e.getStatus().name();
        e.withdraw(Inputs.text(reason, 1000, GeneralErrorCode.QUERY_TEXT));
        enablements.save(e);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENABLEMENT,
                                e.getReference(),
                                "enablement_status",
                                before,
                                e.getStatus().name(),
                                e.getWithdrawnReason())
                        .module(SCREEN));
        return enablementView(e);
    }

    // ---------------------------------------------------------------- 2. upload (Maker of the
    // office)

    /**
     * Uploads the HRMS export for the session's office: checks, reads, validates every record,
     * keeps the source file, and supersedes an earlier unsubmitted upload of the same office.
     */
    @Transactional
    public UploadView upload(
            AccessContext context, Long enablementId, String fileName, byte[] content) {
        Long office = context.locationId();
        policy.requireAt(context, SCREEN, "SUBMIT", office);
        FallbackEnablement e =
                enablements
                        .findById(enablementId)
                        .filter(FallbackEnablement::acceptsUploads)
                        .orElseThrow(() -> new FciException(GeneralErrorCode.FALLBACK_NOT_ENABLED));
        // SRS-EH-11: only an office the enablement names may upload.
        if (scopes.findByEnablementId(e.getId()).stream()
                .noneMatch(s -> s.getOrgUnitId().equals(office))) {
            throw new FciException(GeneralErrorCode.FALLBACK_NOT_ENABLED);
        }
        YearMonth month = Inputs.month(e.getPayrollYymm(), GeneralErrorCode.FALLBACK_PERIOD);
        Optional<PayrollRecords.Cycle> cycle = requireCycleOpen(office, month);

        // The source file is checked as text first (document rules); then read through the layout.
        String text =
                new String(content == null ? new byte[0] : content, StandardCharsets.US_ASCII);
        List<FallbackRecord> records =
                FallbackRules.read(text, e.getType(), formats.format(e.getType()));
        Map<String, Optional<EmployeeDirectory.Member>> members = new HashMap<>();
        Set<Long> enabledUnits = org.subtree(office);
        List<String> reasons =
                FallbackRules.validate(
                        records,
                        e.getType(),
                        month,
                        eis ->
                                members.computeIfAbsent(eis, employees::memberByEis)
                                        .map(m -> new FallbackRules.Member(m.id(), m.locationId())),
                        enabledUnits);
        int rejected = (int) reasons.stream().filter(r -> r != null).count();

        // "The Maker corrects and resubmits as a new version": an unsubmitted earlier upload is
        // superseded.
        for (FallbackUpload earlier : uploads.findByEnablementIdAndOrgUnitId(e.getId(), office)) {
            if (earlier.getStatus() == FallbackUpload.Status.VALIDATED
                    || earlier.getStatus() == FallbackUpload.Status.RETURNED) {
                earlier.supersede();
                uploads.save(earlier);
            } else if (earlier.getStatus() == FallbackUpload.Status.PENDING_APPROVAL
                    || earlier.getStatus() == FallbackUpload.Status.APPROVED) {
                throw new FciException(
                        GeneralErrorCode.FALLBACK_STATUS,
                        new Object[] {earlier.getStatus().name()},
                        List.of(),
                        null);
            }
        }
        String batch =
                "FBU-"
                        + DateTimeFormatter.ofPattern("yyyyMMdd")
                                .format(clock.instant().atOffset(ZoneOffset.UTC))
                        + "-"
                        + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        FallbackUpload upload =
                uploads.saveAndFlush(
                        FallbackUpload.validated(
                                batch,
                                e,
                                office,
                                cycle.map(PayrollRecords.Cycle::cycleInstanceId).orElse(null),
                                fileName == null ? "upload.csv" : fileName,
                                sha256(content),
                                null,
                                records.size() - rejected,
                                rejected,
                                context.loginId(),
                                clock.instant()));
        for (int i = 0; i < records.size(); i++) {
            FallbackRecord r = records.get(i);
            Long employeeId =
                    r.employeeNumber() == null
                            ? null
                            : members.getOrDefault(r.employeeNumber(), Optional.empty())
                                    .map(EmployeeDirectory.Member::id)
                                    .orElse(null);
            lines.save(FallbackUploadLine.of(upload.getId(), r, employeeId, reasons.get(i)));
        }
        // "The source file is kept read-only with the upload" - in the document store, owned by
        // this row.
        DocumentStore.AttachmentView source =
                documents.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                UPLOAD,
                                String.valueOf(upload.getId()),
                                null,
                                null,
                                null,
                                null,
                                upload.getSourceFileName(),
                                content,
                                true));
        upload.sourceFile(source.id());
        uploads.save(upload);
        e.uploaded();
        enablements.save(e);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                UPLOAD,
                                upload.getBatchNo(),
                                "upload_status",
                                null,
                                upload.getStatus().name(),
                                "Uploaded: "
                                        + upload.getAcceptedCount()
                                        + " accepted, "
                                        + rejected
                                        + " rejected")
                        .module(SCREEN));
        return uploadView(upload);
    }

    /** Attaches the order copy of a fallback (b) upload (SRS-G-30 order control). */
    @Transactional
    public DocumentStore.AttachmentView attachOrder(
            AccessContext context,
            Long uploadId,
            String documentType,
            String reference,
            LocalDate orderDate,
            String authority,
            String fileName,
            byte[] content) {
        FallbackUpload u = ownUpload(context, uploadId, "SUBMIT");
        requireEditable(u);
        return documents.attach(
                context.loginId(),
                new DocumentStore.NewAttachment(
                        UPLOAD + "_order",
                        String.valueOf(u.getId()),
                        documentType,
                        reference,
                        orderDate,
                        authority,
                        fileName,
                        content,
                        true));
    }

    /**
     * Submits the upload to the Payroll Checker of the paying unit (X07_SUBMIT; OTP at approval).
     */
    @Transactional
    public UploadView submit(AccessContext context, Long uploadId, String reason) {
        FallbackUpload u = ownUpload(context, uploadId, "SUBMIT");
        // Status first, so a pending, decided or cancelled upload is refused with its own status
        // rather than by the approval queue.
        requireEditable(u);
        // X-07 field table: the order copy is mandatory for fallback (b).
        if (u.getType() == FallbackEnablement.Type.SUSPENSION_ABSCONDING
                && documents.countActive(UPLOAD + "_order", String.valueOf(u.getId())) == 0) {
            throw new FciException(GeneralErrorCode.FALLBACK_ORDER_REQUIRED);
        }
        requireCycleOpen(
                u.getOrgUnitId(),
                Inputs.month(u.getPayrollYymm(), GeneralErrorCode.FALLBACK_PERIOD));
        String why =
                reason == null || reason.isBlank()
                        ? "HRMS fallback upload " + u.getBatchNo()
                        : Inputs.text(reason, 500, GeneralErrorCode.QUERY_TEXT);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SUBMIT_ACTION,
                                SCREEN,
                                "SUBMIT",
                                UPLOAD,
                                String.valueOf(u.getId()),
                                ActionType.CREATE,
                                u.getOrgUnitId(),
                                "{\"uploadId\":"
                                        + u.getId()
                                        + ",\"batchNo\":\""
                                        + u.getBatchNo()
                                        + "\"}",
                                Inputs.month(u.getPayrollYymm(), GeneralErrorCode.FALLBACK_PERIOD)
                                        .atDay(1),
                                why),
                        (OtpProof) null);
        String before = u.getStatus().name();
        u.submit(item.id());
        uploads.save(u);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                UPLOAD,
                                u.getBatchNo(),
                                "upload_status",
                                before,
                                u.getStatus().name(),
                                why)
                        .approval(item.id())
                        .module(SCREEN));
        return uploadView(u);
    }

    /**
     * Cancels an unsubmitted upload with a reason; its files are cancelled with it (never deleted).
     */
    @Transactional
    public UploadView cancel(AccessContext context, Long uploadId, String reason) {
        FallbackUpload u = ownUpload(context, uploadId, "SUBMIT");
        String before = u.getStatus().name();
        String why = Inputs.text(reason, 1000, GeneralErrorCode.QUERY_TEXT);
        u.cancel(why);
        uploads.save(u);
        documents.cancelAll(context.loginId(), UPLOAD + "_order", String.valueOf(u.getId()), why);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                UPLOAD,
                                u.getBatchNo(),
                                "upload_status",
                                before,
                                u.getStatus().name(),
                                why)
                        .module(SCREEN));
        return uploadView(u);
    }

    // ---------------------------------------------------------------- views

    /** Enablements (any X-07 viewer sees which cycles and offices are open for fallback). */
    @Transactional(readOnly = true)
    public Paged<EnablementView> enablements(AccessContext context, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        return Paged.of(enablements.findAllByOrderByEnabledAtDescIdDesc(spec.pageable()))
                .map(this::enablementView);
    }

    /**
     * The enablements still open for an upload (OPEN or UPLOADED): the upload form's choice and the
     * "enabled" tile. A short list (one per type and period at most), not paged.
     */
    @Transactional(readOnly = true)
    public List<EnablementView> liveEnablements(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        // SRS-INT-22 / SRS-EH-11 (static UI audit #34): an office is offered only the live
        // enablements that name it (the only ones it may upload against); Headquarters, which
        // enables them, sees every one.
        boolean headquarters = policy.isAtHeadquarters(context);
        Long office = context.locationId();
        return enablements
                .findByStatusInOrderByEnabledAtDescIdDesc(
                        List.of(FallbackEnablement.Status.OPEN, FallbackEnablement.Status.UPLOADED))
                .stream()
                .filter(
                        e ->
                                headquarters
                                        || scopes.findByEnablementId(e.getId()).stream()
                                                .anyMatch(s -> s.getOrgUnitId().equals(office)))
                .map(this::enablementView)
                .toList();
    }

    /**
     * X-07 tiles: uploads of the scope awaiting approval and those approved, reconciliation
     * pending.
     */
    public record Summary(long awaitingApproval, long reconciliationPending) {}

    @Transactional(readOnly = true)
    public Summary summary(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        Object[] c =
                uploads.tileCounts(
                                Scopes.of(context),
                                FallbackUpload.Status.PENDING_APPROVAL,
                                FallbackUpload.Status.APPROVED,
                                FallbackUpload.ReconStatus.PENDING)
                        .get(0);
        return new Summary(((Number) c[0]).longValue(), ((Number) c[1]).longValue());
    }

    /** Rows FallbackService#export would write (the size check before streaming, SRS-G-13). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context) {
        policy.require(context, SCREEN, "EXPORT");
        Set<Long> scope = Scopes.of(context);
        return exportReader.count(
                page -> uploads.findByOrgUnitIdInOrderByUploadedAtDescIdDesc(scope, page));
    }

    /**
     * The upload list as an export (SRS-G-13): every upload in scope, uncapped, newest first. Runs
     * in one read-only snapshot (SERIALIZABLE), batch by batch, and checks the rows written against
     * the rows counted in that snapshot.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(AccessContext context, ExportSink sink) throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        Set<Long> scope = Scopes.of(context);
        sink.header(
                "Batch",
                "Fallback",
                "Office",
                "Payroll month",
                "File",
                "Records",
                "Accepted",
                "Rejected",
                "Uploaded by",
                "Uploaded at",
                "Status",
                "Checker",
                "Decision reason",
                "Reconciliation");
        long expected =
                exportReader.readAll(
                        page -> uploads.findByOrgUnitIdInOrderByUploadedAtDescIdDesc(scope, page),
                        rows -> {
                            for (FallbackUpload row : rows) {
                                UploadView u = uploadView(row);
                                sink.row(
                                        u.batchNo(),
                                        u.type(),
                                        u.office(),
                                        u.payrollYymm(),
                                        u.fileName(),
                                        u.recordCount(),
                                        u.acceptedCount(),
                                        u.rejectedCount(),
                                        u.uploadedBy(),
                                        u.uploadedAt(),
                                        u.status(),
                                        u.checkerUser(),
                                        u.decisionReason(),
                                        u.reconStatus());
                            }
                        });
        ExportSink.requireComplete(expected, sink);
    }

    @Transactional(readOnly = true)
    public Paged<UploadView> uploads(AccessContext context, PageSpec spec) {
        return uploads(context, spec, null);
    }

    /** The uploads in scope, of one enablement when named (the row picked on X-07). */
    @Transactional(readOnly = true)
    public Paged<UploadView> uploads(AccessContext context, PageSpec spec, Long enablementId) {
        policy.require(context, SCREEN, "VIEW");
        Set<Long> scope = Scopes.of(context);
        return Paged.of(
                        enablementId == null
                                ? uploads.findByOrgUnitIdInOrderByUploadedAtDescIdDesc(
                                        scope, spec.pageable())
                                : uploads
                                        .findByEnablementIdAndOrgUnitIdInOrderByUploadedAtDescIdDesc(
                                                enablementId, scope, spec.pageable()))
                .map(this::uploadView);
    }

    /** Why an upload's records were rejected, reason by reason (X-07 "Validation", SRS-EH-11). */
    @Transactional(readOnly = true)
    public List<Rejection> rejections(AccessContext context, Long uploadId) {
        FallbackUpload u = scopedUpload(context, uploadId, "VIEW");
        return lines.rejectionCounts(u.getId()).stream()
                .map(r -> new Rejection((String) r[0], ((Number) r[1]).longValue()))
                .toList();
    }

    /** The validation result: every line, accepted or rejected with its reason. */
    @Transactional(readOnly = true)
    public Paged<LineView> lines(AccessContext context, Long uploadId, PageSpec spec) {
        FallbackUpload u = scopedUpload(context, uploadId, "VIEW");
        return Paged.of(lines.findByUploadIdOrderByLineNo(u.getId(), spec.pageable()))
                .map(
                        l ->
                                new LineView(
                                        l.getLineNo(),
                                        l.getEmployeeNumberRaw(),
                                        l.getRecordType(),
                                        l.getPeriodFrom(),
                                        l.getPeriodTo(),
                                        str(l.getPayDays()),
                                        str(l.getLwpDays()),
                                        str(l.getHalfPayDays()),
                                        l.getEventEffectiveDate(),
                                        l.getOrderNo(),
                                        l.getValidationStatus(),
                                        l.getRejectionReason(),
                                        l.getReconStatus()));
    }

    /** The order copies of an upload (fallback (b), X-07 "Order copy - attach & store"). */
    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> orders(AccessContext context, Long uploadId) {
        FallbackUpload u = scopedUpload(context, uploadId, "VIEW");
        return documents.active(UPLOAD + "_order", String.valueOf(u.getId()));
    }

    /** The kept source file or an order copy of an upload in scope. */
    @Transactional
    public DocumentStore.Content file(
            AccessContext context, String sourceIp, Long uploadId, Long attachmentId) {
        FallbackUpload u = scopedUpload(context, uploadId, "VIEW");
        String key = String.valueOf(u.getId());
        // The source file belongs to the upload row; order copies to "<upload>_order". The owner is
        // chosen up front: a refused read inside this transaction would mark it rollback-only.
        String owner =
                Objects.equals(u.getSourceAttachmentId(), attachmentId)
                        ? UPLOAD
                        : UPLOAD + "_order";
        return documents.read(context.loginId(), sourceIp, owner, key, attachmentId);
    }

    // ---------------------------------------------------------------- internals

    private FallbackUpload scopedUpload(AccessContext context, Long uploadId, String action) {
        FallbackUpload u =
                uploads.findById(uploadId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireRecordAt(context, SCREEN, action, u.getOrgUnitId());
        return u;
    }

    /** The upload of the session's own office (the Maker acts for the office that uploaded). */
    private FallbackUpload ownUpload(AccessContext context, Long uploadId, String action) {
        FallbackUpload u = scopedUpload(context, uploadId, action);
        if (!u.getOrgUnitId().equals(context.locationId())) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return u;
    }

    /** The office's cycle for the month, refused when it is locked. */
    private Optional<PayrollRecords.Cycle> requireCycleOpen(Long office, YearMonth month) {
        Optional<PayrollRecords.Cycle> cycle = cycleOf(office, month);
        if (cycle.filter(PayrollRecords.Cycle::isLocked).isPresent()) {
            throw new FciException(
                    GeneralErrorCode.FALLBACK_CYCLE_LOCKED,
                    new Object[] {Inputs.yymm(month)},
                    List.of(),
                    null);
        }
        return cycle;
    }

    /** Only a validated or returned upload takes an order or goes to the Checker. */
    private static void requireEditable(FallbackUpload u) {
        if (u.getStatus() != FallbackUpload.Status.VALIDATED
                && u.getStatus() != FallbackUpload.Status.RETURNED) {
            throw new FciException(
                    GeneralErrorCode.FALLBACK_STATUS,
                    new Object[] {u.getStatus().name()},
                    List.of(),
                    null);
        }
    }

    /** The cycle instance of the office's cycle unit (OD-11) for the month, if initiated. */
    private Optional<PayrollRecords.Cycle> cycleOf(Long office, YearMonth month) {
        return org.cycleUnitOf(office)
                .flatMap(unit -> payroll.cycle(unit.getId(), Inputs.yymm(month)));
    }

    private static FallbackEnablement.Type type(String value) {
        // BR-INT-011: exactly two fallback types exist, no others.
        for (FallbackEnablement.Type t : FallbackEnablement.Type.values()) {
            if (t.name().equals(value)) {
                return t;
            }
        }
        throw new FciException(GeneralErrorCode.FALLBACK_FORMAT);
    }

    private static String sha256(byte[] content) {
        return SecureTokens.sha256Hex(content);
    }

    private static String str(java.math.BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private String payload(EnablementProposal proposal) {
        return json.writeValueAsString(proposal);
    }

    private EnablementProposal read(String payload) {
        try {
            return json.readValue(payload, EnablementProposal.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unreadable enablement proposal", ex);
        }
    }

    private EnablementView enablementView(FallbackEnablement e) {
        List<Long> units =
                scopes.findByEnablementId(e.getId()).stream()
                        .map(FallbackEnableScope::getOrgUnitId)
                        .toList();
        List<String> offices =
                units.stream()
                        .map(
                                unit ->
                                        org.location(unit)
                                                .map(OrgLocation::getCode)
                                                .orElse(String.valueOf(unit)))
                        .toList();
        // The office by code and name (owner 2026-10-27: never a bare code on a screen).
        List<String> officeNames =
                units.stream()
                        .map(
                                unit ->
                                        org.location(unit)
                                                .map(l -> l.getCode() + " - " + l.getName())
                                                .orElse(String.valueOf(unit)))
                        .toList();
        return new EnablementView(
                e.getId(),
                e.getReference(),
                e.getType().name(),
                e.getPayrollYymm(),
                offices,
                e.getReason(),
                e.getEnabledBy(),
                e.getEnabledAt(),
                e.getStatus().name(),
                e.getWithdrawnReason(),
                officeNames,
                restoredAt(e));
    }

    /** When HRMS came back for a reconciled enablement (the drawn status names the date). */
    private Instant restoredAt(FallbackEnablement e) {
        if (e.getStatus() != FallbackEnablement.Status.RECONCILED) {
            return null;
        }
        return uploads.findByEnablementId(e.getId()).stream()
                .map(FallbackUpload::getHrmsRestoredAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
    }

    private UploadView uploadView(FallbackUpload u) {
        // The office by code and name (owner 2026-10-27: never a bare code on a screen).
        String office =
                org.location(u.getOrgUnitId())
                        .map(l -> l.getCode() + " - " + l.getName())
                        .orElse(null);
        int orders = documents.countActive(UPLOAD + "_order", String.valueOf(u.getId()));
        return new UploadView(
                u.getId(),
                u.getBatchNo(),
                u.getEnablementId(),
                u.getType().name(),
                office,
                u.getPayrollYymm(),
                u.getSourceFileName(),
                u.getRecordCount(),
                u.getAcceptedCount(),
                u.getRejectedCount(),
                u.getUploadedBy(),
                u.getUploadedAt(),
                u.getStatus().name(),
                u.getCheckerUser(),
                u.getDecisionReason(),
                u.getReconStatus().name(),
                orders,
                u.getSourceAttachmentId(),
                u.getApprovalId(),
                enablements
                        .findById(u.getEnablementId())
                        .map(FallbackEnablement::getReference)
                        .orElse(null));
    }
}
