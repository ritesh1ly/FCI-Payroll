package in.gov.fci.payroll.general.business.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One record of an HRMS export file as read through the configured column map, before validation.
 * Values the file did not hold, or could not be read, are null; {@code readError} names the field
 * that could not be read (a malformed date or number), so the line is rejected with that reason.
 *
 * @param recordType ATTENDANCE_LEAVE for fallback (a); SUSPENSION, ABSCONDING or REVOCATION for (b)
 * @param raw the line as uploaded (kept on the line row for the reconciliation report)
 */
public record FallbackRecord(
        int lineNo,
        String employeeNumber,
        String recordType,
        LocalDate periodFrom,
        LocalDate periodTo,
        BigDecimal payDays,
        BigDecimal lwpDays,
        BigDecimal halfPayDays,
        String otherLeaveCode,
        BigDecimal otherLeaveDays,
        LocalDate eventEffectiveDate,
        String orderNo,
        LocalDate orderDate,
        String issuingAuthority,
        String raw,
        String readError) {}
