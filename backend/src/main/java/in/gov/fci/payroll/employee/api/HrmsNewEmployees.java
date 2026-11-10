package in.gov.fci.payroll.employee.api;

import java.time.LocalDate;

/**
 * The one way a new staff record enters Payroll (SRS-1-11, SRS-1-13, SRS-1-52; INTF_PAY_EMP_BOB;
 * plan 16 D16-34): the HRMS interface (module 23) calls it for every new employee it receives. No
 * Payroll screen creates an employee. The record is created in Payroll Setup Pending with every
 * HRMS-sourced field filled; a record matching an existing employee on PAN or Aadhaar is not
 * created and is answered HELD_DUPLICATE with the matching record, for the interface exception
 * queue and the correction in HRMS.
 */
public interface HrmsNewEmployees {

    /**
     * One new employee as HRMS sends it.
     *
     * @param eis the HRMS employee number (the record key, unique within its CPF parent zone)
     * @param payScaleCode {@code pay_scale_band.band_code}; its scale type gives the pay pattern
     * @param postingCode {@code org_location.location_code} of the present place of posting
     * @param providentFundType GPF or NPS as HRMS holds it for a CDA or FNB member; ignored for IDA
     *     (CPF, SRS-1-17)
     * @param aadhaar the 12-digit Aadhaar (kept for the duplicate check only, never shown whole)
     */
    record NewEmployee(
            String eis,
            String firstName,
            String lastName,
            String fatherName,
            String gender,
            LocalDate birthDate,
            LocalDate joiningDate,
            String designationCode,
            String payScaleCode,
            String postingCode,
            String panNumber,
            String aadhaar,
            String bankAccountNumber,
            String ifsc,
            String cpfCode,
            String providentFundType,
            String mobileNumber,
            String emailAddress) {}

    /** What became of a received record (DDL record_status PAYROLL_SETUP_PENDING when CREATED). */
    enum Outcome {
        CREATED,
        ALREADY_RECEIVED,
        HELD_DUPLICATE,
        HELD_INVALID
    }

    /**
     * The answer.
     *
     * @param employeeId the record created or already received, else null
     * @param matchedEis the existing employee a HELD_DUPLICATE matched
     * @param defect what is wrong with a HELD_INVALID record (the field), else null
     */
    record Received(Outcome outcome, Long employeeId, String matchedEis, String defect) {}

    /** Records the employee; the same EIS and PAN again answers ALREADY_RECEIVED (BR-INT-004). */
    Received receive(NewEmployee employee);
}
