package in.gov.fci.payroll.payroll.api;

import java.util.Optional;

/**
 * SRS-15-3 / SRS-15-10, BR-CYC-006 (plan 05 D-10): a kind of payroll transaction that may be undone
 * before EBS posting under the controlled undo (Maker requests, Checker verifies, Administrator
 * authorises with OTP). The module that owns the transaction implements it as a Spring bean; Module
 * 05 runs the request, the route, the OTP, the posting check, the cycle lease and the register, and
 * calls {@link #undo} in the approval's transaction.
 *
 * <p>Which transactions are undoable, and what undoing each one does, is not named by the SRS
 * (PENDING 05e-types): none is registered until FCI names them, and a request for any other kind is
 * refused.
 */
public interface UndoableTransaction {

    /**
     * The transaction as the undo request and the register name it.
     *
     * @param cycleInstanceId the payroll cycle instance it belongs to (no instance undoes for
     *     another, SRS-15-6)
     * @param stage where it stands now (recorded as the register's stage at undo)
     * @param label what the Checker and the Administrator see in the inbox
     */
    record Target(long cycleInstanceId, String stage, String label) {}

    /**
     * The {@code transaction_entity} of the register (a table name, e.g. {@code mdp_transaction}).
     */
    String entity();

    /** The transaction with this key, if it exists and may still be undone. */
    Optional<Target> find(String key);

    /**
     * Undoes it, in the approval's transaction: by status or a reversing record, never by deleting
     * a business record (R-DEL), and audited by the owning module.
     *
     * @param decidedBy the authorising Administrator
     */
    void undo(String key, String reason, String decidedBy, long approvalId);
}
