package in.gov.fci.payroll.payroll.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.error.FciException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** SRS-15-35: the release guard over the two payment channels. */
class ReleaseSuspensionsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReleaseSuspensions guard = new ReleaseSuspensions(jdbc);

    private void open(String channel, List<Long> ids) {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(7L), eq(channel))).thenReturn(ids);
    }

    @Test
    void SRS_15_35_noOpenStopLetsThePaymentThrough() {
        open("BANK_FILE", List.of());
        open("EBS_AP_POSTING", List.of());
        assertThatCode(() -> guard.requireNoPaymentSuspended(7L)).doesNotThrowAnyException();
    }

    @Test
    void SRS_15_35_aBankFileStopBlocksThePayment() {
        open("BANK_FILE", List.of(3L));
        open("EBS_AP_POSTING", List.of());
        assertThatThrownBy(() -> guard.requireNoPaymentSuspended(7L))
                .isInstanceOf(FciException.class);
    }

    @Test
    void SRS_15_35_anEbsPostingStopBlocksThePayment() {
        open("BANK_FILE", List.of());
        open("EBS_AP_POSTING", List.of(4L));
        assertThatThrownBy(() -> guard.requireNoPaymentSuspended(7L))
                .isInstanceOf(FciException.class);
    }
}
