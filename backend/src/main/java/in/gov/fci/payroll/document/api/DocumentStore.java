package in.gov.fci.payroll.document.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Attachments of a record, for the module that owns the record (SRS-G-30, SRS-G-31, SRS-DM-13).
 *
 * <p><b>Authorisation contract.</b> The caller must already have authorised the acting user on the
 * owning record (screen gate and data scope). Every read names the owning record ({@code
 * entityName}, {@code entityKey}); an attachment of another record is reported as not found, so an
 * attachment id guessed from elsewhere reveals nothing.
 */
public interface DocumentStore extends in.gov.fci.payroll.platform.approval.AttachmentPresence {

    /**
     * A file to attach.
     *
     * @param entityName owning table, e.g. {@code off_cycle_payment}
     * @param entityKey owning row key
     * @param documentTypeCode {@code document_type_master.document_type_code} (SANCTION_ORDER,
     *     CIRCULAR, CERTIFICATE, OTHER), for an order (PDF / JPG / PNG); {@code null} for a
     *     system-captured source file such as an X-07 HRMS export, which is not an order and must
     *     be plain-text CSV
     * @param referenceNumber order or reference number (mandatory for types that require it)
     * @param orderDate date of the order (mandatory for an order)
     * @param issuingAuthority issuing office or authority (mandatory for an order)
     * @param fileName name as uploaded (sanitised before it is stored)
     * @param content the bytes; type is detected from the content, never trusted from the client
     * @param containsPii whether the file holds personal data (masking and retention, SRS-SEC-4)
     */
    record NewAttachment(
            String entityName,
            String entityKey,
            String documentTypeCode,
            String referenceNumber,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] content,
            boolean containsPii) {}

    /** What a screen shows about an attachment (never the bytes). */
    record AttachmentView(
            Long id,
            String entityName,
            String entityKey,
            String documentTypeCode,
            String documentTypeName,
            String referenceNumber,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            String contentType,
            long sizeBytes,
            String checksumSha256,
            String status,
            String uploadedBy,
            Instant uploadedAt) {}

    /**
     * A document type of the master ({@code document_type_master}, SRS-G-30) as an upload form
     * needs it.
     *
     * @param referenceRequired the order's reference number is mandatory (OC-3)
     */
    record TypeView(String code, String name, boolean referenceRequired) {}

    /** The bytes of an attachment, with what a download response needs. */
    record Content(AttachmentView attachment, byte[] bytes) {}

    /**
     * Validates, virus-scans and stores a file for the record; the actor is the uploading user.
     * Each attachment is audited. Fails with a catalogued error (wrong type, too large, too many
     * files, missing order particulars, infected, scanner unavailable).
     */
    AttachmentView attach(String actor, NewAttachment attachment);

    /**
     * OD-149: stores a file the system produced for its requester (the ZIP of a bulk download job),
     * owned by {@code entityName} / {@code entityKey} (the job request). Only a PDF or a ZIP
     * produced by this server; never an upload, so it is not virus-scanned and has no order
     * particulars; marked as personal data. Audited.
     */
    AttachmentView storeGenerated(
            String actor, String entityName, String entityKey, String fileName, byte[] content);

    /** The document types an order may be uploaded as, by code. */
    List<TypeView> types();

    /** Active attachments of a record, oldest first. */
    List<AttachmentView> active(String entityName, String entityKey);

    /**
     * Active attachments of many records of one entity at once, by record key (a key without any is
     * absent), each list oldest first: for a register page instead of one {@link #active} per row
     * (deep profile 2026-10-27).
     */
    java.util.Map<String, List<AttachmentView>> activeByKey(
            String entityName, java.util.Collection<String> entityKeys);

    /** The number of active attachments of a record (the order gate of SRS-G-16 counts them). */
    @Override
    int countActive(String entityName, String entityKey);

    /**
     * The bytes of one attachment of the record; logs the access ({@code document_access_log},
     * OC-7). Not found when the attachment belongs to another record.
     */
    Content read(
            String actor, String sourceIp, String entityName, String entityKey, Long attachmentId);

    @Override
    default List<AttachedFile> files(String entityName, String entityKey) {
        return active(entityName, entityKey).stream()
                .map(
                        a ->
                                new AttachedFile(
                                        a.id(),
                                        a.fileName(),
                                        a.documentTypeName(),
                                        a.referenceNumber(),
                                        a.orderDate(),
                                        a.issuingAuthority(),
                                        a.uploadedBy(),
                                        a.uploadedAt()))
                .toList();
    }

    @Override
    default java.util.Map<String, List<AttachedFile>> filesOf(
            String entityName, java.util.Collection<String> entityKeys) {
        java.util.Map<String, List<AttachedFile>> found = new java.util.LinkedHashMap<>();
        activeByKey(entityName, entityKeys)
                .forEach(
                        (key, attachments) ->
                                found.put(
                                        key,
                                        attachments.stream()
                                                .map(
                                                        a ->
                                                                new AttachedFile(
                                                                        a.id(),
                                                                        a.fileName(),
                                                                        a.documentTypeName(),
                                                                        a.referenceNumber(),
                                                                        a.orderDate(),
                                                                        a.issuingAuthority(),
                                                                        a.uploadedBy(),
                                                                        a.uploadedAt()))
                                                .toList()));
        return found;
    }

    @Override
    default FileContent content(
            String actor, String sourceIp, String entityName, String entityKey, long attachmentId) {
        Content c = read(actor, sourceIp, entityName, entityKey, attachmentId);
        return new FileContent(c.attachment().fileName(), c.attachment().contentType(), c.bytes());
    }

    /**
     * Replaces a file before its transaction is submitted (SRS-G-31: after submission files may
     * only be added). The old row is kept as SUPERSEDED, never deleted.
     */
    AttachmentView replace(
            String actor,
            String entityName,
            String entityKey,
            Long attachmentId,
            NewAttachment replacement);

    /**
     * Cancels one active attachment of the record, never deleting it (SRS-G-31: after submission a
     * wrong file is cancelled, and files are only added). Audited with the reason. Not found when
     * the attachment belongs to another record or is no longer active.
     */
    void cancel(
            String actor, String entityName, String entityKey, Long attachmentId, String reason);

    /** Cancels every active attachment of a record together with its transaction (BR-AUDIT-009). */
    void cancelAll(String actor, String entityName, String entityKey, String reason);

    /**
     * Prefix of a provisional owner key: a file the record needs before its row exists (DDL rules
     * like {@code chk_supp_locked_cycle} require the order at insert). The caller stores the file
     * under {@code PROVISIONAL + <unique token>}, inserts the row, then {@link #adopt adopts} it.
     */
    String PROVISIONAL = "PROVISIONAL:";

    /**
     * Moves an attachment stored under a provisional key to its record's key, in the caller's
     * transaction (audited). Only a provisional key is ever re-pointed, so a file cannot move from
     * one real record to another.
     *
     * @throws IllegalArgumentException when the attachment is not under {@code provisionalKey} or
     *     the key is not provisional
     */
    void adopt(
            String actor,
            String entityName,
            String provisionalKey,
            Long attachmentId,
            String entityKey);
}
