package in.gov.fci.payroll.document.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SRS-G-31 / DDL document_attachment.file_size_bytes: the column may be NULL on a row read back (no
 * size recorded); the size is then reported as 0, never a NullPointerException.
 */
class DocumentAttachmentBranchGapsTest {

    @Test
    void SRS_G_31_aRowWithoutARecordedSizeReportsZeroBytes() {
        // The JPA constructor leaves every column unset, as a row with NULL file_size_bytes.
        assertThat(new DocumentAttachment().getFileSizeBytes()).isZero();
    }
}
