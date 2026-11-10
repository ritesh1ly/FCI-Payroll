package in.gov.fci.payroll.platform.persistence.h2;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.h2.tools.TriggerAdapter;
import org.junit.jupiter.api.Test;

/**
 * The check queries of the H2 triggers are bound to the key of the changed row: BR-RULE-002, LC-9,
 * BR-LABM-001, SRS-2-47, SRS-16-13.
 */
class H2TriggerBindingsMutationGapsTest {

    private static final class Db {
        final Connection conn = mock(Connection.class);
        final PreparedStatement ps = mock(PreparedStatement.class);

        Db() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(false);
            when(ps.executeQuery()).thenReturn(rs);
            when(conn.prepareStatement(anyString())).thenReturn(ps);
        }
    }

    /** A new row that every watched trigger inspects (each reads only its own columns). */
    private static ResultSet newRow() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("GANG_ROLE")).thenReturn("SARDAR");
        when(row.getLong("GANG_ID")).thenReturn(8L);
        when(row.getLong("WORK_SLIP_ID")).thenReturn(15L);
        when(row.getLong("RULE_ID")).thenReturn(4L);
        when(row.getLong("EMPLOYEE_ID")).thenReturn(7L);
        when(row.getString("APPROVAL_STATE")).thenReturn("APPROVED");
        when(row.getString("ATTACHMENT_STATUS")).thenReturn("APPROVED");
        when(row.getString("CPF_CODE")).thenReturn("104882");
        when(row.getObject("COMPONENT_ID")).thenReturn(77L);
        return row;
    }

    private static Db fired(TriggerAdapter trigger) throws SQLException {
        Db db = new Db();
        trigger.fire(db.conn, null, newRow());
        return db;
    }

    @Test
    void BR_LABM_001_theGangRoleCheckIsBoundToTheGang() throws SQLException {
        verify(fired(new GangRoleSingletonTrigger()).ps).setLong(1, 8L);
    }

    @Test
    void BR_LABM_001_theWorkSlipRoleCheckIsBoundToTheWorkSlip() throws SQLException {
        verify(fired(new WorkSlipRoleSingletonTrigger()).ps).setLong(1, 15L);
    }

    @Test
    void BR_RULE_002_theOverlapCheckIsBoundToTheRule() throws SQLException {
        verify(fired(new RuleVersionTemporalTrigger()).ps).setLong(1, 4L);
    }

    @Test
    void SRS_2_47_theAttachmentOverlapCheckIsBoundToTheLabour() throws SQLException {
        verify(fired(new TempAttachmentOverlapTrigger()).ps).setLong(1, 7L);
    }

    @Test
    void LC_9_theCrossUniquenessChecksAreBoundToTheCpfCode() throws SQLException {
        verify(fired(new LabourCpfCrossUniqueTrigger()).ps).setString(1, "104882");
        verify(fired(new EmployeeCpfCrossUniqueTrigger()).ps).setString(1, "104882");
    }

    @Test
    void SRS_16_13_theExclusionLookupIsBoundToThePayComponent() throws SQLException {
        verify(fired(new SupplementaryComponentExcludedTrigger()).ps).setObject(1, 77L);
    }
}
