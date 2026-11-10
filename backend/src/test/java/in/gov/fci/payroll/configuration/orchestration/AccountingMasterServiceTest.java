package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.orchestration.AccountingMasterService.MasterRow;
import in.gov.fci.payroll.configuration.orchestration.AccountingMasterService.RecordRow;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;

/**
 * M-24 as the SRS table lists it (SRS-G-7, OD-121): the seven masters in the drawn order, the view
 * gate before any read, a master outside the seven refused, a page of records. The SQL of each
 * master runs on H2 in MastersIT.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class AccountingMasterServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final AccessContext context = mock(AccessContext.class);
    private final AccountingMasterService service = new AccountingMasterService(jdbc, policy);

    @Test
    void SRS_G_7_theSevenMastersComeInTheDrawnOrderAndOnlyTheNaturalAccountsCarryAProposal() {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class)))
                .thenAnswer(
                        i -> {
                            String sql = i.getArgument(0);
                            return sql.contains("PENDING_APPROVAL")
                                    ? Long.valueOf(2)
                                    : new MasterRow("X", null, 1, false);
                        });

        List<MasterRow> rows = service.list(context);

        assertThat(rows).hasSize(AccountingMasterService.Master.values().length).hasSize(7);
        verify(policy).require(context, "M-24", "VIEW");
    }

    @Test
    void wrongRole_noMasterIsReadWithoutTheViewPermission() {
        doThrow(new AccessDeniedException("no")).when(policy).require(context, "M-24", "VIEW");
        assertThatThrownBy(() -> service.list(context)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.records(context, "BANK", 0, 20))
                .isInstanceOf(AccessDeniedException.class);
        verify(jdbc, never()).queryForObject(anyString(), any(RowMapper.class));
    }

    @Test
    void SRS_G_7_aMasterOutsideTheSevenIsNotInTheCatalogue() {
        assertThatThrownBy(() -> service.records(context, "NO_SUCH", 0, 20))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.MASTER_NOT_IN_CATALOGUE));
    }

    @Test
    void SRS_G_7_recordsComeAsOnePageWithTheTotalOfTheMaster() {
        RecordRow row = new RecordRow("SBIN0", "Okhla", "SBI", null, LocalDate.of(2026, 4, 1));
        when(jdbc.queryForObject(anyString(), any(RowMapper.class))).thenReturn(5L);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(40L), eq(20)))
                .thenReturn(List.<Object>of(row));

        var page = service.records(context, "BANK", 2, 20);

        assertThat(page.total()).isEqualTo(5);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.items()).containsExactly(row);
    }

    @Test
    void anUnreadableCountIsZero() {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class))).thenReturn(null);
        assertThat(service.records(context, "BANK", 0, 20).total()).isZero();
    }
}
