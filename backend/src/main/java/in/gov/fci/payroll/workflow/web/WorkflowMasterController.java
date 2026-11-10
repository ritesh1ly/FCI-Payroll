package in.gov.fci.payroll.workflow.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.workflow.business.WorkflowRules;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction.OfficeLevel;
import in.gov.fci.payroll.workflow.orchestration.WorkflowMasterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-08 Approval Workflow Master (Module 02 F-1; SRS-22-12, 22-15, 22-16, 22-17, 22-19, SRS-G-28).
 *
 * <pre>
 * GET  /v1/admin/workflow-actions             every action, version in force, pending, history   A-08 VIEW
 * POST /v1/admin/workflow-actions             propose a new action or a new version (202)         A-08 CREATE / EDIT
 * GET  /v1/admin/workflow-actions/unstaffed   items with no approver in scope (SRS-G-28)          A-08 VIEW
 * </pre>
 *
 * The service decides CREATE or EDIT (a new action or a version of an existing one) and checks it.
 */
@RestController
@Profile("online")
@RequestMapping("/v1/admin/workflow-actions")
public class WorkflowMasterController {

    public record LevelRequest(
            int levelNo,
            @Size(max = 30) @PrintableAscii String roleCode,
            OfficeLevel officeLevel,
            boolean otpAtLevel) {}

    public record RouteRequest(
            @Size(max = 40) @PrintableAscii String actionCode,
            @Size(max = 200) @PrintableAscii String actionName,
            @Size(max = 40) @PrintableAscii String moduleCode,
            @Size(max = 20) @PrintableAscii String screenCode,
            OfficeLevel officeLevel,
            int levelCount,
            OtpGatedAction otpAction,
            boolean attachmentRequired,
            Integer targetTatHours,
            LocalDate effectiveFrom,
            @NotNull @Size(max = 3) List<@Valid @NotNull LevelRequest> levels,
            // Optional (absent = No, SRS-G-13): a primitive would refuse every caller that omits
            // it.
            Boolean bulkApprovalAllowed,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final WorkflowMasterService master;
    private final Paging paging;

    public WorkflowMasterController(
            CurrentAccess contexts, WorkflowMasterService master, Paging paging) {
        this.paging = paging;
        this.contexts = contexts;
        this.master = master;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('A-08','VIEW')")
    public ResponseEntity<Paged<WorkflowMasterService.ActionView>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(Paged.slice(master.list(contexts.current()), paging.resolve(page, size)));
    }

    @PostMapping
    @PreAuthorize("@authz.gate('A-08','VIEW')")
    public ResponseEntity<WorkflowMasterService.VersionView> propose(
            @Valid @RequestBody RouteRequest r) {
        WorkflowRules.ProposedRoute route =
                new WorkflowRules.ProposedRoute(
                                r.actionCode(),
                                r.actionName(),
                                r.moduleCode(),
                                r.screenCode() == null || r.screenCode().isBlank()
                                        ? null
                                        : r.screenCode(),
                                r.officeLevel(),
                                r.levelCount(),
                                r.otpAction(),
                                r.attachmentRequired(),
                                r.targetTatHours(),
                                r.effectiveFrom(),
                                r.levels().stream()
                                        .map(
                                                l ->
                                                        new WorkflowRules.ProposedLevel(
                                                                l.levelNo(),
                                                                l.roleCode(),
                                                                l.officeLevel(),
                                                                l.otpAtLevel()))
                                        .toList())
                        .bulk(Boolean.TRUE.equals(r.bulkApprovalAllowed()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(master.propose(contexts.current(), route, r.reason()));
    }

    @GetMapping("/unstaffed")
    @PreAuthorize("@authz.gate('A-08','VIEW')")
    public ResponseEntity<Paged<WorkflowMasterService.UnstaffedItem>> unstaffed(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                Paged.slice(master.unstaffed(contexts.current()), paging.resolve(page, size)));
    }
}
