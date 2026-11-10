package in.gov.fci.payroll.platform.persistence;

import in.gov.fci.payroll.platform.security.CurrentUser;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * JPA repositories (nested Spring Data interfaces in the persistence adapter package), auditing
 * (who and when, from the security context and the shared UTC clock), the named-parameter JDBC
 * template with indexable office scopes and scheduled housekeeping.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackages = "in.gov.fci.payroll", considerNestedRepositories = true)
@EnableJpaAuditing(auditorAwareRef = "securityAuditor", dateTimeProviderRef = "utcDateTimeProvider")
public class PersistenceConfig {

    /**
     * {@code created_by}/{@code updated_by}: the user id, or {@code system} for unauthenticated
     * work.
     */
    @Bean
    AuditorAware<String> securityAuditor(CurrentUser currentUser) {
        return () -> Optional.of(currentUser.userId().orElse("system"));
    }

    /**
     * The named-parameter template of every service, with the office guard made indexable (P1);
     * Spring Boot's own then backs off.
     */
    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate(JdbcTemplate jdbc) {
        return new ScopedNamedParameterJdbcTemplate(jdbc);
    }

    /** Microsecond precision, matching {@code TIMESTAMP(6)} on H2 and Oracle. */
    @Bean
    DateTimeProvider utcDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
