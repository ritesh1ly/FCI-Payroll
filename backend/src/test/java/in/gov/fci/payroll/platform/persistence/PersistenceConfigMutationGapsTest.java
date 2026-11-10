package in.gov.fci.payroll.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** R-AUD created_by / updated_by and the audit timestamps: beans called directly. */
class PersistenceConfigMutationGapsTest {

    private final PersistenceConfig config = new PersistenceConfig();

    @Test
    void theAuditorIsTheSignedInUserOrSystem() {
        CurrentUser currentUser = mock(CurrentUser.class);
        AuditorAware<String> auditor = config.securityAuditor(currentUser);
        assertThat(auditor).isNotNull();

        when(currentUser.userId()).thenReturn(Optional.of("104882"));
        assertThat(auditor.getCurrentAuditor()).contains("104882");

        when(currentUser.userId()).thenReturn(Optional.empty());
        assertThat(auditor.getCurrentAuditor()).contains("system");
    }

    @Test
    void theNamedTemplateIsTheScopedOne() {
        NamedParameterJdbcTemplate template = config.namedParameterJdbcTemplate(new JdbcTemplate());
        assertThat(template).isInstanceOf(ScopedNamedParameterJdbcTemplate.class);
    }

    @Test
    void theAuditTimeIsTheClockInstantTruncatedToMicroseconds() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-05T04:30:00.123456789Z"), ZoneOffset.UTC);
        DateTimeProvider provider = config.utcDateTimeProvider(clock);
        assertThat(provider).isNotNull();
        assertThat(provider.getNow()).contains(Instant.parse("2026-11-05T04:30:00.123456Z"));
    }
}
