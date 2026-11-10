package in.gov.fci.payroll.employee.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.employee.orchestration.EmployeeService;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee endpoints: employee master (M-01), eSeva (E-01) and the consolidated MIS head count
 * (R-01). Each has a screen-level {@code @authz.gate} and the record-level check in its service.
 *
 * <p>No field of the record is keyed here: contact and bank details, PAN and pay are HRMS-owned and
 * change only through an I-21 task (CLAUDE.md section 2, R-SRC, SRS-G-45; plan 16 D16-19). The
 * Payroll-owned allowance flags have their own endpoint ({@link EmployeeFlagController}).
 */
@RestController
@Profile("online")
public class EmployeeController {

    /** The M-01 status lines (Employee.displayStatus; EMPLOYEE_RECORD_STATUS list); empty = all. */
    static final String STATUS =
            "^(|PAYROLL_SETUP_PENDING|CHANGE_PENDING|PAN_CPF_MISSING|BANK_INVALID|ACTIVE)$";

    /** An office of the M-01 filter (org_location code); empty = all offices of the scope. */
    static final String OFFICE = "^(|[A-Za-z0-9-]{2,20})$";

    private final CurrentAccess contexts;
    private final EmployeeService employees;
    private final Paging paging;

    public EmployeeController(CurrentAccess contexts, EmployeeService employees, Paging paging) {
        this.paging = paging;
        this.contexts = contexts;
        this.employees = employees;
    }

    @GetMapping("/v1/employees")
    @PreAuthorize("@authz.gate('M-01','VIEW')")
    public ResponseEntity<Paged<EmployeeService.EmployeeRow>> employees(
            @RequestParam(required = false) @Size(max = 60) String q,
            @RequestParam(required = false) @Size(max = 10) String payPattern,
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Pattern(regexp = OFFICE) String office,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                employees.list(
                        contexts.current(),
                        q,
                        payPattern,
                        status,
                        office,
                        paging.resolve(page, size)));
    }

    /** SRS-1-11, SRS-NF-3: the M-01 tiles over the whole filtered scope, not the shown page. */
    @GetMapping("/v1/employees/counts")
    @PreAuthorize("@authz.gate('M-01','VIEW')")
    public ResponseEntity<EmployeeService.StatusCounts> counts(
            @RequestParam(required = false) @Size(max = 60) String q,
            @RequestParam(required = false) @Size(max = 10) String payPattern,
            @RequestParam(required = false) @Pattern(regexp = OFFICE) String office) {
        return noStore(employees.statusCounts(contexts.current(), q, payPattern, office));
    }

    @GetMapping("/v1/employees/{eis}")
    @PreAuthorize("@authz.gate('M-01','VIEW')")
    public ResponseEntity<EmployeeService.EmployeeDetail> employee(
            @PathVariable @Size(max = 20) String eis) {
        return noStore(employees.get(contexts.current(), eis));
    }

    @GetMapping("/v1/eseva/me")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<EmployeeService.EmployeeDetail> ownRecord() {
        return noStore(employees.own(contexts.current()));
    }

    @GetMapping("/v1/mis/headcount")
    @PreAuthorize("@authz.gate('R-01','VIEW')")
    public ResponseEntity<EmployeeService.HeadCountPage> headCount(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(employees.headCount(contexts.current(), paging.resolve(page, size)));
    }
}
