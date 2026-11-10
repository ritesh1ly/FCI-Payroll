package in.gov.fci.payroll.document.persistence;

import in.gov.fci.payroll.document.business.DocumentStorage;
import in.gov.fci.payroll.document.business.model.DocumentAttachment;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The in-database byte store (storage_provider ORACLE_SECUREFILE; decision D-1, the fallback named
 * in the DDL S25 header): one {@code document_content} row per attachment, a SecureFile BLOB on
 * Oracle. Insert-only: bytes are never updated or deleted, a replaced file keeps its own row.
 */
@Component
public class DatabaseDocumentStorage implements DocumentStorage {

    private final JdbcTemplate jdbc;

    public DatabaseDocumentStorage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String provider() {
        return DocumentAttachment.ORACLE_SECUREFILE;
    }

    @Override
    public String bucket() {
        return null; // no bucket in the database store
    }

    @Override
    public void write(DocumentAttachment attachment, byte[] content) {
        // Same transaction as the attachment row: both are stored or neither is.
        jdbc.update(
                "INSERT INTO document_content (attachment_id, content) VALUES (?, ?)",
                attachment.getId(),
                content);
    }

    @Override
    public byte[] read(DocumentAttachment attachment) {
        List<byte[]> rows =
                jdbc.query(
                        "SELECT content FROM document_content WHERE attachment_id = ?",
                        (rs, i) -> rs.getBytes(1),
                        attachment.getId());
        if (rows.isEmpty()) {
            // Metadata without bytes is a storage fault, never an empty download.
            throw new IllegalStateException(
                    "No stored content for attachment " + attachment.getId());
        }
        return rows.get(0);
    }
}
