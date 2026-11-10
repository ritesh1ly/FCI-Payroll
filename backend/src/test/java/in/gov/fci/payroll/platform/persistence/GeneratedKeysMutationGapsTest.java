package in.gov.fci.payroll.platform.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** A statement that cannot be bound is closed before the failure reaches the caller. */
class GeneratedKeysMutationGapsTest {

    @Test
    void aStatementWhoseValuesCannotBeBoundIsClosedAndTheFailureRethrown() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement ps =
                mock(
                        PreparedStatement.class,
                        invocation -> {
                            if (invocation.getMethod().getName().startsWith("set")) {
                                throw new SQLException("cannot bind");
                            }
                            return null;
                        });
        when(connection.prepareStatement(anyString(), any(String[].class))).thenReturn(ps);
        GeneratedKeys.Insert insert =
                new GeneratedKeys.Insert("INSERT INTO t (a) VALUES (?)", "ID", new Object[] {"x"});

        assertThatThrownBy(() -> insert.createPreparedStatement(connection))
                .isInstanceOf(SQLException.class)
                .hasMessage("cannot bind");

        verify(ps).close();
    }
}
