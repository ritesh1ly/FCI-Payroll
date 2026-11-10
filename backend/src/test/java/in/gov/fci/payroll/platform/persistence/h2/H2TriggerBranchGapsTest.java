package in.gov.fci.payroll.platform.persistence.h2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import org.h2.api.Trigger;
import org.h2.tools.TriggerAdapter;
import org.junit.jupiter.api.Test;

/**
 * Edge cases of the H2 ports of the target-DDL v4.0 triggers (the Oracle originals run on Oracle):
 * a delete (no new row) and a row the rule does not watch are never checked; a check query that
 * answers no row or a zero count admits the change; a positive count refuses it with the Oracle
 * code. Rules: BR-RULE-002 (one approved rule version per date), LC-9 (a CPF code unique across
 * staff and labour), BR-LABM-001 (one Sardar and one Mandal per gang), the CPF number bands of the
 * zones, the work-slip roles (DDL S14), BR-CYC-010 (a supplementary on its locked cycle) and
 * BR-CYC-008 (a cycle regression only as recorded).
 */
class H2TriggerBranchGapsTest {

    /** A row whose columns answer from {@code values} (absent columns are null, numbers 0). */
    private static ResultSet row(Map<String, Object> values) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(anyString()))
                .thenAnswer(
                        i -> {
                            Object v = values.get(i.<String>getArgument(0));
                            return v == null ? null : String.valueOf(v);
                        });
        when(rs.getObject(anyString())).thenAnswer(i -> values.get(i.<String>getArgument(0)));
        when(rs.getLong(anyString()))
                .thenAnswer(
                        i ->
                                ((Number) values.getOrDefault(i.<String>getArgument(0), 0L))
                                        .longValue());
        return rs;
    }

    /** A connection whose check query answers one row with {@code count}, or no row (null). */
    private static Connection counting(Long count) throws SQLException {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(count != null, false);
        if (count != null) {
            when(rs.getLong(1)).thenReturn(count);
        }
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        return conn;
    }

    private static <T extends TriggerAdapter> T on(T trigger, String table) throws SQLException {
        trigger.init(null, "PUBLIC", "t", table, false, Trigger.INSERT);
        return trigger;
    }

    /** No row, a zero count and a positive count, in that order, for an already watched row. */
    private static void admitsUnlessCounted(TriggerAdapter t, ResultSet watched, String code)
            throws SQLException {
        assertThatCode(() -> t.fire(counting(null), null, watched)).doesNotThrowAnyException();
        assertThatCode(() -> t.fire(counting(0L), null, watched)).doesNotThrowAnyException();
        assertThatThrownBy(() -> t.fire(counting(1L), null, watched))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(code);
    }

    @Test
    void BR_RULE_002_onlyAnApprovedVersionIsCheckedForAnOverlappingActiveVersion()
            throws SQLException {
        RuleVersionTemporalTrigger t = on(new RuleVersionTemporalTrigger(), "RULE_VERSION");
        Connection untouched = mock(Connection.class);
        assertThatCode(() -> t.fire(untouched, row(Map.of()), null)).doesNotThrowAnyException();
        assertThatCode(() -> t.fire(untouched, null, row(Map.of("APPROVAL_STATE", "PENDING"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> t.fire(untouched, null, row(Map.of()))).doesNotThrowAnyException();
        verify(untouched, never()).prepareStatement(anyString());
        admitsUnlessCounted(
                t, row(Map.of("APPROVAL_STATE", "APPROVED", "RULE_ID", 4L)), "FCIPAY-RUL-001");
    }

    @Test
    void LC_9_aStaffCpfCodeHeldByALabourMemberIsRefusedAndNoCodeIsNotChecked() throws SQLException {
        EmployeeCpfCrossUniqueTrigger t = on(new EmployeeCpfCrossUniqueTrigger(), "EMPLOYEE");
        Connection untouched = mock(Connection.class);
        assertThatCode(() -> t.fire(untouched, row(Map.of("CPF_CODE", "1001")), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> t.fire(untouched, null, row(Map.of()))).doesNotThrowAnyException();
        verify(untouched, never()).prepareStatement(anyString());
        admitsUnlessCounted(t, row(Map.of("CPF_CODE", "1001")), "FCIPAY-CPF-102");
    }

    @Test
    void BR_LABM_001_onlyASardarOrAMandalIsCheckedForASecondActiveHolder() throws SQLException {
        GangRoleSingletonTrigger t = on(new GangRoleSingletonTrigger(), "LABOUR_GANG_MEMBERSHIP");
        Connection untouched = mock(Connection.class);
        assertThatCode(() -> t.fire(untouched, row(Map.of("GANG_ROLE", "SARDAR")), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> t.fire(untouched, null, row(Map.of("GANG_ROLE", "MEMBER"))))
                .doesNotThrowAnyException();
        verify(untouched, never()).prepareStatement(anyString());
        admitsUnlessCounted(t, row(Map.of("GANG_ROLE", "SARDAR", "GANG_ID", 8L)), "FCIPAY-LBR-001");
        admitsUnlessCounted(t, row(Map.of("GANG_ROLE", "MANDAL", "GANG_ID", 8L)), "FCIPAY-LBR-001");
    }

    @Test
    void BR_LABM_001_aWorkSlipHoldsAtMostOneSardarAndOneMandal() throws SQLException {
        WorkSlipRoleSingletonTrigger t =
                on(new WorkSlipRoleSingletonTrigger(), "WORK_SLIP_PARTICIPANT");
        Connection untouched = mock(Connection.class);
        assertThatCode(() -> t.fire(untouched, row(Map.of("GANG_ROLE", "MANDAL")), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> t.fire(untouched, null, row(Map.of("GANG_ROLE", "MEMBER"))))
                .doesNotThrowAnyException();
        verify(untouched, never()).prepareStatement(anyString());
        admitsUnlessCounted(
                t, row(Map.of("GANG_ROLE", "SARDAR", "WORK_SLIP_ID", 3L)), "FCIPAY-WSL-002");
        admitsUnlessCounted(
                t, row(Map.of("GANG_ROLE", "MANDAL", "WORK_SLIP_ID", 3L)), "FCIPAY-WSL-002");
    }

    @Test
    void LC_9_aZoneWithoutACpfBandIsNotCheckedAndOverlappingBandsAreRefused() throws SQLException {
        ZoneBandCheckTrigger t = on(new ZoneBandCheckTrigger(), "ORG_ZONE");
        Connection untouched = mock(Connection.class);
        assertThatCode(() -> t.fire(untouched, row(Map.of("CPF_NUMBER_RANGE_START", 1L)), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> t.fire(untouched, null, row(Map.of()))).doesNotThrowAnyException();
        verify(untouched, never()).prepareStatement(anyString());
        admitsUnlessCounted(
                t,
                row(Map.of("CPF_NUMBER_RANGE_START", 1L, "CPF_NUMBER_RANGE_END", 999L)),
                "FCIPAY-ORG-001");
    }

    @Test
    void BR_CYC_010_aSupplementaryMovedToAnotherCycleIsCheckedAgain() throws SQLException {
        SupplementaryPeriodLockedTrigger t =
                on(new SupplementaryPeriodLockedTrigger(), "SUPPLEMENTARY_PAYMENT");
        Map<String, Object> before = new HashMap<>();
        before.put("RECORD_ORIGIN", "SYSTEM");
        before.put("CYCLE_INSTANCE_ID", 5L);
        before.put("FOR_PERIOD_YYMM", "202607");
        Map<String, Object> after = new HashMap<>(before);
        after.put("CYCLE_INSTANCE_ID", 6L);
        // Same period, another cycle: the new cycle is looked up and its other period refused.
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet cycle = row(Map.of("PAYROLL_YYMM", "202608", "CYCLE_STATUS", "LOCKED"));
        when(cycle.next()).thenReturn(true);
        when(ps.executeQuery()).thenReturn(cycle);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        assertThatThrownBy(() -> t.fire(conn, row(before), row(after)))
                .hasMessageContaining("FCIPAY-SUP-003");
        verify(ps).setObject(1, 6L);
    }

    @Test
    void BR_CYC_008_aReturnToFrozenIsRecordedOnlyFromComputedOrVerified() throws SQLException {
        // A return row exists, yet a regression to FROZEN from any other status is not admitted
        // by it (only an override log or an un-freeze could record that).
        Connection returned = counting(1L);
        assertThat(CycleStatusForwardOnlyTrigger.recorded(counting(null), 5L, "LOCKED", "FROZEN"))
                .isFalse();
        assertThat(
                        CycleStatusForwardOnlyTrigger.recorded(
                                counting(null), 5L, "INITIATED", "FROZEN"))
                .isFalse();
        assertThat(CycleStatusForwardOnlyTrigger.recorded(returned, 5L, "COMPUTED", "FROZEN"))
                .isTrue();
    }

    @Test
    void ADR_0019_anOracleDateStoredAsTimestampValidatesAsTheSameType() {
        OracleModeH2Dialect dialect = new OracleModeH2Dialect();
        assertThat(dialect.equivalentTypes(Types.DATE, Types.TIMESTAMP)).isTrue();
        assertThat(dialect.equivalentTypes(Types.TIMESTAMP, Types.DATE)).isTrue();
        assertThat(dialect.equivalentTypes(Types.DATE, Types.DATE)).isTrue();
        // A date against a number is not a date pair: plain H2 decides (not equivalent).
        assertThat(dialect.equivalentTypes(Types.DATE, Types.INTEGER)).isFalse();
        assertThat(dialect.equivalentTypes(Types.INTEGER, Types.DATE)).isFalse();
        // Neither a date: plain H2 decides (the same type is equivalent, two others are not).
        assertThat(dialect.equivalentTypes(Types.VARCHAR, Types.VARCHAR)).isTrue();
        assertThat(dialect.equivalentTypes(Types.VARCHAR, Types.INTEGER)).isFalse();
    }
}
