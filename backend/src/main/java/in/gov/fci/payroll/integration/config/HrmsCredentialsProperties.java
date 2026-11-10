package in.gov.fci.payroll.integration.config;

import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * The login Payroll presents to HRMS to get its bearer token ({@code POST token} with {@code
 * {"username","password"}}, as in FCI's Postman collection; SRS-INT-4, R-INT). Environment
 * settings, never code: they differ per environment (local, dev, uat, prod) and the password is a
 * secret kept in the environment or in a file, never logged and never shown (R-PII).
 *
 * <pre>
 * fci.integration.hrms.client.credentials.username: payroll-system
 * fci.integration.hrms.client.credentials.password / password-file     (secret)
 * fci.integration.hrms.client.credentials.token-skew-seconds: 60      renew this long before expiry
 * fci.integration.hrms.client.credentials.token-renewals: 2            renew-and-repeat at most this often per call (0 - 5)
 * </pre>
 *
 * With neither a login nor a static {@code fci.integration.hrms.client.bearer-token} set, an API
 * that needs a bearer is not called (refused as not configured).
 */
@ConfigurationProperties("fci.integration.hrms.client.credentials")
public record HrmsCredentialsProperties(
        String username,
        String password,
        String passwordFile,
        Integer tokenSkewSeconds,
        Integer tokenRenewals) {

    @ConstructorBinding
    public HrmsCredentialsProperties {}

    /** Without a stated number of renewals. */
    public HrmsCredentialsProperties(
            String username, String password, String passwordFile, Integer tokenSkewSeconds) {
        this(username, password, passwordFile, tokenSkewSeconds, null);
    }

    /**
     * How often one call may renew the token and repeat when HRMS refuses it (default 2, at most
     * 5).
     */
    public int renewals() {
        return tokenRenewals == null || tokenRenewals < 0 ? 2 : Math.min(tokenRenewals, 5);
    }

    /** A user name and password, both present. */
    public record Login(String username, String password) {}

    /** The login, when both the user name and the password are set. */
    public Optional<Login> login() {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        Optional<String> secret = HrmsClientProperties.secret(password, passwordFile);
        return secret.map(p -> new Login(username.strip(), p));
    }

    /** How long before the token's expiry a new one is fetched. */
    public Duration skew() {
        return Duration.ofSeconds(
                tokenSkewSeconds == null || tokenSkewSeconds < 0 ? 60 : tokenSkewSeconds);
    }

    @Override
    public String toString() {
        return "HrmsCredentialsProperties[username=" + username + ", password=hidden]";
    }
}
