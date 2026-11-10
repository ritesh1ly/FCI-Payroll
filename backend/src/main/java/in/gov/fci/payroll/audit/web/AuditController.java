package in.gov.fci.payroll.audit.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.audit.orchestration.AuditQueryService;
import in.gov.fci.payroll.platform.approval.AttachmentPresence;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** A-05 Audit trail: search and CSV export. */
@RestController
@Profile("online")
@RequestMapping("/v1/admin/audit")
public class AuditController {

    private final CurrentAccess contexts;
    private final AuditQueryService audit;

    private final ExportStreaming exports;
    private final Paging paging;

    public AuditController(
            ExportStreaming exports,
            CurrentAccess contexts,
            AuditQueryService audit,
            Paging paging) {
        this.paging = paging;
        this.exports = exports;
        this.contexts = contexts;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('A-05','VIEW')")
    public ResponseEntity<AuditQueryService.PageView> audit(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) @Size(max = 100) String entity,
            @RequestParam(required = false) @Size(max = 150) String actor,
            @RequestParam(required = false) @Size(max = 100) String field,
            @RequestParam(required = false) @Size(max = 30) String objectGroup,
            @RequestParam(required = false) @Size(max = 30) String userGroup,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageSpec spec = paging.resolve(page, size);
        return noStore(
                audit.search(
                        contexts.current(),
                        new AuditQueryService.Filter(
                                from, to, entity, actor, field, objectGroup, userGroup, q),
                        spec.page(),
                        spec.size()));
    }

    /** X-04 tiles, each in the reader's own scope. */
    @GetMapping("/summary")
    @PreAuthorize("@authz.gate('A-05','VIEW')")
    public ResponseEntity<AuditQueryService.Summary> summary() {
        return noStore(audit.summary(contexts.current()));
    }

    /** SRS l.1248: the order document of one audit row, served as an attachment. */
    @GetMapping("/{id}/order-documents/{documentId}")
    @PreAuthorize("@authz.gate('A-05','VIEW')")
    public ResponseEntity<byte[]> orderDocument(
            @PathVariable long id, @PathVariable long documentId, HttpServletRequest request) {
        AttachmentPresence.FileContent file =
                audit.orderDocument(contexts.current(), request.getRemoteAddr(), id, documentId);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName())
                                .build()
                                .toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes());
    }

    @GetMapping("/export")
    @PreAuthorize("@authz.gate('A-05','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportAudit(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) @Size(max = 100) String entity,
            @RequestParam(required = false) @Size(max = 150) String actor,
            @RequestParam(required = false) @Size(max = 100) String field,
            @RequestParam(required = false) @Size(max = 30) String objectGroup,
            @RequestParam(required = false) @Size(max = 30) String userGroup,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        AuditQueryService.Filter filter =
                new AuditQueryService.Filter(
                        from, to, entity, actor, field, objectGroup, userGroup, q);
        exports.checkSize(audit.exportSize(context, filter));
        return exports.stream("audit-trail", format, sink -> audit.export(context, filter, sink));
    }
}
