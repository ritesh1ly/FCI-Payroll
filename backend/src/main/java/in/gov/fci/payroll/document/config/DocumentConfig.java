package in.gov.fci.payroll.document.config;

import in.gov.fci.payroll.document.business.MalwareScanner;
import in.gov.fci.payroll.document.business.StorageLayout;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Chooses the virus scanner (SRS-G-30). {@code fci.documents.scanner=none} skips the scan. It is
 * refused in prod, and in uat unless the demo profile is active too (the local deployment runs
 * {@code uat,demo} with fake data) - the same rule StartupGuards applies to logged OTP codes. A
 * real deployment that stores files must scan them, so it refuses to start rather than store them
 * unscanned.
 */
@Configuration(proxyBeanMethods = false)
public class DocumentConfig {

    private static final Logger log = LoggerFactory.getLogger(DocumentConfig.class);

    /** The folder of stored files (fci.documents.storage-key-*), checked once at start-up. */
    @Bean
    StorageLayout storageLayout(DocumentProperties properties) {
        return StorageLayout.of(properties.storageKeyPrefix(), properties.storageKeyLayout());
    }

    @Bean
    MalwareScanner malwareScanner(DocumentProperties properties, Environment environment) {
        return switch (properties.scanner()) {
            case "clamd" ->
                    new ClamdMalwareScanner(
                            properties.clamdHost(),
                            properties.clamdPort(),
                            properties.clamdTimeout());
            case "none" -> {
                List<String> profiles = List.of(environment.getActiveProfiles());
                if (profiles.contains("prod")
                        || (profiles.contains("uat") && !profiles.contains("demo"))) {
                    throw new IllegalStateException(
                            "Unsafe configuration: fci.documents.scanner=none is not allowed in"
                                    + " uat/prod (SRS-G-30)");
                }
                log.warn(
                        "event=DOCUMENT_SCAN_DISABLED note=files-stored-unscanned"
                                + " profile=local-or-demo-only");
                yield content -> MalwareScanner.Verdict.CLEAN;
            }
            default ->
                    throw new IllegalStateException("fci.documents.scanner must be clamd or none");
        };
    }
}
