package in.gov.fci.payroll.platform.notification;

import java.util.List;

/**
 * Published in the transaction that releases members' documents outside the cycle lock (OD-148:
 * Form 16 on its release, C-10, SRS-7-9), so that their e-mails are queued in the same transaction
 * and roll back with it. The e-mail itself goes out later and never blocks the release (SRS-17-12).
 *
 * @param documentType {@code FORM16}
 * @param orgUnitId the releasing office (its Payroll Maker sees the e-mail status)
 * @param actor the login of the approver, for the audit
 */
public record MemberDocumentsReleased(
        String documentType, long orgUnitId, String actor, List<Item> items) {

    public MemberDocumentsReleased {
        items = List.copyOf(items);
    }

    /** One member's document: the period (e.g. 2025-26) and its source key (the certificate). */
    public record Item(long employeeId, String periodKey, String sourceKey) {}
}
