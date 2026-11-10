package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.employee.persistence.EmployeeRepository;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The checker's decision on an M-01 change request raised before it was retired (plan 16 D16-19):
 * mobile, e-mail and bank account are HRMS-owned (CLAUDE.md section 2, R-SRC, SRS-G-45) and change
 * only through an I-21 task, so whatever the decision, nothing is applied; it only clears the
 * record's pending-change status. The entity name stays for the M-01 history of those items.
 */
@Component
public class EmployeeApprovalEffect {

    static final String ENTITY = "employee";

    private final EmployeeRepository employees;

    public EmployeeApprovalEffect(EmployeeRepository employees) {
        this.employees = employees;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (decision.concerns(ENTITY)) {
            employees
                    .findByEmpNum(decision.entityKey())
                    .ifPresent(
                            e -> {
                                e.clearChangePending(decision.decidedAt());
                                employees.save(e);
                            });
        }
    }

    /** Bank account numbers are shown masked (last four digits). */
    public static String mask(String field, String value) {
        if (value == null || !"bankAccount".equals(field) || value.length() <= 4) {
            return value;
        }
        return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }
}
