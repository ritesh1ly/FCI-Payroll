package in.gov.fci.payroll.platform.persistence.h2;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import org.h2.api.Trigger;
import org.junit.jupiter.api.Test;

/**
 * The H2 ports of the target-DDL triggers (local database; Oracle runs the PL/SQL originals): a
 * locked payroll result is immutable (FCIPAY-RES), a supplementary payment needs the locked period
 * and a pay code not excluded (FCIPAY-SUP; BR-CYC-010, SRS-16-13), a cycle only moves forward
 * unless the regression is recorded (FCIPAY-CYC; BR-CYC-008).
 */
class H2TriggerRulesTest {

    /** A row whose columns answer from {@code values} (absent columns are null). */
    private static ResultSet row(Map<String, Object> values) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(anyString()))
                .thenAnswer(
                        i ->
                                values.get(i.<String>getArgument(0)) == null
                                        ? null
                                        : String.valueOf(values.get(i.<String>getArgument(0))));
        when(rs.getObject(anyString())).thenAnswer(i -> values.get(i.<String>getArgument(0)));
        when(rs.getLong(anyString()))
                .thenAnswer(
                        i ->
                                ((Number) values.getOrDefault(i.<String>getArgument(0), 0L))
                                        .longValue());
        return rs;
    }

    /** A connection whose every query returns {@code result} (null = no row). */
    private static Connection lookup(Map<String, Object> result) throws SQLException {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = result == null ? mock(ResultSet.class) : row(result);
        when(rs.next()).thenReturn(result != null, false);
        if (result != null) {
            when(rs.getString(1)).thenReturn(String.valueOf(result.get("1")));
        }
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        return conn;
    }

    private static <T extends org.h2.tools.TriggerAdapter> T on(T trigger, String table)
            throws SQLException {
        trigger.init(null, "PUBLIC", "t", table, false, Trigger.UPDATE);
        return trigger;
    }

    // SRS-20-4: locked pay results stay unchanged for reconciliation.
    @Test
    void BR_CYC_010_aLockedPayrollResultAndItsLinesAreNeverChangedOrDeleted() throws SQLException {
        PayrollResultImmutableTrigger result =
                on(new PayrollResultImmutableTrigger(), "PAYROLL_RESULT");
        ResultSet locked = row(Map.of("IS_LOCKED", "Y"));
        ResultSet open = row(Map.of("IS_LOCKED", "N"));
        assertThatCode(() -> result.fire(null, open, open)).doesNotThrowAnyException();
        assertThatCode(() -> result.fire(null, null, open)).doesNotThrowAnyException();
        assertThatThrownBy(() -> result.fire(null, locked, null))
                .hasMessageContaining("FCIPAY-RES-001");
        assertThatThrownBy(() -> result.fire(null, locked, open))
                .hasMessageContaining("FCIPAY-RES-002");
        PayrollResultImmutableTrigger line =
                on(new PayrollResultImmutableTrigger(), "PAYROLL_RESULT_COMPONENT");
        assertThatThrownBy(() -> line.fire(null, locked, open))
                .hasMessageContaining("FCIPAY-RES-003");
    }

    @Test
    void BR_CYC_010_aSupplementaryNeedsTheLockedCycleOfItsOwnPeriod() throws SQLException {
        SupplementaryPeriodLockedTrigger t =
                on(new SupplementaryPeriodLockedTrigger(), "SUPPLEMENTARY_PAYMENT");
        ResultSet system =
                row(
                        Map.of(
                                "RECORD_ORIGIN",
                                "SYSTEM",
                                "CYCLE_INSTANCE_ID",
                                5L,
                                "FOR_PERIOD_YYMM",
                                "202607"));
        // Migrated history, a delete and a SYSTEM row without a cycle are not this trigger's
        // concern.
        assertThatCode(() -> t.fire(null, null, row(Map.of("RECORD_ORIGIN", "MIGRATED"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> t.fire(null, system, null)).doesNotThrowAnyException();
        assertThatCode(() -> t.fire(null, null, row(Map.of("RECORD_ORIGIN", "SYSTEM"))))
                .doesNotThrowAnyException();
        // An update leaving cycle and period unchanged is not re-checked.
        assertThatCode(() -> t.fire(null, system, system)).doesNotThrowAnyException();
        // A missing instance is left to the foreign key.
        assertThatCode(() -> t.fire(lookup(null), null, system)).doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                t.fire(
                                        lookup(
                                                Map.of(
                                                        "PAYROLL_YYMM",
                                                        "202606",
                                                        "CYCLE_STATUS",
                                                        "LOCKED")),
                                        null,
                                        system))
                .hasMessageContaining("FCIPAY-SUP-003");
        assertThatThrownBy(
                        () ->
                                t.fire(
                                        lookup(
                                                Map.of(
                                                        "PAYROLL_YYMM",
                                                        "202607",
                                                        "CYCLE_STATUS",
                                                        "VERIFIED")),
                                        null,
                                        system))
                .hasMessageContaining("FCIPAY-SUP-002");
        assertThatCode(
                        () ->
                                t.fire(
                                        lookup(
                                                Map.of(
                                                        "PAYROLL_YYMM",
                                                        "202607",
                                                        "CYCLE_STATUS",
                                                        "LOCKED")),
                                        null,
                                        system))
                .doesNotThrowAnyException();
        // A changed period on the same cycle is checked again.
        ResultSet moved =
                row(
                        Map.of(
                                "RECORD_ORIGIN",
                                "SYSTEM",
                                "CYCLE_INSTANCE_ID",
                                5L,
                                "FOR_PERIOD_YYMM",
                                "202606"));
        assertThatThrownBy(
                        () ->
                                t.fire(
                                        lookup(
                                                Map.of(
                                                        "PAYROLL_YYMM",
                                                        "202607",
                                                        "CYCLE_STATUS",
                                                        "LOCKED")),
                                        system,
                                        moved))
                .hasMessageContaining("FCIPAY-SUP-003");
    }

    @Test
    void SRS_16_13_anExcludedPayCodeIsNeverPaidAsASupplementary() throws SQLException {
        SupplementaryComponentExcludedTrigger t =
                on(new SupplementaryComponentExcludedTrigger(), "SUPPLEMENTARY_PAYMENT_LINE");
        ResultSet line = row(Map.of("COMPONENT_ID", 140L));
        assertThatCode(() -> t.fire(null, line, null)).doesNotThrowAnyException();
        assertThatCode(() -> t.fire(null, null, row(Map.of()))).doesNotThrowAnyException();
        assertThatCode(() -> t.fire(null, line, line)).doesNotThrowAnyException();
        assertThatThrownBy(() -> t.fire(lookup(Map.of("1", "Y")), null, line))
                .hasMessageContaining("FCIPAY-SUP-001");
        assertThatCode(() -> t.fire(lookup(Map.of("1", "N")), null, line))
                .doesNotThrowAnyException();
        assertThatCode(() -> t.fire(lookup(null), row(Map.of("COMPONENT_ID", 7L)), line))
                .doesNotThrowAnyException();
    }

    @Test
    void BR_CYC_008_aCycleMovesForwardOnlyAndALockedOneIsNeverReopened() throws SQLException {
        CycleStatusForwardOnlyTrigger t =
                on(new CycleStatusForwardOnlyTrigger(), "PAYROLL_CYCLE_INSTANCE");
        ResultSet locked = row(Map.of("CYCLE_STATUS", "LOCKED", "CYCLE_INSTANCE_ID", 5L));
        ResultSet verified = row(Map.of("CYCLE_STATUS", "VERIFIED", "CYCLE_INSTANCE_ID", 5L));
        ResultSet frozen = row(Map.of("CYCLE_STATUS", "FROZEN", "CYCLE_INSTANCE_ID", 5L));
        assertThatCode(() -> t.fire(null, null, frozen)).doesNotThrowAnyException();
        assertThatCode(() -> t.fire(null, frozen, null)).doesNotThrowAnyException();
        assertThatCode(() -> t.fire(null, frozen, verified)).doesNotThrowAnyException();
        assertThatCode(() -> t.fire(null, locked, locked)).doesNotThrowAnyException();
        assertThatThrownBy(() -> t.fire(null, locked, verified))
                .hasMessageContaining("FCIPAY-CYC-002");
        // A regression without a recorded override, un-freeze or return is refused.
        assertThatThrownBy(() -> t.fire(lookup(null), verified, frozen))
                .hasMessageContaining("FCIPAY-CYC-001");
        // A recorded regression is admitted.
        assertThatCode(() -> t.fire(lookup(Map.of("1", "1")), verified, frozen))
                .doesNotThrowAnyException();
    }
}
