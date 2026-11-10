package in.gov.fci.payroll.iam.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.business.PasswordPolicy;
import in.gov.fci.payroll.platform.config.ApiProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Branch gaps of the IAM configuration (ADR-0008 password hashing; SRS-SEC-13 password rules and
 * session store): the encoder choice, the common-password list parser and the string-only session
 * attribute converter.
 */
class IamConfigBranchGapsTest {

    @Test
    void SRS_SEC_13_theEncoderIsBcryptOnlyWhenNamedElseArgon2() {
        PasswordEncoderConfig config = new PasswordEncoderConfig();
        PasswordEncoder bcrypt = config.passwordEncoder("bcrypt");
        assertThat(bcrypt.encode("Zebra#Quartz7Lamp")).startsWith("{bcrypt}");
        PasswordEncoder argon = config.passwordEncoder("anything-else");
        String hash = argon.encode("Zebra#Quartz7Lamp");
        assertThat(hash).startsWith("{argon2}");
        assertThat(bcrypt.matches("Zebra#Quartz7Lamp", hash)).as("either id verifies").isTrue();
    }

    @Test
    void SRS_SEC_13_theCommonPasswordListSkipsBlankLinesAndComments() {
        ApiProperties properties = mock(ApiProperties.class);
        when(properties.password())
                .thenReturn(
                        new ApiProperties.Password(5, Duration.ofDays(90), "classpath:list.txt"));
        ResourceLoader loader = mock(ResourceLoader.class);
        String list = "# deny-list\n\n   \n  Zebra#Quartz7Lamp  \n#Hidden#Quartz7Lamp\n";
        when(loader.getResource("classpath:list.txt"))
                .thenReturn(new ByteArrayResource(list.getBytes(StandardCharsets.UTF_8)));

        PasswordPolicy policy = new DomainConfig().passwordPolicy(properties, loader);

        assertThat(policy.check("Zebra#Quartz7Lamp", "u", "1", null))
                .contains(PasswordPolicy.Violation.COMMON_PASSWORD);
        assertThat(policy.check("#Hidden#Quartz7Lamp", "u", "1", null))
                .as("a comment line is not a password")
                .doesNotContain(PasswordPolicy.Violation.COMMON_PASSWORD);
    }

    @Test
    void SRS_SEC_13_sessionAttributesAreStringsOnly() {
        SessionConfig.StringOnlyConverter converter = new SessionConfig.StringOnlyConverter();
        TypeDescriptor bytes = TypeDescriptor.valueOf(byte[].class);
        TypeDescriptor object = TypeDescriptor.valueOf(Object.class);
        assertThat(converter.getConvertibleTypes()).hasSize(2);
        assertThat(converter.convert(null, object, bytes)).isNull();
        assertThat((byte[]) converter.convert("42", object, bytes))
                .isEqualTo("42".getBytes(StandardCharsets.UTF_8));
        assertThat(converter.convert("42".getBytes(StandardCharsets.UTF_8), bytes, object))
                .isEqualTo("42");
        assertThatThrownBy(() -> converter.convert(42L, object, bytes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("java.lang.Long");
    }
}
