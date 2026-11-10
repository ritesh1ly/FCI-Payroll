package in.gov.fci.payroll.cpf.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * E-01 / E-02 VPF change (BR-ESEVA-006; plan 14 D14-4): a member who has never subscribed asks from
 * none; a member of no office asks from this month; once this month's payroll of the unit is locked
 * the change starts next month; a blank status filter is none.
 */
class VpfRequestFlowTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext member = mock(AccessContext.class);
    private final VpfChangeService service =
            new VpfChangeService(
                    db.jdbc,
                    db.named,
                    mock(Authorization.class),
                    employees,
                    org,
                    payroll,
                    mock(MakerChecker.class),
                    audit,
                    mock(ApplicationEventPublisher.class),
                    Clock.fixed(Instant.parse("2026-10-29T06:00:00Z"), ZoneOffset.UTC));

    VpfRequestFlowTest() {
        when(member.employeeId()).thenReturn(7L);
        when(member.loginId()).thenReturn("member@x");
        db.rows(
                        "FROM employee_pf_header WHERE",
                        row("pf_header_id", 31L, "vcpf_subscription_amt", null))
                .rows("WHERE r.vpf_change_request_id = ?", request());
    }

    private static Map<String, Object> request() {
        return row(
                "vpf_change_request_id",
                1000L,
                "emp_num",
                "104882",
                "first_name",
                "Asha",
                "last_name",
                null,
                "location_code",
                null,
                "location_name",
                null,
                "current_vpf_amount",
                null,
                "requested_vpf_amount",
                new BigDecimal("500"),
                "effective_from_yymm",
                "202610",
                "request_status",
                "SUBMITTED",
                "awaiting",
                "N",
                "decision_reason",
                null,
                "submitted_at",
                Timestamp.from(Instant.parse("2026-10-29T06:00:00Z")));
    }

    private void memberAt(Long location) {
        when(employees.member(7L))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(
                                        7L, "104882", "Asha", location, "IN_SERVICE")));
    }

    @Test
    void BR_ESEVA_006_aFirstSubscriptionOfAMemberOfNoOfficeStartsThisMonthFromNone() {
        memberAt(null);
        service.request(member, new BigDecimal("500"));
        assertThat(db.updates("INSERT INTO vpf_change_request"))
                .singleElement()
                .satisfies(u -> assertThat(u.args()).contains("202610"));
        verify(audit)
                .record(
                        eq("member@x"),
                        argThat(e -> e.oldValue() == null && "500".equals(e.newValue())));
    }

    @Test
    void BR_ESEVA_006_aLockedMonthMovesTheChangeToTheNextOne() {
        memberAt(40L);
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(40L);
        when(org.cycleUnitOf(40L)).thenReturn(Optional.of(unit));
        when(payroll.cycle(eq(40L), anyString()))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(9L, 40L, "202610", "LOCKED")));
        service.request(member, new BigDecimal("500"));
        assertThat(db.updates("INSERT INTO vpf_change_request"))
                .singleElement()
                .satisfies(u -> assertThat(u.args()).contains("202611"));
    }

    @Test
    void BR_ESEVA_006_aBlankStatusFilterIsNone() {
        assertThat(service.page(mock(AccessContext.class), " ", new PageSpec(0, 20)).items())
                .isEmpty();
    }
}
