package in.gov.fci.payroll.document.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** An attachment's owner, PII flag and uploader column (SRS-G-30), a type's reference rule. */
class AttachmentEdgesTest {

    private static DocumentAttachment by(String uploader, boolean pii) {
        return DocumentAttachment.record(
                "loan_header",
                "7",
                1L,
                "REF/1",
                LocalDate.of(2026, 10, 1),
                "HQ",
                "order.pdf",
                "application/pdf",
                10L,
                "a".repeat(64),
                pii,
                uploader,
                Instant.parse("2026-10-23T05:00:00Z"));
    }

    @Test
    void SRS_G_30_anAttachmentBelongsOnlyToItsOwnRecord() {
        DocumentAttachment a = by("maker", false);
        assertThat(a.belongsTo("loan_header", "7")).isTrue();
        assertThat(a.belongsTo("loan_header", "8")).isFalse();
        assertThat(a.belongsTo("cpf_claim", "7")).isFalse();
    }

    @Test
    void SRS_G_30_thePiiFlagIsKept() {
        assertThat(by("maker", true).containsPii()).isTrue();
        assertThat(by("maker", false).containsPii()).isFalse();
    }

    @Test
    void SRS_G_30_anUploaderOfFiftyCharactersIsKeptWhole() {
        String fifty = "u".repeat(50);
        assertThat(by(fifty, false).getUploadedBy()).isEqualTo(fifty);
        assertThat(by(fifty + "x", false).getUploadedBy()).isEqualTo(fifty);
    }

    @Test
    void SRS_G_30_aTypeMayRequireAReferenceNumber() {
        DocumentType t = BeanUtils.instantiateClass(DocumentType.class);
        assertThat(t.requiresReferenceNumber()).isFalse();
        ReflectionTestUtils.setField(t, "requiresReferenceNumber", true);
        assertThat(t.requiresReferenceNumber()).isTrue();
    }
}
