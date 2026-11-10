package in.gov.fci.payroll.iam.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** SRS-SEC-14: a session is looked up by its token's hash; no token finds no session. */
class IdentityRepositoriesBranchGapsTest {

    @Test
    void SRS_SEC_14_aSessionIsFoundByTheTokenHashAndNoTokenFindsNone() {
        IdentityRepositories.Sessions sessions = mock(IdentityRepositories.Sessions.class);
        when(sessions.findByToken(any())).thenCallRealMethod();
        AppSession session = mock(AppSession.class);
        when(sessions.findByTokenHash(SecureTokens.sha256Hex("tok")))
                .thenReturn(Optional.of(session));

        assertThat(sessions.findByToken("tok")).containsSame(session);
        assertThat(sessions.findByToken(null)).isEmpty();
        verify(sessions, never()).findByTokenHash(SecureTokens.sha256Hex(""));
    }
}
