package in.gov.fci.payroll.document.business;

import in.gov.fci.payroll.document.business.model.DocumentAttachment;

/**
 * Where the bytes of an attachment live (port). The DDL's default is external object storage (S3,
 * Azure Blob, OCI Object Storage) with the in-database SecureFile store as the documented fallback;
 * decision D-1 (2026-10-20) starts with the in-database store. Because every consumer goes through
 * {@code document_attachment} and this port, moving to object storage later is a new adapter only.
 */
public interface DocumentStorage {

    /** {@code document_attachment.storage_provider} written for files this adapter stores. */
    String provider();

    /** {@code storage_bucket} (null where the provider has none). */
    String bucket();

    /** Writes the bytes of a saved attachment row (it already has its id and object key). */
    void write(DocumentAttachment attachment, byte[] content);

    /** Reads the bytes back; fails if they are missing (never returns an empty file silently). */
    byte[] read(DocumentAttachment attachment);
}
