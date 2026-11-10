package in.gov.fci.payroll.platform.approval;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The documents of a record as the approval engine needs them: whether one is attached before a
 * submission of an action whose route requires it (SRS-22-16 "mandatory attachment (Y/N)"), and the
 * "View order" of the Approvals Inbox (SRS-G-31: "Show ... through a View order action"; SRS-13-18
 * "Show the order to the Payroll Checker at approval"), for every module alike. Served by the
 * document module's store ({@code DocumentStore}); declared here because the workflow module may
 * not depend on the document module.
 */
public interface AttachmentPresence {

    /** One active document of a record (no content). */
    record AttachedFile(
            long id,
            String fileName,
            String documentTypeName,
            String referenceNumber,
            LocalDate orderDate,
            String issuingAuthority,
            String uploadedBy,
            Instant uploadedAt) {}

    /** The content of one document, as stored. */
    record FileContent(String fileName, String contentType, byte[] bytes) {}

    /** The number of active attachments of the record {@code entityName} / {@code entityKey}. */
    int countActive(String entityName, String entityKey);

    /** The active documents of the record, oldest first. */
    List<AttachedFile> files(String entityName, String entityKey);

    /**
     * The active documents of several records of one entity, by record key (a record without any is
     * left out). The default asks record by record; a store overrides it with one query.
     */
    default java.util.Map<String, List<AttachedFile>> filesOf(
            String entityName, java.util.Collection<String> entityKeys) {
        java.util.Map<String, List<AttachedFile>> found = new java.util.LinkedHashMap<>();
        for (String key : entityKeys) {
            List<AttachedFile> files = files(entityName, key);
            if (!files.isEmpty()) {
                found.put(key, files);
            }
        }
        return found;
    }

    /**
     * One document of the record, its access logged; not found when it belongs to another record.
     */
    FileContent content(
            String actor, String sourceIp, String entityName, String entityKey, long attachmentId);
}
