package in.gov.fci.payroll.platform.persistence.h2;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * H2 port of {@code trg_labour_cpf_xuniq} (DDL v4.0 Section 4; LC-9): a labour CPF code held by a
 * staff member is refused; a row without a code, a deleted row and a free code pass.
 */
class LabourCpfCrossUniqueTriggerTest {

    private final LabourCpfCrossUniqueTrigger trigger = new LabourCpfCrossUniqueTrigger();
    private final Connection conn = mock(Connection.class);

    private ResultSet labour(String cpf) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("CPF_CODE")).thenReturn(cpf);
        return row;
    }

    private void staffHolding(boolean found, long count) throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(found);
        when(result.getLong(1)).thenReturn(count);
    }

    @Test
    void LC_9_aRowWithoutACodeOrADeletedRowIsNotChecked() throws SQLException {
        trigger.fire(conn, (ResultSet) null, (ResultSet) null);
        trigger.fire(conn, (ResultSet) null, labour(null));
        verifyNoInteractions(conn);
    }

    @Test
    void LC_9_aCodeHeldByAStaffMemberIsRefusedAndAFreeOneAccepted() throws SQLException {
        staffHolding(true, 1L);
        assertThatThrownBy(() -> trigger.fire(conn, (ResultSet) null, labour("NA11/7")))
                .isInstanceOf(SQLException.class)
                .hasMessageStartingWith("FCIPAY-CPF-101");
        staffHolding(true, 0L);
        assertThatCode(() -> trigger.fire(conn, (ResultSet) null, labour("NA11/8")))
                .doesNotThrowAnyException();
        staffHolding(false, 0L);
        assertThatCode(() -> trigger.fire(conn, (ResultSet) null, labour("NA11/9")))
                .doesNotThrowAnyException();
        verify(conn, org.mockito.Mockito.times(3)).prepareStatement(anyString());
    }
}
