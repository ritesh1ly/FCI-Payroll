package in.gov.fci.payroll.document.config;

import in.gov.fci.payroll.document.business.StorageLayout;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Document limits and the virus scanner ({@code fci.documents.*}).
 *
 * <p>SRS-G-30 / KB CF-13: "File-size and file-count limits are settings". They are deployment
 * settings set by FCI IT (technical limits, no pay effect); the defaults below are placeholders
 * until FCI IT confirms them (tracker OQ-B3) and must stay within the NGINX {@code
 * client_max_body_size} of the upload locations (config/nginx).
 *
 * @param maxFileBytes largest accepted file
 * @param maxFilesPerRecord most active files one transaction may carry
 * @param scanner {@code clamd} (ClamAV daemon) or {@code none} (local / demo only; refused in uat
 *     and prod by {@link DocumentConfig})
 * @param clamdHost clamd host
 * @param clamdPort clamd TCP port
 * @param clamdTimeout connect and read timeout of one scan
 * @param storageKeyPrefix the folder every stored file goes under (object-key prefix; see {@link
 *     StorageLayout}), one per deployment, e.g. {@code fci-payroll/uat}
 * @param storageKeyLayout the folders below the prefix, from the tokens of {@link StorageLayout}
 */
@Validated
@ConfigurationProperties("fci.documents")
public record DocumentProperties(
        @DefaultValue("10485760") @Min(1024) @Max(104857600) long maxFileBytes,
        @DefaultValue("10") @Min(1) @Max(100) int maxFilesPerRecord,
        @DefaultValue("clamd") @NotNull String scanner,
        @DefaultValue("127.0.0.1") String clamdHost,
        @DefaultValue("3310") int clamdPort,
        @DefaultValue("30s") Duration clamdTimeout,
        @DefaultValue("fci-payroll") String storageKeyPrefix,
        @DefaultValue("{prefix}/{entity}/{yyyy}/{mm}/{uuid}") String storageKeyLayout) {}
