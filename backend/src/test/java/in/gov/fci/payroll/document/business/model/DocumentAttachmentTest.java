package in.gov.fci.payroll.document.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * An attachment's lifecycle (SRS-G-31, BR-AUDIT-009): active, superseded or cancelled, never
 * deleted; a provisional owner key is adopted once by its record ({@code chk_supp_locked_cycle}).
 */
class DocumentAttachmentTest {

    private static final String PROVISIONAL = "PROVISIONAL:";

    private static DocumentAttachment attachment(String key) {
        return DocumentAttachment.record(
                "supplementary_payment",
                key,
                1L,
                "RO/14",
                LocalDate.of(2026, 8, 12),
                "AGM",
                "order.pdf",
                "application/pdf",
                10L,
                "ab",
                false,
                "a".repeat(60), // longer than uploaded_by: kept to the column
                Instant.EPOCH);
    }

    @Test
    void aProvisionalFileIsAdoptedOnceByItsRecord() {
        DocumentAttachment a = attachment(PROVISIONAL + "x");
        a.adoptedBy("500", PROVISIONAL);
        assertThat(a.belongsTo("supplementary_payment", "500")).isTrue();
        assertThat(a.getUploadedBy()).hasSize(50);
        // Adopted: the real key is never re-pointed again.
        assertThatThrownBy(() -> a.adoptedBy("501", PROVISIONAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRealKeyIsNeverMovedAndNoFileIsAdoptedByAnotherProvisionalKey() {
        assertThatThrownBy(() -> attachment("500").adoptedBy("501", PROVISIONAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                attachment(PROVISIONAL + "x")
                                        .adoptedBy(PROVISIONAL + "y", PROVISIONAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void srsG31_aSupersededOrCancelledFileChangesNoMore() {
        DocumentAttachment a = attachment("500");
        a.supersede(9L);
        assertThat(a.isActive()).isFalse();
        assertThat(a.getSupersededBy()).isEqualTo(9L);
        assertThatThrownBy(a::cancel).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> a.supersede(10L)).isInstanceOf(IllegalStateException.class);

        DocumentAttachment b = attachment(PROVISIONAL + "y");
        b.cancel();
        assertThat(b.getStatus()).isEqualTo(DocumentAttachment.CANCELLED);
        assertThatThrownBy(() -> b.adoptedBy("500", PROVISIONAL))
                .isInstanceOf(IllegalStateException.class);
    }
}
