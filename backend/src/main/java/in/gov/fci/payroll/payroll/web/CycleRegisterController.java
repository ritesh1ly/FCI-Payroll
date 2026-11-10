package in.gov.fci.payroll.payroll.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.payroll.orchestration.CycleUndo;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * C-01 Gate Override & Undo / Reversal Register (SRS-15-17, SRS-15-10; DDL {@code
 * undo_reversal_register}): every accepted gate override and every controlled undo of the session's
 * cycle unit, newest first, paged, and its export (SRS-G-13).
 */
@RestController
@Profile("online")
@RequestMapping("/v1/cycle-register-entries")
public class CycleRegisterController {

    private final CurrentAccess contexts;
    private final CycleUndo undo;
    private final ExportStreaming exports;
    private final Paging paging;

    public CycleRegisterController(
            CurrentAccess contexts, CycleUndo undo, ExportStreaming exports, Paging paging) {
        this.contexts = contexts;
        this.undo = undo;
        this.exports = exports;
        this.paging = paging;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('C-01','VIEW')")
    public ResponseEntity<Paged<CycleUndo.RegisterEntry>> register(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(undo.register(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/export")
    @PreAuthorize("@authz.gate('C-01','EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        // The session is read here, on the request thread: the rows are written on an export
        // thread.
        AccessContext context = contexts.current();
        exports.checkSize(undo.exportSize(context));
        return exports.stream(
                "gate-override-undo-register", format, sink -> undo.export(context, sink));
    }
}
