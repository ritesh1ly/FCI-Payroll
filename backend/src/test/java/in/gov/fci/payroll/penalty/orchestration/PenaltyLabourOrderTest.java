package in.gov.fci.payroll.penalty.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.NetPayFloors;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.penalty.business.PenaltyErrorCode;
import in.gov.fci.payroll.penalty.orchestration.PenaltyOrderService.Preview;
import in.gov.fci.payroll.penalty.orchestration.PenaltyOrderService.Request;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * A penalty entered for a labourer on I-06 (SRS-14-1 "Enter a labour penalty in Payroll", SRS-14-8,
 * SRS-14-13; Q-12-1) and the floor of the member's own payroll type (SRS-16-13, SRS-14-10). Before
 * this, every order was tested on the STAFF floor and any nature but RECOVERY was refused.
 */
class PenaltyLabourOrderTest {

    private static final LocalDate DAY = LocalDate.of(2026, 4, 1);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final RuleEngine rules = mock(RuleEngine.class);
    private final NetPayFloors floors = mock(NetPayFloors.class);
    private final ReferenceData references = mock(ReferenceData.class);
    private final Authorization policy = mock(Authorization.class);
    private final AccessContext context = mock(AccessContext.class);
    private final PenaltyOrderService service =
            new PenaltyOrderService(
                    jdbc,
                    employees,
                    org,
                    payroll,
                    rules,
                    floors,
                    references,
                    policy,
                    mock(MakerChecker.class),
                    mock(DocumentStore.class),
                    JsonMapper.builder().build(),
                    Clock.fixed(Instant.parse("2026-04-10T05:00:00Z"), ZoneOffset.UTC),
                    mock(in.gov.fci.payroll.audit.api.AuditService.class));

    PenaltyLabourOrderTest() {
        when(employees.memberByEis("DL1"))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(2L, "DL1", "P", 5L, "IN_SERVICE")));
        when(references.contains(eq(PenaltyOrderService.NATURES), any())).thenReturn(true);
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(3L);
        when(org.cycleUnitOf(5L)).thenReturn(Optional.of(unit));
        when(jdbc.queryForObject(
                        contains("SELECT current_basic"),
                        eq(BigDecimal.class),
                        any(Object[].class)))
                .thenReturn(new BigDecimal("22600"));
        when(jdbc.queryForList(
                        contains("PENALTY_RECOVERY"), eq(BigDecimal.class), any(Object[].class)))
                .thenAnswer(
                        call ->
                                "RECOVERY".equals(call.getArgument(2))
                                        ? List.of(new BigDecimal("401"))
                                        : List.of());
    }

    private void type(String t) {
        when(employees.employeeTypeOf(2L)).thenReturn(Optional.of(t));
    }

    private static Request withoutRecovery(String nature, LocalDate to) {
        return new Request("DL1", nature, "DO/1", DAY, null, 0, null, null, to);
    }

    private void refused(Request r, String why) {
        assertThatThrownBy(() -> service.preview(context, r))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(PenaltyErrorCode.ORDER);
                            assertThat(ex.getMessageArgs()).containsExactly(why);
                        });
    }

    @Test
    void SRS_14_1_aLabourIncrementStopIsEnteredWithoutRecoveryTerms() {
        type("DEPT_LABOUR");

        Preview p =
                service.preview(
                        context, withoutRecovery("INCREMENT_STOP", LocalDate.of(2026, 12, 31)));

        assertThat(p.nature()).isEqualTo("INCREMENT_STOP");
        assertThat(p.amount()).isNull();
        assertThat(p.schedule()).isEmpty();
        assertThat(p.belowFloor()).isFalse();
        assertThat(p.currentBasic()).isEqualByComparingTo("22600");
    }

    @Test
    void SRS_14_1_anEmployeePenaltyStaysHrmsOnly() {
        type("STAFF");

        assertThatThrownBy(() -> service.preview(context, withoutRecovery("BASIC_REDUCTION", null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(PenaltyErrorCode.HRMS_ONLY));
    }

    @Test
    void SRS_14_8_aNatureThatRecoversNothingTakesNoTerms() {
        type("DPS_LABOUR");
        String terms = "an amount is given for a nature that recovers nothing";
        refused(
                new Request("DL1", "INCREMENT_STOP", "O", DAY, BigDecimal.TEN, 0, null, null, null),
                terms);
        refused(new Request("DL1", "INCREMENT_STOP", "O", DAY, null, 3, null, null, null), terms);
        refused(
                new Request("DL1", "INCREMENT_STOP", "O", DAY, null, 0, "202604", null, null),
                terms);
        refused(
                new Request("DL1", "INCREMENT_STOP", "O", DAY, null, 0, null, true, null),
                "an order is deferred to F&F only when it breaches the net-pay floor");
        refused(
                withoutRecovery("INCREMENT_STOP", DAY.minusDays(1)),
                "the period ends before it starts");
    }

    @Test
    void SRS_14_1_aLabourRecoveryIsTestedOnTheLabourersOwnPayroll() {
        type("NWNP_LABOUR");
        when(jdbc.queryForList(contains("FROM payroll_result"), any(Object[].class)))
                .thenAnswer(
                        call ->
                                "NWNP_LABOUR".equals(call.getArgument(2))
                                        ? List.of(
                                                Map.of(
                                                        "GROSS_EARNINGS", new BigDecimal("20000"),
                                                        "TOTAL_DEDUCTIONS", new BigDecimal("1000"),
                                                        "NET_PAY", new BigDecimal("19000")))
                                        : List.of());
        when(rules.resolve(any(), any(), eq(new RuleEngine.Context("NWNP_LABOUR", 5L))))
                .thenAnswer(
                        c -> {
                            RuleEngine.Resolved r = mock(RuleEngine.Resolved.class);
                            when(r.scalar()).thenReturn(new BigDecimal("50"));
                            return Optional.of(r);
                        });
        when(floors.inForce(eq("NWNP_LABOUR"), any()))
                .thenReturn(
                        Optional.of(
                                new NetPayFloors.Floor(
                                        "ABSOLUTE_AMOUNT", new BigDecimal("19000"), true)));

        Preview p =
                service.preview(
                        context,
                        new Request(
                                "DL1",
                                "RECOVERY",
                                "DO/1",
                                DAY,
                                new BigDecimal("6000"),
                                3,
                                "202604"));

        // SRS-16-13: the labourer's floor decides, whatever the STAFF floor says.
        assertThat(p.floor()).isEqualByComparingTo("19000");
        assertThat(p.belowFloor()).isTrue();
    }

    @Test
    void SRS_14_1_aLabourRecoveryWithNoComputedWageIsRefusedNeverPassedUntested() {
        type("DEPT_LABOUR");
        when(jdbc.queryForList(contains("FROM payroll_result"), any(Object[].class)))
                .thenReturn(List.of());
        refused(
                new Request("DL1", "RECOVERY", "O", DAY, new BigDecimal("600"), 3, "202604"),
                "the member has no computed pay yet to test the recovery on");
    }

    @Test
    void CLAUDE_6_3_aMemberOutsideTheMakersOfficeIsRefused() {
        doThrow(new FciException(CommonErrorCode.NOT_FOUND))
                .when(policy)
                .requireEmployeeRecord(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.preview(context, withoutRecovery("INCREMENT_STOP", null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
    }
}
