package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.InterfaceIntake;
import in.gov.fci.payroll.integration.api.InterfaceSettings;
import in.gov.fci.payroll.integration.api.InterfaceSettings.Settings;
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The rules of an inbound call that need a failing neighbour (SRS-INT-5, SRS-INT-12, SRS-INT-27,
 * SRS-INT-31, SRS-INT-32; BR-INT-004): a conflicting repeat, a concurrent staging, a technical
 * failure while applying, several requests in one body with one bad among good, and the
 * reprocess_of resend. The neighbours (staging port, employee directory, I-21) are mocks.
 */
class HrmsInboundServiceTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private InterfaceIntake intake;
    private InterfaceSettings settings;
    private EmployeeDirectory employees;
    private HrmsChanges changes;
    private IntegrationStore store;
    private JdbcTemplate jdbc;
    private FailureAlerts alerts;
    private HrmsInboundService service;

    /** payload stored per (type:request) by the staging stand-in. */
    private final List<Object[]> existing = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        intake = mock(InterfaceIntake.class);
        settings = mock(InterfaceSettings.class);
        employees = mock(EmployeeDirectory.class);
        changes = mock(HrmsChanges.class);
        store = mock(IntegrationStore.class);
        jdbc = mock(JdbcTemplate.class);
        alerts = mock(FailureAlerts.class);
        when(store.jdbc()).thenReturn(jdbc);
        when(store.interfaceOf(anyString()))
                .thenAnswer(
                        i ->
                                new IntegrationStore.Registered(
                                        1L, i.getArgument(0), "HRMS", "INBOUND"));
        when(settings.inForce(anyString()))
                .thenReturn(
                        Optional.of(
                                new Settings(
                                        "X", 1, 1, "FIXED", 5, false, null, null, null, TODAY,
                                        true)));
        when(intake.open(anyString(), anyString(), any(), any())).thenReturn(5L);
        when(employees.idOf("160661")).thenReturn(Optional.of(11L));
        when(employees.employeeTypeOf(11L)).thenReturn(Optional.of("STAFF"));
        when(employees.idOf("DL0001")).thenReturn(Optional.of(12L));
        when(employees.employeeTypeOf(12L)).thenReturn(Optional.of("DEPT_LABOUR"));
        when(jdbc.query(
                        contains("FROM intf_staging_txn WHERE"),
                        any(RowMapper.class),
                        any(),
                        any()))
                .thenAnswer(
                        i -> {
                            RowMapper<Object> mapper = i.getArgument(1);
                            List<Object> out = new ArrayList<>();
                            for (Object[] row : existing) {
                                if (row[0].equals(i.getArgument(2))
                                        && row[1].equals(i.getArgument(3))) {
                                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                                    when(rs.getString(1)).thenReturn((String) row[2]);
                                    when(rs.getString(2)).thenReturn((String) row[3]);
                                    when(rs.getString(3)).thenReturn((String) row[4]);
                                    out.add(mapper.mapRow(rs, 0));
                                }
                            }
                            return out;
                        });
        when(intake.stage(anyLong(), any()))
                .thenAnswer(i -> new InterfaceIntake.Staged(77L, InterfaceIntake.Outcome.STAGED));
        service =
                new HrmsInboundService(
                        store,
                        intake,
                        settings,
                        new HrmsApiProperties(
                                null,
                                Map.of(),
                                Map.of(),
                                Map.of("request_pay_travel_allowance", "MEDICAL")),
                        employees,
                        changes,
                        alerts,
                        Clock.systemUTC());
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String travel(String request, String emp) {
        return "{\"request_id\":"
                + request
                + ",\"emp_num\":"
                + emp
                + ",\"sanction_amount\":100,\"from_date\":\""
                + TODAY.minusDays(3)
                + "\",\"to_date\":\""
                + TODAY.minusDays(1)
                + "\"}";
    }

    private HrmsInboundService.Ack one(HrmsApi api, Map<String, String> query, String body) {
        HrmsInboundService.Result r = service.intake(api, query, bytes(body));
        assertThat(r.acks()).hasSize(1);
        return r.acks().getFirst();
    }

    @Test
    void SRS_INT_5_theSameContentAgainAnswersTheEarlierResult() {
        existing.add(new Object[] {"TRAVEL_REIMB", "7", "APPLIED", null, travel("7", "160661")});
        var ack = one(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE, Map.of(), travel("7", "160661"));
        assertThat(ack.status()).isEqualTo("Accepted");
        assertThat(ack.duplicate()).isTrue();
        verify(changes, never()).receive(any());
    }

    @Test
    void SRS_INT_5_differentContentUnderTheSameRequestIdIsConflictingAndNeverOverwritten() {
        existing.add(new Object[] {"TRAVEL_REIMB", "7", "APPLIED", null, travel("7", "160661")});
        var ack =
                one(
                        HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                        Map.of(),
                        travel("7", "160661").replace("100", "999"));
        assertThat(ack.http()).isEqualTo(409);
        assertThat(ack.status()).isEqualTo("Rejected");
        assertThat(ack.reason()).contains("different content").contains("unchanged");
        verify(changes, never()).receive(any());
        verify(intake, times(1)).stage(anyLong(), any());
        verify(intake)
                .completeCounts(eq(5L), eq(1L), eq(0L), eq(1L), contains("different content"));
    }

    @Test
    void SRS_INT_5_aRejectedRequestRepeatedAnswersItsReason() {
        existing.add(
                new Object[] {
                    "TRAVEL_REIMB", "7", "REJECTED", "ALW-409: why", travel("7", "160661")
                });
        var ack = one(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE, Map.of(), travel("7", "160661"));
        assertThat(ack.status()).isEqualTo("Rejected");
        assertThat(ack.reason()).isEqualTo("ALW-409: why");
        assertThat(ack.http()).isEqualTo(422);
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_INT_12_twoCallsRacingToStageOneRequestLeaveOneRecordAndAnswerTheWinner() {
        when(intake.stage(anyLong(), any()))
                .thenThrow(new DuplicateKeyException("uk_intf_staging_txn"));
        existing.clear();
        // The winner's record is visible only after the loser's insert failed.
        when(jdbc.query(
                        contains("FROM intf_staging_txn WHERE"),
                        any(RowMapper.class),
                        any(),
                        any()))
                .thenReturn(List.of())
                .thenAnswer(
                        i -> {
                            RowMapper<Object> mapper = i.getArgument(1);
                            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                            when(rs.getString(1)).thenReturn("RECEIVED");
                            when(rs.getString(3))
                                    .thenReturn(
                                            travel("7", "160661")
                                                    .replace("sanction_amount", "sanction_amount"));
                            return List.of(mapper.mapRow(rs, 0));
                        });
        var ack = one(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE, Map.of(), travel("7", "160661"));
        assertThat(ack.duplicate()).isTrue();
        assertThat(ack.status()).isEqualTo("Received");
        verify(changes, never()).receive(any());
    }

    @Test
    void SRS_INT_31_aFailureWhileApplyingRejectsTheStagedRecordAndSaysHowToResend() {
        when(changes.receive(any())).thenThrow(new IllegalStateException("db down"));
        var ack = one(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE, Map.of(), travel("8", "160661"));
        assertThat(ack.http()).isEqualTo(503);
        assertThat(ack.status()).isEqualTo("Rejected");
        assertThat(ack.reason())
                .contains("nothing was changed")
                .contains("reprocess_of=8")
                .doesNotContain("db down");
        verify(intake).rejected(eq(77L), contains("Technical failure"));
        verify(intake, never()).applied(anyLong(), anyString(), anyString());
        verify(intake).completeCounts(eq(5L), eq(1L), eq(0L), eq(1L), isNull());
    }

    @Test
    void SRS_INT_26_theOwningModulesRefusalIsTheReasonOfTheRejection() {
        when(changes.receive(any())).thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        var ack = one(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE, Map.of(), travel("9", "160661"));
        assertThat(ack.status()).isEqualTo("Rejected");
        assertThat(ack.reason()).startsWith("COM-404");
    }

    @Test
    void SRS_INT_31_aResendNamesTheRejectedRequestAndIsLinked() {
        one(
                HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                Map.of("reprocess_of", "7"),
                travel("70", "160661"));
        ArgumentCaptor<InterfaceIntake.Inbound> sent =
                ArgumentCaptor.forClass(InterfaceIntake.Inbound.class);
        verify(intake).stage(anyLong(), sent.capture());
        assertThat(sent.getValue().reprocessOfRequestId()).isEqualTo("7");
        assertThat(sent.getValue().requestId()).isEqualTo("70");
    }

    @Test
    void SRS_INT_31_aResendOfSomethingNotRejectedIsRefusedBeforeAnythingIsKept() {
        when(intake.stage(anyLong(), any())).thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        var ack =
                one(
                        HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                        Map.of("reprocess_of", "7"),
                        travel("70", "160661"));
        assertThat(ack.status()).isEqualTo("Rejected");
        assertThat(ack.reason()).contains("reprocess_of");
        verify(changes, never()).receive(any());
    }

    @Test
    void SRS_INT_31_aReprocessOfThatIsNotAnIdIsRefusedForTheWholeCall() {
        assertThatThrownBy(
                        () ->
                                service.intake(
                                        HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                                        Map.of("reprocess_of", "7; DROP"),
                                        bytes(travel("70", "160661"))))
                .isInstanceOf(FciException.class)
                .hasMessage("INT-422-RECORD");
    }

    @Test
    void SRS_INT_27_aLabourMemberHasNoHrmsInterface() {
        var ack = one(HrmsApi.REQUEST_OTA, Map.of(), "{\"request_id\":1,\"emp_num\":\"DL0001\"}");
        assertThat(ack.status()).isEqualTo("Rejected");
        assertThat(ack.reason()).contains("labour");
        verify(intake, never()).stage(anyLong(), any());
    }

    @Test
    void SRS_INT_32_oneBadRequestAmongGoodOnesIsRejectedAndEveryWindowBalances() {
        String body =
                "[{\"request_id\":\"C1\",\"emp_num\":\"160661\",\"pay_code\":\"211\",\"percent\":\"6\"},"
                    + "{\"request_id\":\"C1\",\"emp_num\":\"160661\",\"pay_code\":\"208\",\"percent\":\"10\"},"
                    + "{\"request_id\":\"C2\",\"emp_num\":\"160661\",\"effective_dt\":\"2023-02-30\"},"
                    + "{\"request_id\":\"C3\",\"emp_num\":\"nobody\"},"
                    + "{\"request_id\":\"C4\",\"emp_num\":\"160661\",\"pay_code\":\"217\"}]";
        HrmsInboundService.Result r =
                service.intake(HrmsApi.POST_CAFETERIA_ALLOWANCE, Map.of(), bytes(body));
        assertThat(r.http()).isEqualTo(207);
        assertThat(r.acks())
                .extracting(HrmsInboundService.Ack::requestId)
                .containsExactly("C1", "C2", "C3", "C4");
        assertThat(r.acks())
                .extracting(HrmsInboundService.Ack::status)
                .containsExactly("Received", "Rejected", "Rejected", "Received");
        assertThat(r.acks().get(1).reason()).contains("effective_dt");
        assertThat(r.acks().get(2).reason()).contains("not a Payroll employee");
        // four windows, each received = accepted + rejected
        verify(intake, times(4)).open(anyString(), anyString(), any(), any());
        verify(intake, times(2)).completeCounts(eq(5L), eq(1L), eq(1L), eq(0L), isNull());
        verify(intake, times(2)).completeCounts(eq(5L), eq(1L), eq(0L), eq(1L), anyString());
        // the two rows of C1 are kept together as one staged record
        ArgumentCaptor<InterfaceIntake.Inbound> sent =
                ArgumentCaptor.forClass(InterfaceIntake.Inbound.class);
        verify(intake, times(2)).stage(anyLong(), sent.capture());
        assertThat(sent.getAllValues().getFirst().payload())
                .startsWith("[{")
                .contains("\"211\"")
                .contains("\"208\"");
    }

    @Test
    void SRS_INT_32_rowsOfOneRequestForTwoEmployeesAreRefused() {
        String body =
                "[{\"request_id\":\"C1\",\"emp_num\":\"160661\"},{\"request_id\":\"C1\",\"emp_num\":\"160662\"}]";
        var ack =
                service.intake(HrmsApi.POST_CAFETERIA_ALLOWANCE, Map.of(), bytes(body))
                        .acks()
                        .getFirst();
        assertThat(ack.status()).isEqualTo("Rejected");
        assertThat(ack.reason()).contains("share emp_num");
    }

    @Test
    void SRS_INT_32_aRowWithoutAUsableRequestIdIsItsOwnRejectedRequest() {
        String body =
                "[{\"emp_num\":\"160661\"},{\"emp_num\":\"160661\"},{\"request_id\":\"C1\",\"emp_num\":\"160661\"}]";
        HrmsInboundService.Result r =
                service.intake(HrmsApi.POST_CAFETERIA_ALLOWANCE, Map.of(), bytes(body));
        assertThat(r.acks()).hasSize(3);
        assertThat(r.acks())
                .extracting(HrmsInboundService.Ack::status)
                .containsExactly("Rejected", "Rejected", "Received");
    }

    @Test
    void SRS_INT_23_theQueryRequestIdMustAgreeWithTheBody() {
        var ack =
                one(
                        HrmsApi.UPDATING_HRA_DA,
                        Map.of("request_id", "99", "emp_num", "160661"),
                        "[{\"request_id\":98,\"request_for\":\"HRA\"}]");
        assertThat(ack.reason()).contains("differs");
        var ok =
                one(
                        HrmsApi.UPDATING_HRA_DA,
                        Map.of("request_id", "98", "emp_num", "160661"),
                        "[{\"request_id\":98,\"request_for\":\"HRA\"}]");
        assertThat(ok.status()).isEqualTo("Received");
    }

    @Test
    void SRS_INT_16_anInterfaceWithoutSettingsIsRefusedBeforeAnythingIsRead() {
        when(settings.inForce(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.intake(HrmsApi.REQUEST_OTA, Map.of(), bytes("not json")))
                .isInstanceOf(FciException.class)
                .hasMessage("INT-503-NOT-CONFIGURED");
        verify(intake, never()).open(anyString(), anyString(), any(), any());
    }

    @Test
    void SRS_INT_12_anUnreadableBodyIsAMalformedRequestNotAStagedOne() {
        for (String bad :
                new String[] {"", "{", "[1]", "{\"request_id\":1,\"request_id\":2}", "\"text\""}) {
            assertThatThrownBy(() -> service.intake(HrmsApi.REQUEST_OTA, Map.of(), bytes(bad)))
                    .as(bad)
                    .isInstanceOf(FciException.class)
                    .hasMessage("INT-400-HRMS-BODY");
        }
        verify(intake, never()).open(anyString(), anyString(), any(), any());
    }

    @Test
    void SRS_INT_26_aSeparatedOrTransferredEmployeeIsStillAnHrmsEmployee() {
        // The sources state no rule that refuses HRMS records by employment status: staged, and the
        // owning module decides on approval (PENDING API-23-14).
        var ack = one(HrmsApi.REQUEST_OTA, Map.of(), "{\"request_id\":1,\"emp_num\":\"160661\"}");
        assertThat(ack.status()).isEqualTo("Received");
        verify(employees, never()).member(any());
    }

    @Test
    void SRS_INT_26_anApiThatIsNotAnIntakeApiHasNoContractToTake() {
        assertThatThrownBy(() -> service.intake(HrmsApi.GET_OTA_STATUS, Map.of(), bytes("{}")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(intake, never()).open(anyString(), anyString(), any(), any());
    }

    @Test
    void SRS_INT_26_aStagingRefusalWithoutAResendIsTheRejectionReason() {
        when(intake.stage(anyLong(), any())).thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        var ack = one(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE, Map.of(), travel("71", "160661"));
        assertThat(ack.http()).isEqualTo(422);
        assertThat(ack.status()).isEqualTo("Rejected");
        assertThat(ack.reason()).startsWith("COM-404").doesNotContain("reprocess_of");
        verify(alerts).raise(any(), eq(5L), isNull(), anyString(), contains("COM-404"));
        verify(changes, never()).receive(any());
    }

    @Test
    void SRS_INT_31_aResendIdWithSpacesIsStrippedBeforeItIsLinked() {
        one(
                HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                Map.of("reprocess_of", " 7 "),
                travel("72", "160661"));
        ArgumentCaptor<InterfaceIntake.Inbound> sent =
                ArgumentCaptor.forClass(InterfaceIntake.Inbound.class);
        verify(intake).stage(anyLong(), sent.capture());
        assertThat(sent.getValue().reprocessOfRequestId()).isEqualTo("7");
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_INT_12_aRaceWhoseWinnerKeptAnUnreadablePayloadIsAConflictNotAMatch() {
        when(intake.stage(anyLong(), any()))
                .thenThrow(new DuplicateKeyException("uk_intf_staging_txn"));
        existing.clear();
        when(jdbc.query(
                        contains("FROM intf_staging_txn WHERE"),
                        any(RowMapper.class),
                        any(),
                        any()))
                .thenReturn(List.of())
                .thenAnswer(
                        i -> {
                            RowMapper<Object> mapper = i.getArgument(1);
                            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                            when(rs.getString(1)).thenReturn("RECEIVED");
                            when(rs.getString(3)).thenReturn("{ not json");
                            return List.of(mapper.mapRow(rs, 0));
                        });
        var ack = one(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE, Map.of(), travel("73", "160661"));
        assertThat(ack.http()).isEqualTo(409);
        assertThat(ack.duplicate()).isTrue();
        verify(changes, never()).receive(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_INT_5_aStagingPortThatReportsADuplicateAnswersTheEarlierRecord() {
        when(intake.stage(anyLong(), any()))
                .thenReturn(new InterfaceIntake.Staged(77L, InterfaceIntake.Outcome.DUPLICATE));
        existing.clear();
        var first = new java.util.concurrent.atomic.AtomicInteger();
        when(jdbc.query(
                        contains("FROM intf_staging_txn WHERE"),
                        any(RowMapper.class),
                        any(),
                        any()))
                .thenAnswer(
                        i -> {
                            if (first.getAndIncrement() == 0) {
                                return List.of();
                            }
                            RowMapper<Object> mapper = i.getArgument(1);
                            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                            when(rs.getString(1)).thenReturn("CANCELLED");
                            when(rs.getString(2)).thenReturn("withdrawn");
                            when(rs.getString(3)).thenReturn(travel("74", "160661"));
                            return List.of(mapper.mapRow(rs, 0));
                        });
        var ack = one(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE, Map.of(), travel("74", "160661"));
        assertThat(ack.status()).isEqualTo("Cancelled");
        assertThat(ack.reason()).isEqualTo("withdrawn");
        assertThat(ack.duplicate()).isTrue();
    }

    @Test
    void SRS_INT_5_everyStagingStatusHasItsAcknowledgement() {
        assertThat(HrmsInboundService.ack("APPLIED", "1", "x", false).http()).isEqualTo(200);
        assertThat(HrmsInboundService.ack("APPLIED", "1", "x", false).reason()).isNull();
        for (String status : new String[] {"REJECTED", "ERROR"}) {
            var ack = HrmsInboundService.ack(status, "1", "why", true);
            assertThat(ack.http()).isEqualTo(422);
            assertThat(ack.status()).isEqualTo("Rejected");
            assertThat(ack.reason()).isEqualTo("why");
            assertThat(ack.duplicate()).isTrue();
        }
        var cancelled = HrmsInboundService.ack("CANCELLED", "1", "gone", false);
        assertThat(cancelled.http()).isEqualTo(200);
        assertThat(cancelled.status()).isEqualTo("Cancelled");
        assertThat(cancelled.reason()).isEqualTo("gone");
        for (String status : new String[] {"RECEIVED", "STAGED", "PENDING", ""}) {
            var received = HrmsInboundService.ack(status, "1", "ignored", false);
            assertThat(received.http()).isEqualTo(202);
            assertThat(received.status()).isEqualTo("Received");
            assertThat(received.reason()).isNull();
        }
    }

    @Test
    void SRS_INT_12_aRefusalWhoseDetailIsNotAFormatFallsBackToItsTitleAndIsCut() {
        var broken =
                new in.gov.fci.payroll.platform.error.ErrorCode() {
                    @Override
                    public String code() {
                        return "TST-500-BROKEN";
                    }

                    @Override
                    public org.springframework.http.HttpStatus status() {
                        return org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
                    }

                    @Override
                    public String type() {
                        return "test/broken";
                    }

                    @Override
                    public String defaultTitle() {
                        return "Broken title " + "t".repeat(600);
                    }

                    @Override
                    public String defaultDetail() {
                        return "unbalanced { brace";
                    }
                };
        String reason = HrmsInboundService.reasonOf(new FciException(broken));
        assertThat(reason).startsWith("TST-500-BROKEN: Broken title").hasSize(500);
        String ok =
                HrmsInboundService.reasonOf(
                        new FciException(
                                in.gov.fci.payroll.integration.business.IntegrationErrorCode
                                        .HRMS_REQUEST_NOT_FOUND,
                                new Object[] {"4516"},
                                List.of(),
                                null));
        assertThat(ok).startsWith("INT-").doesNotContain("{0}");
    }
}
