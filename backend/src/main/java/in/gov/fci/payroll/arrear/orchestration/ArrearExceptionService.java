package in.gov.fci.payroll.arrear.orchestration;

import in.gov.fci.payroll.arrear.business.ArrearErrorCode;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * I-08 Headquarters exception for an event beyond the back-date limit (SRS-8-6, BR-EMPR-007; DDL
 * {@code arrear_event.beyond_window_flag}, {@code hq_exception_approval_id}): the Payroll Maker
 * asks for it with a reason and the order, an Active M-25 circular (SRS-G-30); a Payroll Checker at
 * Headquarters approves it (route {@value #ACTION}). Approved, the event is computed (a stated
 * event becomes COMPUTED) and goes the usual way to Approve &amp; Merge; rejected, it stays RAISED
 * and is never computed.
 */
@Service
@Loggable
public class ArrearExceptionService {

    public static final String ACTION = "I08_HQ_EXCEPTION";
    static final String ENTITY = "arrear_event_hq_exception";
    private static final Pattern CIRCULAR = Pattern.compile("\"circularId\":(\\d+)");

    private final ArrearRegisterService register;
    private final RetroEventService retro;
    private final MakerChecker approvals;
    private final Circulars circulars;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ArrearExceptionService(
            ArrearRegisterService register,
            RetroEventService retro,
            MakerChecker approvals,
            Circulars circulars,
            JdbcTemplate jdbc,
            AuditService audit) {
        this.register = register;
        this.retro = retro;
        this.approvals = approvals;
        this.circulars = circulars;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** SRS-8-6: the Maker asks Headquarters to process an event beyond the back-date limit. */
    @Transactional
    public SubmittedApproval request(
            AccessContext context, long eventId, String reason, Long circularId) {
        Map<String, Object> e = register.requireEvent(context, eventId, "SUBMIT");
        if (!"Y".equals(e.get("BEYOND_WINDOW_FLAG"))
                || e.get("HQ_EXCEPTION_APPROVAL_ID") != null
                || !"RAISED".equals(e.get("EVENT_STATUS"))) {
            throw new FciException(
                    ArrearErrorCode.STATE,
                    new Object[] {e.get("EVENT_STATUS"), "RAISED beyond the back-date limit"},
                    List.of(),
                    null);
        }
        if (reason == null || reason.isBlank()) {
            throw new FciException(ArrearErrorCode.REMARK);
        }
        circulars.requireActive(circularId);
        Long location =
                e.get("SCOPE_LOCATION_ID") == null
                        ? null
                        : ((Number) e.get("SCOPE_LOCATION_ID")).longValue();
        return approvals.submit(
                context,
                new Submission(
                        ACTION,
                        ArrearRegisterService.SCREEN,
                        "SUBMIT",
                        ENTITY,
                        String.valueOf(eventId),
                        ActionType.UPDATE,
                        location,
                        "{\"circularId\":" + circularId + "}",
                        new java.sql.Date(((java.util.Date) e.get("EFFECTIVE_FROM")).getTime())
                                .toLocalDate(),
                        reason.strip()),
                (OtpProof) null);
    }

    /**
     * SRS-8-6: Headquarters' decision. Approved: the exception and the order are recorded on the
     * event, the order is linked to it, and the event is computed. Rejected or returned: audited;
     * the event stays RAISED.
     */
    @EventListener
    public void on(ApprovalDecided d) {
        if (!d.concerns(ENTITY)) {
            return;
        }
        long eventId = Long.parseLong(d.entityKey());
        if (d.approved()) {
            Long circularId = circular(d.payload());
            jdbc.update(
                    "UPDATE arrear_event SET hq_exception_approval_id = ?, circular_order_id ="
                            + " COALESCE(circular_order_id, ?) WHERE arrear_event_id = ?",
                    d.approvalId(),
                    circularId,
                    eventId);
            if (circularId != null) {
                circulars.link(
                        d.checkerLoginId(),
                        circularId,
                        Circulars.LinkType.TRANSACTION,
                        ArrearRegisterService.ENTITY,
                        d.entityKey(),
                        ArrearRegisterService.SCREEN);
            }
            retro.releaseException(eventId, d.checkerLoginId());
        }
        audit.record(
                d.checkerLoginId(),
                AuditService.Entry.change(
                                ArrearRegisterService.ENTITY,
                                d.entityKey(),
                                "hq_exception",
                                "REQUESTED",
                                d.approved() ? "APPROVED" : "REJECTED",
                                d.approved()
                                        ? "Headquarters exception beyond the back-date limit"
                                        : "Headquarters exception refused: " + d.reason())
                        .approval(d.approvalId())
                        .module(ArrearRegisterService.SCREEN));
    }

    private static Long circular(String payload) {
        Matcher m = CIRCULAR.matcher(payload == null ? "" : payload);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }
}
