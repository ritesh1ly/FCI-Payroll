package in.gov.fci.payroll.allowance.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The offices whose claims a user sees on I-03 / I-16: every office at Headquarters, the user's
 * cycle unit and the offices below it where the screen's right holds there, else the user's own
 * office and those below it; a session without an office sees none.
 */
class ClaimScopeTest {

    private final OrgDirectory org = mock(OrgDirectory.class);
    private final Authorization policy = mock(Authorization.class);
    private final ClaimScope scope = new ClaimScope(org, policy);

    private static AccessContext at(Long location) {
        return new AccessContext(
                3L, "user@x", "User", "s", "P", location, null, List.of(), Map.of(), Set.of(),
                Set.of(), Set.of());
    }

    private static OrgLocation unit(long id) {
        OrgLocation l = BeanUtils.instantiateClass(OrgLocation.class);
        ReflectionTestUtils.setField(l, "id", id);
        return l;
    }

    @Test
    void aSessionWithoutAnOfficeSeesNoClaims() {
        assertThatThrownBy(() -> scope.offices(at(null), "I-03", "VIEW"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED));
        verify(org, never()).subtree(anyLong());
    }

    @Test
    void headquartersSeesEveryOffice() {
        AccessContext hq = at(1L);
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        assertThat(scope.offices(hq, "I-03", "VIEW")).isEmpty();
        verify(policy).requireAt(hq, "I-03", "VIEW", 1L);
    }

    @Test
    void aUserWithTheRightAtTheCycleUnitSeesTheUnitAndTheOfficesBelowIt() {
        AccessContext depot = at(500L);
        when(org.cycleUnitOf(500L)).thenReturn(Optional.of(unit(300L)));
        when(policy.canAt(depot, "I-16", "VIEW", 300L)).thenReturn(true);
        when(org.subtree(300L)).thenReturn(Set.of(300L, 500L, 501L));
        assertThat(scope.offices(depot, "I-16", "VIEW")).contains(Set.of(300L, 500L, 501L));
        verify(policy, never()).requireAt(any(), anyString(), anyString(), anyLong());
    }

    @Test
    void aDealingHandWithoutTheRightAtTheUnitSeesOnlyTheOwnOfficeAndThoseBelowIt() {
        AccessContext depot = at(500L);
        when(org.cycleUnitOf(500L)).thenReturn(Optional.of(unit(300L)));
        when(org.subtree(500L)).thenReturn(Set.of(500L));
        assertThat(scope.offices(depot, "I-16", "VIEW")).contains(Set.of(500L));
        verify(policy).requireAt(depot, "I-16", "VIEW", 500L);
        // An office outside any cycle unit is its own scope too.
        AccessContext loose = at(700L);
        when(org.cycleUnitOf(700L)).thenReturn(Optional.empty());
        when(org.subtree(700L)).thenReturn(Set.of(700L));
        assertThat(scope.offices(loose, "I-16", "VIEW")).contains(Set.of(700L));
        verify(policy, never()).canAt(loose, "I-16", "VIEW", 700L);
    }
}
