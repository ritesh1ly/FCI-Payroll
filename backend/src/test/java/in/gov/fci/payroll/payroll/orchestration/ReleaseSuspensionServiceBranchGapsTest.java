package in.gov.fci.payroll.payroll.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * O-02 emergency stop of payment release (SRS-15-35): the list names the waiting resumption of a
 * suspension only while it waits (none for one never asked, nor for one decided); a unit scope with
 * a blank unit code names no unit and is refused, an all-units scope with a blank code names none
 * and is taken.
 */
class ReleaseSuspensionServiceBranchGapsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final Authorization policy = mock(Authorization.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final ReleaseSuspensionService service =
            new ReleaseSuspensionService(
                    db.jdbc,
                    db.named,
                    mock(OrgDirectory.class),
                    policy,
                    mock(MakerChecker.class),
                    statuses,
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-28T06:00:00Z"), ZoneOffset.UTC),
                    JsonMapper.builder().build());

    private final AccessContext finance =
            new AccessContext(
                    5L,
                    "finance@hq",
                    "Finance",
                    "s",
                    "P",
                    1L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of("FINANCE"),
                    Set.of(1L));

    private static Map<String, Object> suspension(long id) {
        return cols(
                id,
                "ALL_UNITS",
                null,
                "ALL",
                "finance@hq",
                Timestamp.from(Instant.parse("2026-10-27T06:00:00Z")),
                "Bank file mismatch",
                null,
                null,
                null);
    }

    @Test
    void SRS_15_35_theListNamesTheWaitingResumptionOnlyWhileItWaits() {
        when(policy.isAtHeadquarters(finance)).thenReturn(true);
        db.rows("s.suspension_id, s.scope_type", suspension(1L), suspension(2L), suspension(3L))
                .rows("SELECT entity_key, MAX(approval_id)", cols("2", 20L), cols("3", 30L));
        when(statuses.of(any()))
                .thenReturn(
                        Map.of(20L, ApprovalStatus.REJECTED, 30L, ApprovalStatus.PENDING_APPROVAL));
        List<ReleaseSuspensionService.SuspensionView> rows =
                service.list(finance, new PageSpec(0, 20)).items();
        assertThat(rows)
                .extracting(ReleaseSuspensionService.SuspensionView::pendingApprovalId)
                .containsExactly(null, null, 30L);
        assertThat(rows.getFirst().resumedAt()).isNull();
    }

    @Test
    void SRS_15_35_aBlankUnitCodeNamesNoUnit() {
        when(policy.isAtHeadquarters(finance)).thenReturn(true);
        assertThatThrownBy(() -> service.suspend(finance, "UNIT", "  ", "ALL", "Mismatch"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.SUSPENSION_SCOPE);
                            assertThat(e.getMessageArgs()).containsExactly("UNIT_REQUIRED");
                        });
        assertThat(service.suspend(finance, "ALL_UNITS", "  ", "BANK_FILE", "Mismatch"))
                .isEqualTo(1000L);
        assertThat(db.updates("INSERT INTO disb_release_suspension").getFirst().args().get(1))
                .isNull();
    }
}
