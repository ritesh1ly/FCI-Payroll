package in.gov.fci.payroll.loans.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The loans clearance of a Full &amp; Final Settlement (plan 11 phase 11b; SRS-10-27, BR-FNF-006;
 * D11-8): an interest-bearing account is a loan, an interest-free one an advance; the reference is
 * the loan number, else the type and sanction date.
 */
class LoanClearanceTest {

    @Test
    @SuppressWarnings("unchecked")
    void SRS_10_27_eachOutstandingAccountIsALoanOrAnAdvanceWithItsReference() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArgumentCaptor<RowMapper<LoanClearance.Outstanding>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.query(anyString(), mapper.capture(), eq(7L))).thenReturn(List.of());
        assertThat(new LoanClearance(jdbc).outstanding(7L)).isEmpty();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("loan_id")).thenReturn(3L);
        when(rs.getString("loan_type_code")).thenReturn("FESTIVAL");
        when(rs.getDate("sanction_date")).thenReturn(Date.valueOf("2026-04-10"));
        when(rs.getBigDecimal("owed")).thenReturn(new BigDecimal("1500"));
        when(rs.getString("is_interest_bearing")).thenReturn("Y", "N");
        when(rs.getString("loan_number")).thenReturn("HBA/1", "HBA/1", null);
        LoanClearance.Outstanding loan = mapper.getValue().mapRow(rs, 0);
        assertThat(loan.kind()).isEqualTo("LOAN");
        assertThat(loan.reference()).isEqualTo("HBA/1");
        LoanClearance.Outstanding advance = mapper.getValue().mapRow(rs, 1);
        assertThat(advance.kind()).isEqualTo("ADVANCE");
        assertThat(advance.reference()).isEqualTo("FESTIVAL of 2026-04-10");
        assertThat(advance.amount()).isEqualByComparingTo("1500");
    }
}
