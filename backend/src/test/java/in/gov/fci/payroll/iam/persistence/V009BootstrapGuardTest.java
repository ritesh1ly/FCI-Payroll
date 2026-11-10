package in.gov.fci.payroll.iam.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.config.ApiProperties;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The first-deployment guard of the break-glass administrator (RBAC_DESIGN.md §9): a database
 * without an approved System Administrator refuses to start unless the deployment supplies an
 * initial password of the policy's length, and a bootstrap query that answers nothing stops the
 * migration instead of creating a half-made account.
 */
class V009BootstrapGuardTest {

    private static V009__Bootstrap_system_administrator migration(String password) {
        ApiProperties properties = mock(ApiProperties.class);
        when(properties.bootstrap())
                .thenReturn(new ApiProperties.Bootstrap("sysadmin@fci.gov.in", password));
        return new V009__Bootstrap_system_administrator(mock(PasswordEncoder.class), properties);
    }

    /** A connection whose every query answers one row of zero, or no row at all. */
    private static Context database(boolean answers) throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(answers);
        when(rows.getLong(1)).thenReturn(0L);
        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);
        return context;
    }

    @Test
    void theFirstDeploymentRefusesAMissingBlankOrShortInitialPassword() throws SQLException {
        Context context = database(true);
        for (String password : new String[] {null, "   ", "Short1!"}) {
            assertThatThrownBy(() -> migration(password).migrate(context))
                    .as(String.valueOf(password))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("fci.api.bootstrap.admin-initial-password")
                    .hasMessageNotContaining("Short1!");
        }
    }

    @Test
    void aBootstrapQueryWithoutARowStopsTheMigration() throws SQLException {
        Context context = database(false);
        assertThatThrownBy(() -> migration("Bootstrap-Passw0rd!").migrate(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap query returned no row");
    }
}
