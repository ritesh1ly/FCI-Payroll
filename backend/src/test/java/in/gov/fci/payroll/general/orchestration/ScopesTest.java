package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.security.AccessContext;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A worklist covers the session's offices; without any it covers nothing, never everything. */
class ScopesTest {

    @Test
    void anEmptyOrMissingScopeMatchesNoOffice() {
        AccessContext context = mock(AccessContext.class);
        when(context.dataScope()).thenReturn(Set.of());
        assertThat(Scopes.of(context)).containsExactly(-1L);
        when(context.dataScope()).thenReturn(null);
        assertThat(Scopes.of(context)).containsExactly(-1L);
        when(context.dataScope()).thenReturn(Set.of(40L, 41L));
        assertThat(Scopes.of(context)).containsExactlyInAnyOrder(40L, 41L);
    }
}
