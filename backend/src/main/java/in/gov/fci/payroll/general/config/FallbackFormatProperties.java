package in.gov.fci.payroll.general.config;

import in.gov.fci.payroll.general.business.FallbackRules;
import in.gov.fci.payroll.general.business.model.FallbackEnablement;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The HRMS export layouts of the two X-07 fallback types ({@code fci.hrms-fallback.formats.*};
 * decision D-2). SRS v0.5 requires "the HRMS export format" but no source describes it, so the
 * layout is configuration supplied with FCI's HRMS export specification; until then the base
 * configuration has none and uploads are refused (HFB-503-FORMAT). The local / demo / test profiles
 * carry a clearly marked placeholder layout so the flow can be exercised.
 *
 * <pre>
 * fci.hrms-fallback.formats.ATTENDANCE_LEAVE.date-pattern: dd-MM-yyyy
 * fci.hrms-fallback.formats.ATTENDANCE_LEAVE.columns.employeeNumber: EMP_NO
 * ...
 * </pre>
 *
 * Logical field names are the constants of {@link FallbackRules}.
 */
@ConfigurationProperties("fci.hrms-fallback")
public record FallbackFormatProperties(Map<FallbackEnablement.Type, FormatConfig> formats) {

    /** One layout: column header per logical field, and the date pattern. */
    public record FormatConfig(Map<String, String> columns, String datePattern) {}

    /** The layout of a type, or an unconfigured one. */
    public FallbackRules.Format format(FallbackEnablement.Type type) {
        FormatConfig config = formats == null ? null : formats.get(type);
        return config == null
                ? new FallbackRules.Format(Map.of(), null)
                : new FallbackRules.Format(config.columns(), config.datePattern());
    }
}
