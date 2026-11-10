package in.gov.fci.payroll.platform.security;

import in.gov.fci.payroll.platform.config.ApiProperties;
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Symmetric key material, loaded once at start-up (ADR-0013): the audit HMAC key (also keys the OTP
 * hashes) and the data-encryption key, Base64, 32 bytes each, from files in the secrets directory.
 * With {@code fci.api.secrets.ephemeral-keys=true} (local/dev/test only, refused in uat/prod by
 * StartupGuards) missing keys are generated in memory.
 */
@Component
public class KeyMaterial {

    private static final Logger log = LoggerFactory.getLogger(KeyMaterial.class);
    private static final int SYMMETRIC_KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] auditHmacKey;
    private final byte[] dataEncryptionKey;

    public KeyMaterial(ApiProperties properties) {
        ApiProperties.Secrets secrets = properties.secrets();
        this.auditHmacKey =
                symmetricKey(secrets.auditHmacKey(), "audit-hmac-key", secrets.ephemeralKeys());
        this.dataEncryptionKey =
                symmetricKey(
                        secrets.dataEncryptionKey(),
                        "data-encryption-key",
                        secrets.ephemeralKeys());
    }

    public byte[] auditHmacKey() {
        return auditHmacKey.clone();
    }

    public byte[] dataEncryptionKey() {
        return dataEncryptionKey.clone();
    }

    private static byte[] symmetricKey(String base64, String name, boolean ephemeral) {
        if (base64 != null && !base64.isBlank()) {
            byte[] key = Base64.getDecoder().decode(base64.strip());
            if (key.length != SYMMETRIC_KEY_BYTES) {
                throw new IllegalStateException("Key '" + name + "' must be 32 bytes (Base64)");
            }
            return key;
        }
        if (!ephemeral) {
            throw new IllegalStateException("Key '" + name + "' is required");
        }
        byte[] key = new byte[SYMMETRIC_KEY_BYTES];
        RANDOM.nextBytes(key);
        log.warn("event=EPHEMERAL_KEY key={} note=data-unreadable-after-restart", name);
        return key;
    }
}
