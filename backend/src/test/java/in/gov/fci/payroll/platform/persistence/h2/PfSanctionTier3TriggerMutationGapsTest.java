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
 * SRS-6-4, UC-05, DDL {@code trg_pf_sanction_tier3}: a CPF sanction reaches SANCTIONED only with an
 * OTP-confirmed tier-3 SANCTIONED action on the current pass.
 */
class PfSanctionTier3TriggerMutationGapsTest {

    private final PfSanctionTier3Trigger trigger = new PfSanctionTier3Trigger();

    private static ResultSet sanction(String stage) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("WORKFLOW_STAGE")).thenReturn(stage);
        when(row.getLong("SANCTION_ID")).thenReturn(31L);
        return row;
    }

    private static final class Db {
        final Connection conn = mock(Connection.class);
        final PreparedStatement ps = mock(PreparedStatement.class);

        Db(boolean tier3) throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(tier3);
            when(ps.executeQuery()).thenReturn(rs);
            when(conn.prepareStatement(anyString())).thenReturn(ps);
        }
    }

    @Test
    void SRS_6_4_aStageOtherThanSanctionedIsNotChecked() throws SQLException {
        Connection conn = mock(Connection.class);
        ResultSet before = sanction("TIER_2");
        ResultSet after = sanction("TIER_3");
        assertThatCode(() -> trigger.fire(conn, before, after)).doesNotThrowAnyException();
        verifyNoInteractions(conn);
    }

    @Test
    void SRS_6_4_aSanctionAlreadySanctionedIsNotCheckedAgain() throws SQLException {
        Connection conn = mock(Connection.class);
        ResultSet before = sanction("SANCTIONED");
        ResultSet after = sanction("SANCTIONED");
        assertThatCode(() -> trigger.fire(conn, before, after)).doesNotThrowAnyException();
        verifyNoInteractions(conn);
    }

    @Test
    void SRS_6_4_reachingSanctionedWithoutATier3ActionOnTheCurrentPassIsRefused()
            throws SQLException {
        Db db = new Db(false);
        ResultSet before = sanction("TIER_3");
        ResultSet after = sanction("SANCTIONED");
        assertThatThrownBy(() -> trigger.fire(db.conn, before, after))
                .isInstanceOf(SQLException.class)
                .hasMessageStartingWith("FCIPAY-CPF-004")
                .extracting(e -> ((SQLException) e).getErrorCode())
                .isEqualTo(20153);
        // the sanction id is bound to the tier lookup and to the current-pass sub-query
        verify(db.ps).setLong(1, 31L);
        verify(db.ps).setLong(2, 31L);
    }

    @Test
    void SRS_6_4_reachingSanctionedWithAnOtpConfirmedTier3ActionIsAdmitted() throws SQLException {
        Db db = new Db(true);
        ResultSet before = sanction("TIER_3");
        ResultSet after = sanction("SANCTIONED");
        assertThatCode(() -> trigger.fire(db.conn, before, after)).doesNotThrowAnyException();
        verify(db.ps).setLong(1, 31L);
        verify(db.ps).setLong(2, 31L);
    }
}
