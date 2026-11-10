package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.jobs.api.BackgroundJobs;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.PdfRenderGate;
import in.gov.fci.payroll.platform.export.ZipBundle;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payslip PDFs (OD-144, OD-149; SRS-1-10, SRS-17-1, SRS-17-7, SRS-NF-2, SRS-NF-5; screens E-01
 * and O-01). Owner 2026-11-09 (OD-149): every manual download is generated on demand from the data
 * in the database, nothing is stored; a bulk download is a ZIP of single-month PDFs.
 *
 * <ul>
 *   <li>Single month (E-01 own, E-01 support preview, O-01 member): one PDF, rendered from the
 *       stored locked result through {@link PayslipService}, so the access rules are the screen's:
 *       the member's own LOCKED month only (another member's, an unlocked or an unknown month is
 *       404 alike: no existence leak), the user's own open preview session, a member in the O-01
 *       scope (another office's is 404 and a security event, SRS-G-2). Never recomputed, so the
 *       same closed month gives the same bytes (SRS O-01 l.3392). SRS-NF-5 ("prepare payslips at
 *       lock so members can download them at once") is met by the payslip data written at lock and
 *       this fast rendering (a few milliseconds), within {@link PdfRenderGate}.
 *   <li>Member bulk (E-01, OD-149): the member's own locked months of a range as one ZIP, at most
 *       {@code fci.api.pdf.member-bulk-max-periods} months (a setting with no default: until it is
 *       set the bulk download is refused "not configured", PENDING PDF-SETTINGS); an unlocked month
 *       of the range is simply not in it.
 *   <li>Office bulk (O-01 Bulk PDF, SRS-NF-2: a background job): {@link #requestBulk} queues
 *       PAYSLIP_BULK on the batch instance; its ZIP is kept for the requester ({@link #bulkFile}).
 * </ul>
 *
 * <p>Signatures used by the e-mail delivery (OD-148): {@link #mine}, {@link #ofStaff}.
 */
@Service
@Loggable
public class PayslipPdfService {

    /** The owner of a bulk job's output in the document store (the job request). */
    public static final String OUTPUT_ENTITY = "batch_job_output";

    private static final String O_01 = "O-01";
    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("uuuuMM");

    private final PayslipService payslips;
    private final PdfRenderGate gate;
    private final Integer memberBulkMax;
    private final BackgroundJobs jobs;
    private final DocumentStore documents;
    private final OrgDirectory org;
    private final Authorization policy;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public PayslipPdfService(
            PayslipService payslips,
            PdfRenderGate gate,
            @Value("${fci.api.pdf.member-bulk-max-periods:#{null}}") Integer memberBulkMax,
            BackgroundJobs jobs,
            DocumentStore documents,
            OrgDirectory org,
            Authorization policy) {
        this.payslips = payslips;
        this.gate = gate;
        this.memberBulkMax = memberBulkMax;
        this.jobs = jobs;
        this.documents = documents;
        this.org = org;
        this.policy = policy;
    }

    /** E-01 SELF: the member's own locked month. */
    @Transactional(readOnly = true)
    public byte[] mine(AccessContext context, String yymm) {
        PayslipService.Payslip slip = payslips.payslip(context, yymm);
        return gate.render("payslip-" + yymm, () -> PayslipPdf.of(slip));
    }

    /** E-01 VIEW: a locked month of the member of the user's own open support preview. */
    @Transactional(readOnly = true)
    public byte[] ofPreview(AccessContext context, long session, String yymm) {
        PayslipService.Payslip slip = payslips.ofPreview(context, session, yymm);
        return gate.render("payslip-" + yymm, () -> PayslipPdf.of(slip));
    }

    /** O-01 VIEW: one committed month of a member in the user's scope. */
    @Transactional(readOnly = true)
    public byte[] ofStaff(AccessContext context, String eis, String yymm) {
        PayslipService.Payslip slip = payslips.ofStaff(context, eis, yymm);
        return gate.render("payslip-" + yymm, () -> PayslipPdf.of(slip));
    }

    // ---------------------------------------------------------------- member bulk (E-01)

    /** OD-149: the most months of one member bulk download, or empty while not configured. */
    public Optional<Integer> memberBulkMax() {
        return Optional.ofNullable(memberBulkMax).filter(m -> m > 0);
    }

    /**
     * OD-149, E-01 SELF: the months the member chose (YYYYMM), checked before any byte is sent:
     * more distinct months than the setting is 422 (both numbers), the setting not made is 422 "not
     * configured", and a month that is not one of the member's own LOCKED months (open, unknown or
     * another member's) makes the whole request 404, so nothing tells which month it was. Returned
     * oldest first, without duplicates.
     */
    @Transactional(readOnly = true)
    public List<String> memberMonths(AccessContext context, List<String> chosen) {
        int max =
                memberBulkMax()
                        .orElseThrow(() -> new FciException(ApiErrorCode.PDF_BULK_NOT_CONFIGURED));
        List<String> months =
                chosen == null ? List.of() : chosen.stream().distinct().sorted().toList();
        if (months.isEmpty()) {
            throw new FciException(
                    CommonErrorCode.VALIDATION_FAILED, new Object[] {"months"}, List.of(), null);
        }
        months.forEach(PayslipPdfService::month);
        if (months.size() > max) {
            throw new FciException(
                    ApiErrorCode.PDF_BULK_TOO_MANY,
                    new Object[] {months.size(), max},
                    List.of(),
                    null);
        }
        List<String> locked = payslips.lockedMonths(context, months.getFirst(), months.getLast());
        if (!locked.containsAll(months)) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return months;
    }

    /**
     * OD-149: writes the member's months into the ZIP, one PDF each ({@code payslip-YYYYMM.pdf}),
     * each through the render bulkhead; a month whose render fails is left out and listed in the
     * manifest. Called on the export thread with the context captured on the request thread.
     */
    @Transactional(readOnly = true)
    public void writeMemberZip(AccessContext context, List<String> months, ZipBundle zip)
            throws IOException {
        for (String yymm : months) {
            String name = "payslip-" + yymm + ".pdf";
            byte[] pdf;
            try {
                PayslipService.Payslip slip = payslips.payslip(context, yymm);
                pdf = gate.render("payslip-" + yymm, () -> PayslipPdf.of(slip));
            } catch (PdfRenderGate.Busy busy) {
                throw busy;
            } catch (RuntimeException failed) {
                zip.leftOut(name, failed.getClass().getSimpleName());
                continue;
            }
            zip.add(name, pdf);
        }
    }

    /** The timestamp of every entry of a member ZIP: the first day of its newest month. */
    public static LocalDateTime zipTime(List<String> months) {
        String newest = months.stream().max(Comparator.naturalOrder()).orElse("200001");
        return month(newest).atDay(1).atStartOfDay();
    }

    // ---------------------------------------------------------------- office bulk (O-01 job)

    /** The O-01 bulk file of a month and level: its job and state. */
    public record BulkFile(long jobRequestId, String status, long written, long leftOut) {}

    private static Map<String, String> params(
            long unitId, String yymm, PayslipRegisterService.Level level) {
        return Map.of(
                "locationId",
                String.valueOf(unitId),
                "payPeriod",
                month(yymm).toString(),
                "level",
                (level == null ? PayslipRegisterService.Level.STAFF : level).name());
    }

    /**
     * SRS-NF-2, OD-149: queues the O-01 Bulk PDF of the user's paying unit for a locked month (SRS
     * l.3389: an open month is refused) on the batch instance; 409 while the same request is still
     * open. The requester is told when it ends and downloads it from O-01.
     */
    @Transactional
    public BulkFile requestBulk(
            AccessContext context, String yymm, PayslipRegisterService.Level level) {
        policy.require(context, O_01, "VIEW");
        long unit = unitOf(context);
        if (!payslips.monthLocked(unit, yymm)) {
            throw new FciException(
                    ApiErrorCode.JOB_PARAMETER_INVALID,
                    new Object[] {"payPeriod"},
                    List.of(),
                    null);
        }
        long id =
                jobs.enqueue(
                        context, BackgroundJobs.Kind.PAYSLIP_BULK, unit, params(unit, yymm, level));
        return new BulkFile(id, "PENDING", 0, 0);
    }

    /** The user's newest O-01 bulk file of the month and level, if one was asked for. */
    @Transactional(readOnly = true)
    public Optional<BulkFile> bulkStatus(
            AccessContext context, String yymm, PayslipRegisterService.Level level) {
        policy.require(context, O_01, "VIEW");
        long unit = unitOf(context);
        return jobs.latest(
                        BackgroundJobs.Kind.PAYSLIP_BULK,
                        context.loginId(),
                        params(unit, yymm, level))
                .map(j -> new BulkFile(j.id(), j.status(), j.written(), j.skipped()));
    }

    /**
     * A finished O-01 bulk file and its name; another user's or another kind's job reads as none.
     */
    public record Download(String name, byte[] zip, long files) {}

    /**
     * The ZIP of the user's own O-01 bulk job: only its requester, only while the requester still
     * holds O-01 VIEW at the job's unit; a job not yet completed is 409 with its state.
     */
    @Transactional(readOnly = true)
    public Download bulkFile(AccessContext context, String sourceIp, long jobRequestId) {
        policy.require(context, O_01, "VIEW");
        BackgroundJobs.Job job =
                jobs.find(BackgroundJobs.Kind.PAYSLIP_BULK, jobRequestId)
                        .filter(j -> context.loginId().equals(j.requestedByLogin()))
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireRecordAt(context, O_01, "VIEW", job.locationId());
        if (!job.completed()) {
            throw new FciException(
                    ApiErrorCode.PDF_BULK_NOT_READY, new Object[] {job.status()}, List.of(), null);
        }
        DocumentStore.AttachmentView newest =
                documents.active(OUTPUT_ENTITY, String.valueOf(jobRequestId)).stream()
                        .reduce((a, b) -> b)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        DocumentStore.Content content =
                documents.read(
                        context.loginId(),
                        sourceIp,
                        OUTPUT_ENTITY,
                        String.valueOf(jobRequestId),
                        newest.id());
        String period = job.params().getOrDefault("payPeriod", "").replace("-", "");
        return new Download("payslips-" + period, content.bytes(), job.written());
    }

    private long unitOf(AccessContext context) {
        return org.cycleUnitOf(context.locationId())
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND))
                .getId();
    }

    private static YearMonth month(String yymm) {
        try {
            return YearMonth.parse(yymm == null ? "" : yymm, YYMM);
        } catch (java.time.format.DateTimeParseException e) {
            throw new FciException(
                    CommonErrorCode.VALIDATION_FAILED, new Object[] {"yymm"}, List.of(), null);
        }
    }
}
