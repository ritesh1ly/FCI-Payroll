package in.gov.fci.payroll.document.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.document.business.model.DocumentAttachment;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * DDL document_content (decision D-1, ORACLE_SECUREFILE): the stored bytes are returned; metadata
 * without bytes is a storage fault, never an empty download.
 */
class DatabaseDocumentStorageBranchGapsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DatabaseDocumentStorage storage = new DatabaseDocumentStorage(jdbc);
    private final DocumentAttachment attachment = mock(DocumentAttachment.class);

    @Test
    void SRS_G_31_storedBytesAreReadBack() {
        when(attachment.getId()).thenReturn(41L);
        byte[] bytes = {1, 2, 3};
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<byte[]>>any(), eq(41L)))
                .thenReturn(List.of(bytes));
        assertThat(storage.read(attachment)).isEqualTo(bytes);
        assertThat(storage.provider()).isEqualTo(DocumentAttachment.ORACLE_SECUREFILE);
        assertThat(storage.bucket()).isNull();
    }

    @Test
    void SRS_G_31_metadataWithoutBytesIsAStorageFault() {
        when(attachment.getId()).thenReturn(42L);
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<byte[]>>any(), eq(42L)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> storage.read(attachment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No stored content for attachment 42");
    }
}
