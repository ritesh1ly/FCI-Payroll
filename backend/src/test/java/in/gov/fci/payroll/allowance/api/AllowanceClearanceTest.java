package in.gov.fci.payroll.allowance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * SRS-10-27, SRS-10-13 "PLI advance": the allowances clearance of a settlement reads the latest
 * transaction of each policy with an advance still outstanding; a member with none owes nothing.
 */
class AllowanceClearanceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AllowanceClearance clearance = new AllowanceClearance(jdbc);

    @Test
    @SuppressWarnings("unchecked")
    void SRS_10_27_eachOutstandingAdvanceBecomesAPolicyRowWithItsAmount() throws SQLException {
        ArgumentCaptor<RowMapper<AllowanceClearance.Outstanding>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                        eq(870001L)))
                .thenReturn(List.of());

        assertThat(clearance.outstanding(870001L)).isEmpty();

        verify(jdbc).query(anyString(), mapper.capture(), eq(870001L));
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn(55L);
        when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("12500.50"));
        AllowanceClearance.Outstanding row = mapper.getValue().mapRow(rs, 0);
        assertThat(row.policyId()).isEqualTo(55L);
        assertThat(row.reference()).isEqualTo("PLI policy 55");
        assertThat(row.amount()).isEqualByComparingTo("12500.50");
    }

    @Test
    void SRS_10_27_theQueryAsksForTheLatestTransactionWithAPositiveAdvanceOnly() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(jdbc.query(
                        sql.capture(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                        eq(1L)))
                .thenReturn(List.of());

        clearance.outstanding(1L);

        assertThat(sql.getValue())
                .contains("MAX(x.pli_txn_id)", "t.advance_outstanding > 0", "t.employee_id = ?");
    }
}
