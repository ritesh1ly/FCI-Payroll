package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.HrmsRecords;
import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.HrmsGateway;
import in.gov.fci.payroll.integration.api.HrmsReply;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** SRS-1-70, SRS-INT-1; PENDING API-16-7: the employee reads go through the real gateway. */
class HrmsRecordReaderTest {

    private final HrmsGateway gateway = mock(HrmsGateway.class);
    private final EmployeeDirectory directory = mock(EmployeeDirectory.class);
    private final HrmsRecordReader reader = new HrmsRecordReader(gateway, directory);

    private static JsonNode json(String text) {
        return JsonMapper.builder().build().readTree(text);
    }

    private static HrmsReply accepted(String data) {
        return new HrmsReply(
                HrmsReply.Outcome.ACCEPTED, 200, null, "ok", json(data), List.of(), false, 1);
    }

    private static HrmsReply rejected(String code) {
        return new HrmsReply(
                HrmsReply.Outcome.REJECTED, 404, code, "no", null, List.of(), false, 1);
    }

    private void basicAnswers(HrmsReply reply) {
        when(gateway.status(eq(HrmsApi.GET_BASIC_SYNCUP_STATUS), any())).thenReturn(reply);
    }

    @Test
    void SRS_1_70_theBasicPayIsReadThroughTheGatewayWithTheEmployeesNumber() {
        when(directory.eisOf(5L)).thenReturn(Optional.of("104882"));
        basicAnswers(accepted("{\"hrms_basic\":65000.5}"));
        assertThat(reader.current(5L, "BASIC_PAY"))
                .contains(new HrmsRecords.Fetched("65000.50", null));
        verify(gateway)
                .status(
                        HrmsApi.GET_BASIC_SYNCUP_STATUS,
                        Map.of("hist_flag", "N", "emp_num", "104882"));
    }

    @Test
    void SRS_1_70_aFieldNoApiReadsIsUnavailableAndNothingIsCalled() {
        assertThatThrownBy(() -> reader.current(5L, "PAN"))
                .isInstanceOf(HrmsRecords.Unavailable.class)
                .hasMessageContaining("PAN");
        verifyNoInteractions(gateway, directory);
    }

    @Test
    void SRS_1_70_anUnknownEmployeeIsUnavailable() {
        when(directory.eisOf(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> reader.current(5L, "BASIC_PAY"))
                .isInstanceOf(HrmsRecords.Unavailable.class);
    }

    @Test
    void SRS_1_70_hrmsHoldingNoRecordIsNoValueNotAFailure() {
        when(directory.eisOf(5L)).thenReturn(Optional.of("104882"));
        for (String code : List.of("EMPLOYEE_NOT_FOUND", "NO_SYNC_RECORD", "NO_PAYROLL_DATA")) {
            basicAnswers(rejected(code));
            assertThat(reader.current(5L, "BASIC_PAY")).isEmpty();
        }
    }

    @Test
    void SRS_1_70_anyOtherAnswerIsUnavailableNeverAGuess() {
        when(directory.eisOf(5L)).thenReturn(Optional.of("104882"));
        basicAnswers(rejected("BAD_REQUEST"));
        assertThatThrownBy(() -> reader.current(5L, "BASIC_PAY"))
                .isInstanceOf(HrmsRecords.Unavailable.class);
        when(gateway.status(eq(HrmsApi.GET_BASIC_SYNCUP_STATUS), any()))
                .thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> reader.current(5L, "BASIC_PAY"))
                .isInstanceOf(HrmsRecords.Unavailable.class)
                .hasCauseInstanceOf(FciException.class);
    }

    @Test
    void SRS_1_70_anAcceptedAnswerWithoutAUsableAmountIsNoValue() {
        when(directory.eisOf(5L)).thenReturn(Optional.of("104882"));
        for (String data :
                List.of(
                        "{}",
                        "{\"hrms_basic\":null}",
                        "{\"hrms_basic\":\" \"}",
                        "{\"hrms_basic\":\"n/a\"}")) {
            basicAnswers(accepted(data));
            assertThat(reader.current(5L, "BASIC_PAY")).isEmpty();
        }
    }

    @Test
    void API_16_7_theSnapshotJoinsBothReads() {
        basicAnswers(accepted("{\"hrms_basic\":65000}"));
        when(gateway.status(eq(HrmsApi.GET_PAYROLL_STATUST), any()))
                .thenReturn(accepted("{\"emp_status\":\"ACTIVE\"}"));
        assertThat(reader.snapshot("104882"))
                .isEqualTo(new HrmsRecords.Snapshot(true, new BigDecimal("65000"), "ACTIVE"));
    }

    @Test
    void API_16_7_oneReadFailingStillShowsTheOther() {
        basicAnswers(rejected("NO_SYNC_RECORD"));
        when(gateway.status(eq(HrmsApi.GET_PAYROLL_STATUST), any()))
                .thenReturn(accepted("{\"emp_status\":\"ACTIVE\"}"));
        assertThat(reader.snapshot("104882"))
                .isEqualTo(new HrmsRecords.Snapshot(true, null, "ACTIVE"));
    }

    @Test
    void API_16_7_nothingReadIsUnavailableAndNeverThrows() {
        when(gateway.status(any(), any())).thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        assertThat(reader.snapshot("104882")).isEqualTo(HrmsRecords.UNAVAILABLE);
    }
}
