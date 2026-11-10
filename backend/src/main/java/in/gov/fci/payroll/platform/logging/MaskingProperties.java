package in.gov.fci.payroll.platform.logging;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The PII masking policy for structured data in logs (R-PII), shared by every module that logs a
 * request or a response, as configuration and not as code: what is masked, and how, is decided in
 * {@code application.yaml} (or the environment's config folder), so a new sensitive field is a
 * one-line change that needs no release.
 *
 * <pre>
 * fci.logging.masking:
 *   fields:                         # a JSON key or a query parameter name; the FIRST rule that matches wins
 *     - {name: ".*(password|token).*", strategy: FULL}
 *     - {name: "pan", strategy: KEEP_LAST, keep: 4}
 *   patterns:                       # free text, anywhere in a body or value; applied in order
 *     - {regex: "[A-Z]{5}[0-9]{4}[A-Z]", strategy: KEEP_LAST, keep: 4}
 * </pre>
 *
 * {@code name} is a regular expression matched against the WHOLE key, ignoring case. {@code
 * strategy}: FULL (all replaced), KEEP_LAST / KEEP_FIRST ({@code keep} characters stay), EMAIL
 * (first letter and the domain stay), INITIALS (the first letter of each word stays), HASH (a short
 * fingerprint, so equal values can be correlated without being shown). With no rule at all the log
 * withholds bodies: it never writes an unmasked body because nobody configured a policy.
 */
@ConfigurationProperties("fci.logging.masking")
public record MaskingProperties(List<FieldRule> fields, List<PatternRule> patterns) {

    /** How a value is hidden. */
    public enum Strategy {
        FULL,
        KEEP_LAST,
        KEEP_FIRST,
        EMAIL,
        INITIALS,
        HASH
    }

    /**
     * One field rule: a key-name expression and a strategy ({@code keep} for KEEP_LAST /
     * KEEP_FIRST).
     */
    public record FieldRule(String name, Strategy strategy, Integer keep) {}

    /**
     * One free-text rule: an expression and a strategy ({@code keep} for KEEP_LAST / KEEP_FIRST).
     */
    public record PatternRule(String regex, Strategy strategy, Integer keep) {}

    /** The field rules, never null. */
    public List<FieldRule> fieldRules() {
        return fields == null ? List.of() : fields;
    }

    /** The free-text rules, never null. */
    public List<PatternRule> patternRules() {
        return patterns == null ? List.of() : patterns;
    }
}
