package in.gov.fci.payroll.platform.persistence.h2;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * H2 port of {@code trg_labour_temp_attach_overlap} (DDL v4.0 Section 4; SRS-2-47): only a pending
 * or approved attachment is checked; an overlapping one is refused, none overlapping passes.
 */
class TempAttachmentOverlapTriggerTest {

    private final TempAttachmentOverlapTrigger trigger = new TempAttachmentOverlapTrigger();
    private final Connection conn = mock(Connection.class);

    private static ResultSet attachment(String status) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("ATTACHMENT_STATUS")).thenReturn(status);
        when(row.getLong("EMPLOYEE_ID")).thenReturn(7L);
        return row;
    }

    private void overlapping(boolean found, long count) throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(found);
        when(result.getLong(1)).thenReturn(count);
    }

    @Test
    void SRS_2_47_aDeletedRejectedOrEndedAttachmentIsNotChecked() throws SQLException {
        trigger.fire(conn, (ResultSet) null, (ResultSet) null);
        trigger.fire(conn, (ResultSet) null, attachment("REJECTED"));
        verifyNoInteractions(conn);
    }

    @Test
    void SRS_2_47_anOverlappingAttachmentIsRefusedAndOtherwiseAccepted() throws SQLException {
        overlapping(true, 1L);
        assertThatThrownBy(() -> trigger.fire(conn, (ResultSet) null, attachment("APPROVED")))
                .isInstanceOf(SQLException.class)
                .hasMessageStartingWith("FCIPAY-LBR-002");
        overlapping(true, 0L);
        assertThatCode(() -> trigger.fire(conn, (ResultSet) null, attachment("PENDING_APPROVAL")))
                .doesNotThrowAnyException();
        overlapping(false, 0L);
        assertThatCode(() -> trigger.fire(conn, (ResultSet) null, attachment("APPROVED")))
                .doesNotThrowAnyException();
    }
}
