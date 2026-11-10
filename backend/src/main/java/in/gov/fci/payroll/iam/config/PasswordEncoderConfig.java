package in.gov.fci.payroll.iam.config;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing (ADR-0008): Argon2id with the OWASP minimum parameters (19 MiB, t=2, p=1) by
 * default, BCrypt cost 12 as configurable fallback. Hashes carry a {@code {id}} prefix so either
 * can always be verified and outdated ones are upgraded at the next login.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfig {

    @Bean
    PasswordEncoder passwordEncoder(@Value("${fci.api.password.encoder:argon2}") String encoderId) {
        Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
        String id = "bcrypt".equals(encoderId) ? "bcrypt" : "argon2";
        DelegatingPasswordEncoder delegating =
                new DelegatingPasswordEncoder(id, Map.of("argon2", argon2, "bcrypt", bcrypt));
        delegating.setDefaultPasswordEncoderForMatches(argon2);
        return delegating;
    }
}
