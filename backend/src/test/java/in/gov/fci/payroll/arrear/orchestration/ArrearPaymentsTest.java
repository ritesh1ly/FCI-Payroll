package in.gov.fci.payroll.arrear.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.arrear.business.ArrearRules;
import in.gov.fci.payroll.configuration.api.NetPayFloors;
import in.gov.fci.payroll.payroll.api.StaffDeductions;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

/**
 * Plan 13 phase 13a (SRS-8-17, SRS-8-21; D13-1, D13-2): approved arrears in the staff payroll, the
 * floor-limited recovery and the refusals when a code or the floor is not configured (R-RULE).
 */
class ArrearPaymentsTest {

    private static final LocalDate AS_OF = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private static final YearMonth MONTH = YearMonth.from(AS_OF);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NetPayFloors floors = mock(NetPayFloors.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private StaffDeductions.Run run(
            Map<String, Integer> codes,
            Optional<NetPayFloors.Floor> floor,
            List<ArrearRules.Differential> due) {
        doAnswer(
                        inv -> {
                            RowCallbackHandler handler = inv.getArgument(1);
                            for (Map.Entry<String, Integer> e : codes.entrySet()) {
                                ResultSet rs = mock(ResultSet.class);
                                when(rs.getString(1)).thenReturn(e.getKey());
                                when(rs.getBigDecimal(2))
                                        .thenReturn(BigDecimal.valueOf(e.getValue()));
                                handler.processRow(rs);
                            }
                            return null;
                        })
                .when(jdbc)
                .query(anyString(), any(RowCallbackHandler.class), any(), any());
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L), any())).thenReturn((List) due);
        when(floors.inForce("STAFF", AS_OF)).thenReturn(floor);
        return new ArrearPayments(jdbc, floors).open(AS_OF);
    }

    private static StaffDeductions.Member member(String gross, String deducted) {
        return new StaffDeductions.Member(7L, 1L, "IDA", MONTH, List.of(), new BigDecimal(gross))
                .withDeducted(new BigDecimal(deducted));
    }

    private static ArrearRules.Differential d(int code, String amount) {
        return new ArrearRules.Differential(code, new BigDecimal(amount));
    }

    private static Optional<NetPayFloors.Floor> absolute(String value) {
        return Optional.of(new NetPayFloors.Floor("ABSOLUTE_AMOUNT", new BigDecimal(value), true));
    }

    @Test
    void SRS_8_21_aPositiveArrearIsPaidUnderTheArrearCodeOfItsComponent() {
        StaffDeductions.Run run =
                run(Map.of("111", 9111), Optional.empty(), List.of(d(111, "1200.40")));
        assertThat(new ArrearPayments(jdbc, floors).order()).isEqualTo(ArrearPayments.ORDER);
        assertThat(run.earnings(member("30000", "0")))
                .containsExactly(new StaffDeductions.Earning(9111, new BigDecimal("1200")));
        assertThat(run.deductions(member("30000", "0"))).as("nothing to recover").isEmpty();
        assertThat(run.versions()).contains("arrears={111=9111}").contains("floor=-");
    }

    @Test
    void SRS_8_21_aComponentWithoutAnArrearCodeRefusesTheMember() {
        StaffDeductions.Run run = run(Map.of(), Optional.empty(), List.of(d(111, "100")));
        assertThatThrownBy(() -> run.earnings(member("30000", "0")))
                .isInstanceOf(StaffDeductions.NotComputable.class)
                .hasMessageContaining("ARREAR_PAY_CODE");
    }

    @Test
    void SRS_8_17_theRecoveryStopsAtTheNetPayFloor() {
        StaffDeductions.Run run =
                run(Map.of("RECOVERY", 9900), absolute("5000"), List.of(d(121, "-9000")));
        assertThat(run.deductions(member("30000", "20000")))
                .singleElement()
                .satisfies(
                        l -> {
                            assertThat(l.payCode()).isEqualTo(9900);
                            assertThat(l.amount()).isEqualByComparingTo("5000");
                        });
        assertThat(run.deductions(member("30000", "26000"))).as("no room: carried").isEmpty();
        assertThat(run.versions()).contains("floor=ABSOLUTE_AMOUNT:5000");
    }

    @Test
    void SRS_8_17_aRecoveryWithoutTheCodeOrTheFloorIsRefused() {
        StaffDeductions.Run noCode = run(Map.of(), absolute("5000"), List.of(d(121, "-100")));
        assertThatThrownBy(() -> noCode.deductions(member("30000", "0")))
                .isInstanceOf(StaffDeductions.NotComputable.class)
                .hasMessageContaining("RECOVERY");
        StaffDeductions.Run noFloor =
                run(Map.of("RECOVERY", 9900), Optional.empty(), List.of(d(121, "-100")));
        assertThatThrownBy(() -> noFloor.deductions(member("30000", "0")))
                .isInstanceOf(StaffDeductions.NotComputable.class)
                .hasMessageContaining("M-14");
    }

    @Test
    void SRS_8_17_aPercentFloorIsOfTheGross() {
        assertThat(
                        new NetPayFloors.Floor("PERCENT_OF_GROSS", new BigDecimal("25"), true)
                                .amount(new BigDecimal("30001")))
                .isEqualByComparingTo("7500.25");
        assertThat(new NetPayFloors.Floor("OTHER", BigDecimal.TEN, true).amount(BigDecimal.TEN))
                .isEqualByComparingTo("0");
    }

    /**
     * SRS-8-21: the member's differentials are read once per member and month; the same member in
     * another month reads that month's.
     */
    @Test
    @SuppressWarnings("unchecked")
    void SRS_8_21_theSameMemberInAnotherMonthReadsThatMonthsDifferentials() {
        StaffDeductions.Run run =
                run(Map.of("111", 9111), Optional.empty(), List.of(d(111, "100")));
        StaffDeductions.Member now = member("30000", "0");
        StaffDeductions.Member earlier =
                new StaffDeductions.Member(
                        7L, 1L, "IDA", MONTH.minusMonths(1), List.of(), new BigDecimal("30000"));
        assertThat(run.earnings(now)).hasSize(1);
        assertThat(run.deductions(now)).isEmpty();
        assertThat(run.earnings(earlier)).hasSize(1);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.times(2))
                .query(anyString(), any(RowMapper.class), eq(7L), any());
    }
}
