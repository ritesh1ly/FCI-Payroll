package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.iam.business.model.AccessGroup;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A-03 access groups on X-04 (SRS-20-29, SRS-G-37): a group seeded with the system (no approval
 * item) is in force as seeded, and an image without a scope type, access mode or a role still in
 * the catalogue shows them as none (the role by its id); a group whose approval still waits has no
 * version yet.
 */
class AccessGroupHistoryTest {

    private final AccessRepositories.Groups groups = mock(AccessRepositories.Groups.class);
    private final AccessRepositories.GroupScopes scopes =
            mock(AccessRepositories.GroupScopes.class);
    private final AccessRepositories.Roles roles = mock(AccessRepositories.Roles.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final AccessGroupHistory history =
            new AccessGroupHistory(groups, scopes, roles, statuses);

    private AccessGroup group(String code, Long approval) {
        AccessGroup g = mock(AccessGroup.class);
        when(g.getId()).thenReturn(4L);
        when(g.getCode()).thenReturn(code);
        when(g.getName()).thenReturn("Payroll makers, Delhi");
        when(g.getRoleId()).thenReturn(5L);
        when(g.getApprovalId()).thenReturn(approval);
        when(g.getScopeType()).thenReturn(null);
        when(g.getAccessMode()).thenReturn(null);
        when(g.getEffectiveFrom()).thenReturn(LocalDate.of(2026, 4, 1));
        when(groups.findByCode(code)).thenReturn(Optional.of(g));
        return g;
    }

    @Test
    void SRS_20_29_aSeededGroupIsInForceAsSeededAndMissingAttributesShowAsNone() {
        group("AG-SEED", null);
        when(roles.findById(5L)).thenReturn(Optional.empty());
        when(scopes.findByAccessGroupIdIn(any())).thenReturn(List.of());
        HistoryRecord record = history.find("AG-SEED").orElseThrow();
        assertThat(record.versions()).hasSize(1);
        Map<String, String> image = record.versions().get(0).values();
        assertThat(image.get("role")).isEqualTo("5");
        assertThat(image.get("scope_type")).isNull();
        assertThat(image.get("access_mode")).isNull();
        assertThat(image.get("consolidated_view")).isEqualTo("N");
    }

    @Test
    void SRS_G_37_aGroupWhoseApprovalWaitsHasNoVersionYet() {
        group("AG-NEW", 70L);
        when(statuses.of(List.of(70L))).thenReturn(Map.of(70L, ApprovalStatus.PENDING_APPROVAL));
        assertThat(history.find("AG-NEW").orElseThrow().versions()).isEmpty();
        assertThat(history.find("AG-NONE")).isEmpty();
    }

    @Test
    void SRS_20_29_aConsolidatedViewGroupShowsItsScopeModeAndRole() {
        AccessGroup g = group("AG-MIS", null);
        when(g.isConsolidatedView()).thenReturn(true);
        when(g.getScopeType()).thenReturn(in.gov.fci.payroll.platform.security.ScopeType.GLOBAL);
        when(g.getAccessMode()).thenReturn(in.gov.fci.payroll.platform.security.AccessMode.FULL);
        in.gov.fci.payroll.iam.business.model.AppRole role =
                mock(in.gov.fci.payroll.iam.business.model.AppRole.class);
        when(role.getCode()).thenReturn("MIS_READER");
        when(roles.findById(5L)).thenReturn(Optional.of(role));
        when(scopes.findByAccessGroupIdIn(any())).thenReturn(List.of());
        Map<String, String> image = history.find("AG-MIS").orElseThrow().versions().get(0).values();
        assertThat(image.get("consolidated_view")).isEqualTo("Y");
        assertThat(image.get("scope_type")).isEqualTo("GLOBAL");
        assertThat(image.get("access_mode")).isEqualTo("FULL");
        assertThat(image.get("role")).isEqualTo("MIS_READER");
    }
}
