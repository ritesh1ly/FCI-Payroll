package in.gov.fci.payroll.employee.api;

import java.time.LocalDate;

/**
 * The person record of a labour member (plan 20 phase 20a; SRS-2-27, SRS-2-30; D20-1). Labour are
 * not in HRMS: the Labour Master (M-02, module 20) keeps them in Payroll, and the person anchor
 * every payroll table keys on is an {@code employee} row of the labour payroll type. The employee
 * module owns that table; the labour module writes it only through this port, in the transaction of
 * the approved Labour Master change.
 */
public interface LabourPersons {

    /**
     * The person as the approved Labour Master entry gives it.
     *
     * @param labourCode the generated labour code, also the {@code emp_num}
     * @param payrollType DEPT_LABOUR, DPS_LABOUR or NWNP_LABOUR
     * @param locationId the depot (as the migrated labour; the cycle unit follows from it)
     * @param bankBranchId the branch of the IFSC entered (bank_branch master)
     * @param retirementDate from the date of birth (A-07 RETIREMENT_AGE), or null while unset
     * @param casualLabour the Casual Labour indicator (SRS-G-55), processed in the same payroll
     */
    record Person(
            String labourCode,
            String payrollType,
            String firstName,
            String lastName,
            String fatherName,
            String gender,
            LocalDate birthDate,
            LocalDate joiningDate,
            long designationId,
            long locationId,
            Long depotLocationId,
            Long zoneId,
            String panNumber,
            String cpfCode,
            String uanNumber,
            String mobileNumber,
            String bankAccountNumber,
            Long bankBranchId,
            LocalDate retirementDate,
            boolean casualLabour) {}

    /**
     * Writes the person (ACTIVE, IN_SERVICE, record origin ENTERED).
     *
     * @return the employee id
     */
    long create(Person person, String actor);

    /** Amends the person's Labour Master fields (the labour code and payroll type never change). */
    void amend(long employeeId, Person person, String actor);

    /**
     * SRS-2-59: an approved name or date-of-birth correction (a null argument keeps the value; the
     * retirement date follows a corrected date of birth).
     */
    void correct(
            long employeeId,
            String firstName,
            String lastName,
            LocalDate birthDate,
            LocalDate retirementDate);

    /**
     * SRS-2-6 / SRS-2-49: the person's depot, office and zone after an approved transfer, or the
     * designation after an approved designation change (a null argument keeps the value).
     */
    void move(
            long employeeId,
            Long locationId,
            Long depotLocationId,
            Long zoneId,
            Long designationId);

    /**
     * SRS-2-30 / SRS-10-19: the labour member is separated from the date (employment status
     * SEPARATED); the Full &amp; Final Settlement case opens on {@link
     * EmployeeEvents.EmployeeSeparated}.
     */
    void separate(
            long employeeId,
            String separationType,
            LocalDate date,
            String reason,
            String decidedBy);

    /**
     * NWNP Reinstate Details (plan 22 phase 22c): the separated labour member is in service again
     * from the date; the modules that follow the member (the CPF ledger clears the ex-member
     * interest stop, SRS-6-9) hear {@link EmployeeEvents.EmployeeLifecycleApplied} REINSTATEMENT.
     */
    void reinstate(long employeeId, LocalDate date, String decidedBy);
}
