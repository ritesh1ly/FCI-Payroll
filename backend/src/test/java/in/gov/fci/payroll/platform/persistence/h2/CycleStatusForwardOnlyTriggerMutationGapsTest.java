package in.gov.fci.payroll.platform.persistence.h2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * BR-CYC-016 / BR-CYC-017 (SRS-15-10), DDL {@code trg_cycle_status_forward_only}: which record
 * admits which regression, and what the lookups are bound to.
 */
class CycleStatusForwardOnlyTriggerMutationGapsTest {

    private static final String OVERRIDE = "cycle_override_log";
    private static final String UNFREEZE = "cycle_unfreeze";
    private static final String RETURN = "cycle_return";

    /** A database where only the named tables hold a matching row; every lookup is remembered. */
    private static final class Db {
        final Connection conn = mock(Connection.class);
        final Map<String, PreparedStatement> statements = new LinkedHashMap<>();
        final List<String> asked = new ArrayList<>();

        Db(Set<String> tablesWithRow) throws SQLException {
            when(conn.prepareStatement(anyString()))
                    .thenAnswer(
                            call -> {
                                String sql = call.getArgument(0);
                                String table =
                                        List.of(OVERRIDE, UNFREEZE, RETURN).stream()
                                                .filter(sql::contains)
                                                .findFirst()
                                                .orElseThrow();
                                asked.add(table);
                                PreparedStatement ps = mock(PreparedStatement.class);
                                ResultSet rs = mock(ResultSet.class);
                                when(rs.next()).thenReturn(tablesWithRow.contains(table));
                                when(ps.executeQuery()).thenReturn(rs);
                                statements.put(table, ps);
                                return ps;
                            });
        }
    }

    private static ResultSet cycle(String status) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("CYCLE_STATUS")).thenReturn(status);
        when(row.getLong("CYCLE_INSTANCE_ID")).thenReturn(5L);
        return row;
    }

    private static void fire(Db db, String before, String after) throws SQLException {
        new CycleStatusForwardOnlyTrigger().fire(db.conn, cycle(before), cycle(after));
    }

    @Test
    void BR_CYC_016_anApprovedOverrideAdmitsTheRegressionAndIsLookedUpByInstanceAndStatuses()
            throws SQLException {
        Db db = new Db(Set.of(OVERRIDE));
        assertThatCode(() -> fire(db, "VERIFIED", "FROZEN")).doesNotThrowAnyException();
        assertThat(db.asked).containsExactly(OVERRIDE);
        PreparedStatement ps = db.statements.get(OVERRIDE);
        verify(ps).setLong(1, 5L);
        verify(ps).setString(2, "VERIFIED");
        verify(ps).setString(3, "FROZEN");
        verify(ps, times(2)).setString(anyInt(), anyString());
    }

    @Test
    void BR_CYC_016_aRecentUnfreezeAdmitsTheRegressionToInitiated() throws SQLException {
        Db db = new Db(Set.of(UNFREEZE));
        assertThatCode(() -> fire(db, "COMPUTED", "INITIATED")).doesNotThrowAnyException();
        assertThat(db.asked).containsExactly(OVERRIDE, UNFREEZE);
        PreparedStatement ps = db.statements.get(UNFREEZE);
        verify(ps).setLong(1, 5L);
        verify(ps).setString(2, "COMPUTED");
        verify(ps, times(1)).setString(anyInt(), anyString());
    }

    @Test
    void BR_CYC_016_anUnfreezeDoesNotAdmitARegressionToAnyOtherStatus() throws SQLException {
        Db db = new Db(Set.of(UNFREEZE));
        assertThatThrownBy(() -> fire(db, "COMPUTED", "FROZEN"))
                .isInstanceOf(SQLException.class)
                .hasMessageStartingWith("FCIPAY-CYC-001");
        assertThat(db.asked).doesNotContain(UNFREEZE);
    }

    @Test
    void BR_CYC_017_anOpenCheckerReturnAdmitsTheRegressionFromComputedOrVerifiedToFrozen()
            throws SQLException {
        for (String before : new String[] {"COMPUTED", "VERIFIED"}) {
            Db db = new Db(Set.of(RETURN));
            assertThatCode(() -> fire(db, before, "FROZEN")).doesNotThrowAnyException();
            assertThat(db.asked).containsExactly(OVERRIDE, RETURN);
            PreparedStatement ps = db.statements.get(RETURN);
            verify(ps).setLong(1, 5L);
            verify(ps).setString(2, before);
        }
    }

    @Test
    void BR_CYC_017_aCheckerReturnDoesNotAdmitARegressionToInitiated() throws SQLException {
        Db db = new Db(Set.of(RETURN));
        assertThatThrownBy(() -> fire(db, "VERIFIED", "INITIATED"))
                .isInstanceOf(SQLException.class)
                .hasMessageStartingWith("FCIPAY-CYC-001");
        assertThat(db.asked).doesNotContain(RETURN);
    }

    @Test
    void BR_CYC_016_withoutAnyRecordTheRegressionIsRefusedAfterTheLookups() throws SQLException {
        Db db = new Db(Set.of());
        assertThatThrownBy(() -> fire(db, "VERIFIED", "FROZEN"))
                .hasMessageStartingWith("FCIPAY-CYC-001");
        assertThat(db.asked).containsExactly(OVERRIDE, RETURN);
        Db toInitiated = new Db(Set.of());
        assertThatThrownBy(() -> fire(toInitiated, "FROZEN", "INITIATED"))
                .hasMessageStartingWith("FCIPAY-CYC-001");
        assertThat(toInitiated.asked).containsExactly(OVERRIDE, UNFREEZE);
    }

    @Test
    void BR_CYC_016_aForwardOrSameStatusUpdateTouchesNoTable() throws SQLException {
        Db db = new Db(Set.of());
        assertThatCode(() -> fire(db, "FROZEN", "COMPUTED")).doesNotThrowAnyException();
        assertThatCode(() -> fire(db, "FROZEN", "FROZEN")).doesNotThrowAnyException();
        assertThat(db.asked).isEmpty();
    }
}
