package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.employee.api.LabourPersons;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The labour member's person record (plan 20 phase 20a; SRS-2-27, SRS-2-30; D20-1): an {@code
 * employee} row of the labour payroll type, written only in the transaction of the approved Labour
 * Master change (the audit is the labour module's, with the approval). HRMS never sends labour. The
 * labour CPF code stays on {@code labour_profile} (DDL Section 4): only the fund type is set here.
 */
@Component
public class LabourPersonWriter implements LabourPersons {

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public LabourPersonWriter(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long create(Person p, String actor) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO employee (emp_num, first_name, last_name,"
                                        + " father_name, gender, birth_date, comp_joining_date,"
                                        + " employee_type, emp_lbr_flag, designation_id,"
                                        + " location_id, depot_location_id, parent_zone_id,"
                                        + " pan_number, provident_fund_type, cpf_code,"
                                        + " cpf_code_origin, uan_number, mobile_number,"
                                        + " bank_account_number, bank_branch_id, retirement_date,"
                                        + " casual_labour_flag, employment_status, record_status,"
                                        + " record_origin) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Y', ?,"
                                        + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'IN_SERVICE',"
                                        + " 'ACTIVE', 'ENTERED')",
                                    new String[] {"employee_id"});
                    int i = bind(ps, p);
                    ps.setObject(
                            i,
                            p.retirementDate() == null ? null : Date.valueOf(p.retirementDate()),
                            Types.DATE);
                    ps.setString(i + 1, p.casualLabour() ? "Y" : "N");
                    return ps;
                },
                key);
        return GeneratedKeys.id(key);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void amend(long employeeId, Person p, String actor) {
        jdbc.update(
                "UPDATE employee SET first_name = ?, last_name = ?, father_name = ?, gender = ?,"
                    + " birth_date = ?, comp_joining_date = ?, designation_id = ?, location_id = ?,"
                    + " depot_location_id = ?, parent_zone_id = ?, pan_number = ?,"
                    + " provident_fund_type = ?, cpf_code = ?, cpf_code_origin = ?, uan_number = ?,"
                    + " mobile_number = ?, bank_account_number = ?, bank_branch_id = ?,"
                    + " retirement_date = ?, casual_labour_flag = ?, row_version = row_version + 1,"
                    + " updated_at = SYSTIMESTAMP WHERE employee_id = ? AND emp_lbr_flag = 'Y'",
                p.firstName(),
                p.lastName(),
                p.fatherName(),
                p.gender(),
                Date.valueOf(p.birthDate()),
                Date.valueOf(p.joiningDate()),
                p.designationId(),
                p.locationId(),
                p.depotLocationId(),
                p.zoneId(),
                p.panNumber(),
                p.cpfCode() == null ? "NONE" : "CPF",
                null,
                null,
                p.uanNumber(),
                p.mobileNumber(),
                p.bankAccountNumber(),
                p.bankBranchId(),
                p.retirementDate() == null ? null : Date.valueOf(p.retirementDate()),
                p.casualLabour() ? "Y" : "N",
                employeeId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void correct(
            long employeeId,
            String firstName,
            String lastName,
            LocalDate birthDate,
            LocalDate retirementDate) {
        if (birthDate == null) {
            jdbc.update(
                    "UPDATE employee SET first_name = ?, last_name = ?, row_version = row_version"
                            + " + 1, updated_at = SYSTIMESTAMP WHERE employee_id = ? AND"
                            + " emp_lbr_flag = 'Y'",
                    firstName,
                    lastName,
                    employeeId);
            return;
        }
        jdbc.update(
                "UPDATE employee SET birth_date = ?, retirement_date = ?, row_version = row_version"
                        + " + 1, updated_at = SYSTIMESTAMP WHERE employee_id = ? AND emp_lbr_flag ="
                        + " 'Y'",
                Date.valueOf(birthDate),
                retirementDate == null ? null : Date.valueOf(retirementDate),
                employeeId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void move(
            long employeeId,
            Long locationId,
            Long depotLocationId,
            Long zoneId,
            Long designationId) {
        jdbc.update(
                "UPDATE employee SET location_id = COALESCE(?, location_id), depot_location_id ="
                    + " COALESCE(?, depot_location_id), parent_zone_id = COALESCE(?,"
                    + " parent_zone_id), designation_id = COALESCE(?, designation_id), row_version"
                    + " = row_version + 1, updated_at = SYSTIMESTAMP WHERE employee_id = ? AND"
                    + " emp_lbr_flag = 'Y'",
                locationId,
                depotLocationId,
                zoneId,
                designationId,
                employeeId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void separate(
            long employeeId,
            String separationType,
            LocalDate date,
            String reason,
            String decidedBy) {
        jdbc.update(
                "UPDATE employee SET employment_status = 'SEPARATED', row_version = row_version +"
                    + " 1, updated_at = SYSTIMESTAMP WHERE employee_id = ? AND emp_lbr_flag = 'Y'",
                employeeId);
        events.publishEvent(
                new EmployeeEvents.EmployeeSeparated(
                        employeeId, null, separationType, date, reason, decidedBy));
    }

    @Override
    public void reinstate(long employeeId, LocalDate date, String decidedBy) {
        jdbc.update(
                "UPDATE employee SET employment_status = 'IN_SERVICE', row_version = row_version +"
                    + " 1, updated_at = SYSTIMESTAMP WHERE employee_id = ? AND emp_lbr_flag = 'Y'",
                employeeId);
        events.publishEvent(
                new EmployeeEvents.EmployeeLifecycleApplied(
                        employeeId, null, "REINSTATEMENT", date, null, decidedBy));
    }

    /** Binds the person's columns in the INSERT's order; answers the next parameter index. */
    private static int bind(PreparedStatement ps, Person p) throws java.sql.SQLException {
        ps.setString(1, p.labourCode());
        ps.setString(2, p.firstName());
        ps.setString(3, p.lastName());
        ps.setString(4, p.fatherName());
        ps.setString(5, p.gender());
        ps.setDate(6, Date.valueOf(p.birthDate()));
        ps.setDate(7, Date.valueOf(p.joiningDate()));
        ps.setString(8, p.payrollType());
        ps.setLong(9, p.designationId());
        ps.setLong(10, p.locationId());
        ps.setObject(11, p.depotLocationId(), Types.BIGINT);
        ps.setObject(12, p.zoneId(), Types.BIGINT);
        ps.setString(13, p.panNumber());
        ps.setString(14, p.cpfCode() == null ? "NONE" : "CPF");
        // The labour CPF code is on labour_profile only (DDL trg_labour_cpf_xuniq /
        // trg_employee_cpf_xuniq).
        ps.setString(15, null);
        ps.setString(16, null);
        ps.setString(17, p.uanNumber());
        ps.setString(18, p.mobileNumber());
        ps.setString(19, p.bankAccountNumber());
        ps.setObject(20, p.bankBranchId(), Types.BIGINT);
        return 21;
    }
}
