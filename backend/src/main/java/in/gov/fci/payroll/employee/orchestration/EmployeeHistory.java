package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.persistence.EmployeeRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * M-01 employee records for X-04 and the M-01 History button (SRS-20-29: "including M-01"), key =
 * EIS number, at the employee's office (scope). The record is one current row: as-of is rebuilt
 * from the field-level audit rows of its Payroll-owned fields (approved changes, SRS-G-36) and of
 * the HRMS events applied to it (status, transfer). Field names are those the audit rows carry; the
 * bank account stays masked as audited (R-PII).
 */
@Component
public class EmployeeHistory implements RecordHistorySource {

    static final String SCREEN = "M-01";

    private final EmployeeRepository employees;

    public EmployeeHistory(EmployeeRepository employees) {
        this.employees = employees;
    }

    @Override
    public String entity() {
        return EmployeeApprovalEffect.ENTITY;
    }

    @Override
    public Optional<HistoryRecord> find(String key) {
        return employees
                .findByEmpNum(key)
                .map(
                        e ->
                                HistoryRecord.ofCurrent(
                                        EmployeeApprovalEffect.ENTITY,
                                        e.getEmpNum(),
                                        SCREEN,
                                        e.getLocationId(),
                                        image(e)));
    }

    static Map<String, String> image(Employee e) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("mobileNumber", e.getMobileNumber());
        image.put("emailAddress", e.getEmailAddress());
        image.put(
                "bankAccount",
                EmployeeApprovalEffect.mask("bankAccount", e.getBankAccountNumber()));
        image.put("employment_status", e.getEmploymentStatus());
        image.put(
                "location_id",
                e.getLocationId() == null ? null : String.valueOf(e.getLocationId()));
        return image;
    }
}
