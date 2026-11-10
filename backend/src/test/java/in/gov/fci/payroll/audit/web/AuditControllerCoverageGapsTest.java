package in.gov.fci.payroll.audit.web;

import static in.gov.fci.payroll.ControllerKit.from;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ControllerKit;
import in.gov.fci.payroll.audit.orchestration.AuditQueryService;
import in.gov.fci.payroll.platform.approval.AttachmentPresence;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * A-05 Audit Trail Viewer, the order document of an audit row (SRS l.1248, SRS-20-24): it is read
 * for the session from the caller's address and served as an attachment of its own type and name.
 */
class AuditControllerCoverageGapsTest {

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final AuditQueryService audit = mock(AuditQueryService.class);
    private final AuditController controller =
            new AuditController(new ControllerKit().exports, contexts, audit, mock(Paging.class));

    @Test
    void SRS_20_24_anAuditRowsOrderDownloadsAsAnAttachmentOfItsType() {
        when(contexts.current()).thenReturn(context);
        var request = from("10.0.0.11");
        when(audit.orderDocument(context, "10.0.0.11", 40L, 3L))
                .thenReturn(
                        new AttachmentPresence.FileContent(
                                "sanction.pdf", "application/pdf", new byte[] {9, 9}));

        ResponseEntity<byte[]> r = controller.orderDocument(40L, 3L, request);

        assertThat(r.getBody()).containsExactly(9, 9);
        assertThat(r.getHeaders().getContentDisposition().isAttachment()).isTrue();
        assertThat(r.getHeaders().getContentDisposition().getFilename()).isEqualTo("sanction.pdf");
        assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    }
}
