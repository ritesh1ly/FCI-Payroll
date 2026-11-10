package in.gov.fci.payroll.document.persistence;

import in.gov.fci.payroll.document.business.model.DocumentAttachment;
import in.gov.fci.payroll.document.business.model.DocumentType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Target DDL v4.0 S25 repositories. Plain {@code Repository} interfaces: no delete method exists,
 * because an attachment is never deleted (BR-AUDIT-009).
 */
public final class DocumentRepositories {

    private DocumentRepositories() {}

    public interface Attachments extends Repository<DocumentAttachment, Long> {
        Optional<DocumentAttachment> findById(Long id);

        List<DocumentAttachment> findByEntityNameAndEntityKeyAndStatusOrderByIdAsc(
                String entityName, String entityKey, String status);

        List<DocumentAttachment> findByEntityNameAndEntityKeyInAndStatusOrderByIdAsc(
                String entityName, java.util.Collection<String> entityKeys, String status);

        int countByEntityNameAndEntityKeyAndStatus(
                String entityName, String entityKey, String status);

        DocumentAttachment save(DocumentAttachment attachment);

        DocumentAttachment saveAndFlush(DocumentAttachment attachment);
    }

    public interface Types extends Repository<DocumentType, Long> {
        Optional<DocumentType> findByCode(String code);

        Optional<DocumentType> findById(Long id);

        List<DocumentType> findAllByOrderByCode();
    }
}
