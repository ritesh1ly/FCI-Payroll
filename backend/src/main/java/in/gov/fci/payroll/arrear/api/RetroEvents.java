package in.gov.fci.payroll.arrear.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The one retrospective mechanism (plan 13 phase 13b; SRS-8-13, SRS-21-1, SRS-21-7, SRS-21-9,
 * SRS-21-11): the module owning an approved back-dated change raises a single-member arrear event
 * here, in the transaction of its change; the arrear module recomputes each locked month from the
 * effective date once that transaction has committed and places the differentials on I-08 for the
 * Payroll Maker. The caller never computes the arrear and has no workflow of its own.
 */
public interface RetroEvents {

    /**
     * A back-dated change of one member.
     *
     * @param eventCode the arrear event type (LEAVE, PENALTY, REIMBURSEMENT, PROMOTION, OTHER)
     * @param sourceModule the owning module (DDL {@code arrear_event.source_module}, e.g. LEAVE)
     * @param sourceEntity and {@code sourceKey}: the change's record; one open event per record
     * @param reason the remark (SRS-21-9: mandatory)
     * @param raisedBy the login that approved the change
     */
    record Raise(
            long employeeId,
            LocalDate effectiveFrom,
            String eventCode,
            String sourceModule,
            String sourceEntity,
            String sourceKey,
            String reason,
            String raisedBy) {

        public Raise {
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            Objects.requireNonNull(eventCode, "eventCode");
            Objects.requireNonNull(sourceModule, "sourceModule");
            Objects.requireNonNull(sourceKey, "sourceKey");
            Objects.requireNonNull(raisedBy, "raisedBy");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("SRS-21-9: a back-dated event needs a remark");
            }
        }
    }

    /**
     * Raises the event (status RAISED) or answers the one already open for the same source record
     * (never twice).
     *
     * @return the arrear event id
     */
    long raise(Raise raise);

    /**
     * The header of an arrear run whose records the caller keeps in its own table (plan 13 phase
     * 13d: I-24 leave encashment arrears, paid by APPS invoice, never through the cycle): one
     * COMPUTED event linked to the revision order, with no {@code arrear_detail}.
     *
     * @param eventCode the arrear event type (LEAVE_ENCASHMENT)
     * @param circularId the revision order (M-25), or null
     */
    record Header(
            String eventCode,
            Long scopeLocationId,
            LocalDate effectiveFrom,
            String sourceModule,
            String sourceEntity,
            String sourceKey,
            String reference,
            Long circularId,
            String raisedBy) {}

    /**
     * Records the header in the caller's transaction.
     *
     * @return the arrear event id
     */
    long header(Header header);

    /**
     * One month's differential the owning module already knows (plan 13 phase 13g, D13-14): a
     * deduction it took or must still take, which no earnings recompute can find.
     *
     * @param yymm the month the differential belongs to (YYYYMM)
     * @param payCode the legacy pay code of the owning module's component for that month
     * @param amount signed: positive is paid to the member (a refund), negative is recovered
     */
    record Line(String yymm, int payCode, BigDecimal amount) {

        public Line {
            Objects.requireNonNull(yymm, "yymm");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /**
     * A back-dated change of one member whose differentials the owning module states (SRS-14-16,
     * SRS-21-10: the reversing arrear of a penalty waiver; SRS-9-23: a back-dated NPS amount). The
     * event is written COMPUTED with these lines; the Maker submits it on I-08 and the Checker
     * approves it like any other (SRS-8-14). It is never recomputed.
     */
    record Known(
            long employeeId,
            String eventCode,
            String sourceModule,
            String sourceEntity,
            String sourceKey,
            String reason,
            String raisedBy,
            List<Line> lines) {

        public Known {
            Objects.requireNonNull(eventCode, "eventCode");
            Objects.requireNonNull(sourceModule, "sourceModule");
            Objects.requireNonNull(sourceKey, "sourceKey");
            Objects.requireNonNull(raisedBy, "raisedBy");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("SRS-21-9: a back-dated event needs a remark");
            }
            lines = List.copyOf(lines);
            if (lines.isEmpty()) {
                throw new IllegalArgumentException("A known differential needs at least one line");
            }
        }
    }

    /**
     * Records the event and its lines in the caller's transaction, or answers the one already open
     * for the same source record (never twice). A pay code with no component is a programming or
     * configuration error and rolls the caller back.
     *
     * @return the arrear event id
     */
    long known(Known known);
}
