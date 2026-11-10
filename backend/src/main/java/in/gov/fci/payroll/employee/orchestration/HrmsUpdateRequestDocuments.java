package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The optional order copy of an HRMS Update Request (plan 16 phase 16c, D16-32; SRS-1-70 "may
 * attach the order"; DDL: "a document_attachment on entity HRMS_UPDATE_REQUEST"). The member
 * attaches to an own request (eSeva, E-01 CREATE); the I-21 desk of the member's cycle unit
 * attaches to and reads any request it may raise (I-21 REQUEST). An attachment of another request,
 * or a request outside the caller's scope, reads as not found.
 */
@Service
@Loggable
public class HrmsUpdateRequestDocuments {

    static final String ENTITY = "HRMS_UPDATE_REQUEST";

    /** The order's particulars and file. */
    public record Order(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    private final JdbcTemplate jdbc;
    private final OrgDirectory org;
    private final Authorization policy;
    private final DocumentStore documents;

    public HrmsUpdateRequestDocuments(
            JdbcTemplate jdbc, OrgDirectory org, Authorization policy, DocumentStore documents) {
        this.jdbc = jdbc;
        this.org = org;
        this.policy = policy;
        this.documents = documents;
    }

    /** Attaches the order copy; {@code mine} = the member's own request (eSeva). */
    @Transactional
    public DocumentStore.AttachmentView attach(
            AccessContext context, long id, boolean mine, Order order) {
        authorise(context, id, mine, "CREATE");
        return documents.attach(
                context.loginId(),
                new DocumentStore.NewAttachment(
                        ENTITY,
                        String.valueOf(id),
                        order.documentType(),
                        order.reference(),
                        order.orderDate(),
                        order.issuingAuthority(),
                        order.fileName(),
                        order.content(),
                        true));
    }

    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> list(AccessContext context, long id, boolean mine) {
        authorise(context, id, mine, "SELF");
        return documents.active(ENTITY, String.valueOf(id));
    }

    @Transactional
    public DocumentStore.Content read(
            AccessContext context, String sourceIp, long id, boolean mine, long attachmentId) {
        authorise(context, id, mine, "SELF");
        return documents.read(
                context.loginId(), sourceIp, ENTITY, String.valueOf(id), attachmentId);
    }

    /**
     * The member's own request (eSeva action), or a request of the desk's cycle unit (I-21
     * REQUEST); anything else is not found.
     */
    private void authorise(AccessContext context, long id, boolean mine, String esevaAction) {
        Map<String, Object> row =
                jdbc
                        .queryForList(
                                "SELECT r.employee_id, e.location_id FROM hrms_update_request r"
                                        + " JOIN employee e ON e.employee_id = r.employee_id WHERE"
                                        + " r.hrms_update_request_id = ?",
                                id)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        long employeeId = ((Number) row.get("EMPLOYEE_ID")).longValue();
        if (mine) {
            policy.require(context, HrmsUpdateRequestService.ESEVA, esevaAction);
            if (context.employeeId() == null || context.employeeId() != employeeId) {
                policy.recordForeignAttempt(
                        context,
                        HrmsUpdateRequestService.ESEVA,
                        esevaAction,
                        "hrms_update_request",
                        id);
                throw new FciException(CommonErrorCode.NOT_FOUND);
            }
            return;
        }
        OrgLocation unit =
                org.cycleUnitOf(((Number) row.get("LOCATION_ID")).longValue())
                        .orElseThrow(() -> new FciException(ApiErrorCode.CYCLE_NO_UNIT));
        policy.requireRecordAt(context, HrmsChangeService.SCREEN, "REQUEST", unit.getId());
    }
}
