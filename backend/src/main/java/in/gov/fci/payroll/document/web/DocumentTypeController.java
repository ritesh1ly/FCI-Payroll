package in.gov.fci.payroll.document.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.document.api.DocumentStore;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The document types of the order control (SRS-G-30, {@code document_type_master}) for the upload
 * forms of every screen. Codes and names only, nothing personal: any signed-in session may read
 * them; each upload is authorised by its own screen.
 */
@RestController
@Profile("online")
@PreAuthorize("isAuthenticated()")
public class DocumentTypeController {

    private final DocumentStore documents;

    public DocumentTypeController(DocumentStore documents) {
        this.documents = documents;
    }

    @GetMapping("/v1/document-types")
    public ResponseEntity<List<DocumentStore.TypeView>> types() {
        return noStore(documents.types());
    }
}
