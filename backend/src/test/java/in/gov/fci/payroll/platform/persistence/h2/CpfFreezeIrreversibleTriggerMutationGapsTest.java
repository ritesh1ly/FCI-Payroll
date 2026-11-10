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
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;

/**
 * SRS-6-46, DDL {@code trg_cpf_freeze_irreversible}: a CPF interest freeze is never removed and its
 * year, zone or time change only with an APPROVED authorisation of that freeze.
 */
class CpfFreezeIrreversibleTriggerMutationGapsTest {

    private static final Timestamp FROZEN = Timestamp.valueOf("2026-03-31 18:30:00");

    private final CpfFreezeIrreversibleTrigger trigger = new CpfFreezeIrreversibleTrigger();

    private static ResultSet freeze(String finYear, Long zone, Timestamp frozenAt)
            throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("FIN_YEAR")).thenReturn(finYear);
        when(row.getObject("ZONE_ID")).thenReturn(zone);
        when(row.getTimestamp("FROZEN_AT")).thenReturn(frozenAt);
        when(row.getLong("FREEZE_ID")).thenReturn(9L);
        return row;
    }

    private static final class Db {
        final Connection conn = mock(Connection.class);
        final PreparedStatement ps = mock(PreparedStatement.class);

        Db(boolean approved) throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(approved);
            when(ps.executeQuery()).thenReturn(rs);
            when(conn.prepareStatement(anyString())).thenReturn(ps);
        }
    }

    @Test
    void SRS_6_46_aFreezeCannotBeRemoved() throws SQLException {
        Connection conn = mock(Connection.class);
        ResultSet before = freeze("2025-26", 3L, FROZEN);
        assertThatThrownBy(() -> trigger.fire(conn, before, (ResultSet) null))
                .isInstanceOf(SQLException.class)
                .hasMessageStartingWith("FCIPAY-CPF-001")
                .extracting(e -> ((SQLException) e).getErrorCode())
                .isEqualTo(20150);
        verifyNoInteractions(conn);
    }

    @Test
    void SRS_6_46_anUnchangedKeyNeedsNoAuthorisation() throws SQLException {
        Connection conn = mock(Connection.class);
        ResultSet before = freeze("2025-26", 3L, FROZEN);
        ResultSet after = freeze("2025-26", 3L, FROZEN);
        assertThatCode(() -> trigger.fire(conn, before, after)).doesNotThrowAnyException();
        verifyNoInteractions(conn);
    }

    @Test
    void SRS_6_46_aChangeOfYearZoneOrTimeNeedsAnApprovedAuthorisationOfThatFreeze()
            throws SQLException {
        ResultSet before = freeze("2025-26", 3L, FROZEN);
        ResultSet[] changed = {
            freeze("2024-25", 3L, FROZEN),
            freeze("2025-26", 4L, FROZEN),
            freeze("2025-26", 3L, Timestamp.valueOf("2026-04-01 09:00:00")),
        };
        for (ResultSet after : changed) {
            Db refused = new Db(false);
            assertThatThrownBy(() -> trigger.fire(refused.conn, before, after))
                    .isInstanceOf(SQLException.class)
                    .hasMessageStartingWith("FCIPAY-CPF-002")
                    .extracting(e -> ((SQLException) e).getErrorCode())
                    .isEqualTo(20151);
            verify(refused.ps).setString(1, "9");

            Db approved = new Db(true);
            assertThatCode(() -> trigger.fire(approved.conn, before, after))
                    .doesNotThrowAnyException();
            verify(approved.ps).setString(1, "9");
        }
    }

    @Test
    void SRS_6_46_aKeyThatBecomesOrStopsBeingNullIsAChange() throws SQLException {
        Db db = new Db(false);
        ResultSet noZone = freeze("2025-26", null, FROZEN);
        ResultSet zone = freeze("2025-26", 3L, FROZEN);
        assertThatThrownBy(() -> trigger.fire(db.conn, noZone, zone))
                .hasMessageStartingWith("FCIPAY-CPF-002");
        assertThatThrownBy(() -> trigger.fire(db.conn, zone, noZone))
                .hasMessageStartingWith("FCIPAY-CPF-002");
        ResultSet alsoNoZone = freeze("2025-26", null, FROZEN);
        assertThatCode(() -> trigger.fire(db.conn, noZone, alsoNoZone)).doesNotThrowAnyException();
    }
}
