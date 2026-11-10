/*
 * Generates every secret the backend needs, in the layout the deployment expects (ADR-0013).
 * Run with the JDK only, no build:
 *
 *     java scripts/GenerateSecrets.java --out /etc/fci [--force]
 *
 * Writes <out>/secrets/backend/: a Spring configtree (one file per property, named after it),
 * read by both the online and the batch instance.
 *
 * One-time values an operator must hand over are printed once to stdout: the database password
 * (for the DBA, who sets it on the Oracle schema owner with db/tools/oracle/create_app_user.sql),
 * the metrics password (only its hash is written) and the first-deployment System Administrator
 * password. The latter
 * is also in secrets/backend/fci.api.bootstrap.admin-initial-password, because the backend hashes it at
 * first start (V009, only when no System Administrator exists); delete that file once the
 * administrator has signed in and changed the password.
 *
 * Files are 0400 and directories 0700 on POSIX systems; run as root on the target host, then
 * `chown -R tomcat:tomcat <out>/secrets` (see the runbook). Existing files are never
 * overwritten without --force; rotating a key is a separate procedure (runbook, "Key rotation").
 */

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class GenerateSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "#%+-=@_~";

    private static boolean force;

    public static void main(String[] args) throws Exception {
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[++i]);
                case "--force" -> force = true;
                default -> usage("unknown argument " + args[i]);
            }
        }
        if (out == null) {
            usage("--out is required");
        }
        // One backend WAR, two instances (online, batch): both read the same secrets directory.
        Path backend = directory(out.resolve("secrets").resolve("backend"));

        // Symmetric keys: 32 random bytes, Base64.
        write(backend, "fci.api.secrets.audit-hmac-key", key32());
        write(backend, "fci.api.secrets.data-encryption-key", key32());

        // Database password: the Oracle schema owner (ADR-0019), also valid for the interim H2 server.
        String database = oraclePassword();
        write(backend, "spring.datasource.password", database);

        // Metrics (Prometheus) Basic credentials: hash to the backend, password to the operator.
        String metricsPassword = token(32);
        write(backend, "fci.api.management.password-hash", pbkdf2(metricsPassword));

        // First-deployment System Administrator (V009). Must be changed at first sign-in.
        String admin = password();
        write(backend, "fci.api.bootstrap.admin-initial-password", admin);

        System.out.println("Secrets written to " + out.toAbsolutePath());
        System.out.println();
        System.out.println("HAND OVER NOW (the metrics password is not stored anywhere):");
        System.out.println("  database (Oracle schema owner) password : " + database + "   (to the DBA)");
        System.out.println("  sysadmin@fci.gov.in initial password : " + admin);
        System.out.println("  metrics (Prometheus) password : " + metricsPassword + "   (user: metrics)");
        System.out.println();
        System.out.println("Delete secrets/backend/fci.api.bootstrap.admin-initial-password after the first sign-in.");
    }

    private static void usage(String problem) {
        System.err.println("GenerateSecrets: " + problem);
        System.err.println("usage: java GenerateSecrets.java --out <dir> [--force]");
        System.exit(2);
    }

    private static Path directory(Path dir) throws IOException {
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException windows) {
            // NTFS: restrict with icacls (runbook).
        }
        return dir;
    }

    private static void write(Path dir, String name, String value) throws IOException {
        Path file = dir.resolve(name);
        if (Files.exists(file) && !force) {
            throw new FileAlreadyExistsException(file + " (use --force)");
        }
        Files.writeString(file, value, StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        restrict(file);
    }

    private static void restrict(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
        } catch (UnsupportedOperationException windows) {
            // NTFS: restrict with icacls (runbook).
        }
    }

    private static String key32() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /** URL-safe random token (passwords for machines: metrics). */
    private static String token(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * Human-typeable password that satisfies the backend's policy (ADR-0008): 20 characters, all four
     * classes, no character repeated more than three times in a row.
     */
    private static String password() {
        String all = UPPER + LOWER + DIGITS + SYMBOLS;
        while (true) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                sb.append(all.charAt(RANDOM.nextInt(all.length())));
            }
            String candidate = sb.toString();
            if (hasAny(candidate, UPPER) && hasAny(candidate, LOWER) && hasAny(candidate, DIGITS)
                    && hasAny(candidate, SYMBOLS) && !candidate.matches(".*(.)\\1{3,}.*")) {
                return candidate;
            }
        }
    }

    /**
     * Oracle account password (db/tools/oracle/create_app_user.sql; Autonomous Database rules): 30
     * characters, the maximum; upper and lower case and a digit; starts with a letter and uses only
     * letters, digits, '#' and '_', so it is valid unquoted in SQL and needs no escaping in a JDBC URL,
     * a SQLcl connect string ('@' would split it) or a shell.
     */
    private static String oraclePassword() {
        String letters = UPPER + LOWER;
        String all = letters + DIGITS + "#_";
        while (true) {
            StringBuilder sb = new StringBuilder();
            sb.append(letters.charAt(RANDOM.nextInt(letters.length())));
            for (int i = 1; i < 30; i++) {
                sb.append(all.charAt(RANDOM.nextInt(all.length())));
            }
            String candidate = sb.toString();
            if (hasAny(candidate, UPPER) && hasAny(candidate, LOWER) && hasAny(candidate, DIGITS)) {
                return candidate;
            }
        }
    }

    private static boolean hasAny(String value, String alphabet) {
        return value.chars().anyMatch(c -> alphabet.indexOf(c) >= 0);
    }

    /**
     * Spring Security's {@code {pbkdf2@SpringSecurity_v5_8}} format, which the apps'
     * DelegatingPasswordEncoder verifies: hex(salt || PBKDF2-HMAC-SHA256(password, salt, 310000, 256)).
     */
    private static String pbkdf2(String password) throws Exception {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(password.toCharArray(), salt, 310_000, 256))
                .getEncoded();
        byte[] encoded = new byte[salt.length + hash.length];
        System.arraycopy(salt, 0, encoded, 0, salt.length);
        System.arraycopy(hash, 0, encoded, salt.length, hash.length);
        return "{pbkdf2@SpringSecurity_v5_8}" + HexFormat.of().formatHex(encoded);
    }
}
