package in.gov.fci.payroll.platform.logging;

import in.gov.fci.payroll.platform.logging.MaskingProperties.FieldRule;
import in.gov.fci.payroll.platform.logging.MaskingProperties.PatternRule;
import in.gov.fci.payroll.platform.logging.MaskingProperties.Strategy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Masks personal data in what a log shows (R-PII), driven by {@link MaskingProperties}: field rules
 * for JSON keys and query parameters, pattern rules for free text. Pure and thread-safe; the rules
 * are compiled once, and a rule that does not compile stops the start (a masking policy that
 * silently does nothing is worse than none).
 *
 * <p>A JSON body is walked; a value whose key matches a field rule is replaced by the strategy's
 * mask whatever its type (a mobile number sent as a JSON number is masked too); every other text
 * value is run through the pattern rules. A body that is not JSON is run through the pattern rules
 * as text. The input is cut to {@link #LIMIT} characters before any rule runs, so a hostile body
 * cannot make a rule slow.
 */
@Component
public final class MaskingPolicy {

    /** The most characters looked at; the rest is dropped before masking. */
    public static final int LIMIT = 65_536;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int RULES_MAX = 200;

    private record Field(Pattern name, Strategy strategy, int keep) {}

    private record Free(Pattern regex, Strategy strategy, int keep) {}

    private final List<Field> fields = new ArrayList<>();
    private final List<Free> patterns = new ArrayList<>();

    public MaskingPolicy(MaskingProperties properties) {
        if (properties.fieldRules().size() + properties.patternRules().size() > RULES_MAX) {
            throw new IllegalStateException("more than " + RULES_MAX + " masking rules");
        }
        for (FieldRule r : properties.fieldRules()) {
            fields.add(
                    new Field(
                            compile(r.name(), "name", Pattern.CASE_INSENSITIVE),
                            strategy(r.strategy()),
                            keep(r.keep())));
        }
        for (PatternRule r : properties.patternRules()) {
            patterns.add(
                    new Free(
                            compile(r.regex(), "regex", 0),
                            strategy(r.strategy()),
                            keep(r.keep())));
        }
    }

    private static Pattern compile(String expression, String what, int flags) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalStateException("a masking rule has no " + what);
        }
        try {
            return Pattern.compile(expression, flags);
        } catch (PatternSyntaxException ex) {
            throw new IllegalStateException(
                    "masking rule " + what + " '" + expression + "' does not compile", ex);
        }
    }

    private static Strategy strategy(Strategy s) {
        if (s == null) {
            throw new IllegalStateException("a masking rule has no strategy");
        }
        return s;
    }

    private static int keep(Integer keep) {
        return keep == null || keep < 0 ? 0 : Math.min(keep, 32);
    }

    /** Whether any rule is configured; without one a body must not be shown. */
    public boolean configured() {
        return !fields.isEmpty() || !patterns.isEmpty();
    }

    /** A request or response body, masked: JSON by key and by text, anything else by text. */
    public String body(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String text = raw.length() > LIMIT ? raw.substring(0, LIMIT) : raw;
        try {
            JsonNode root = MAPPER.readTree(text);
            if (root != null && (root.isObject() || root.isArray())) {
                return MAPPER.writeValueAsString(walk(root));
            }
        } catch (JacksonException notJson) {
            // truncated or not JSON: mask it as text
        }
        return free(text);
    }

    /** A raw query string ({@code a=1&b=2}), each value masked by its parameter name. */
    public String query(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return rawQuery;
        }
        String text = rawQuery.length() > LIMIT ? rawQuery.substring(0, LIMIT) : rawQuery;
        StringBuilder out = new StringBuilder();
        for (String pair : text.split("&")) {
            if (out.length() > 0) {
                out.append('&');
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.append(free(pair));
                continue;
            }
            String name = pair.substring(0, eq);
            out.append(name).append('=').append(value(name, pair.substring(eq + 1)));
        }
        return out.toString();
    }

    private JsonNode walk(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            for (String name : node.propertyNames()) {
                JsonNode child = node.get(name);
                Field rule = ruleFor(name);
                if (rule != null && child.isValueNode() && !child.isNull()) {
                    out.put(name, apply(rule.strategy(), rule.keep(), child.asString()));
                } else {
                    out.set(name, walk(child));
                }
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            node.forEach(child -> out.add(walk(child)));
            return out;
        }
        if (node.isString()) {
            return MAPPER.getNodeFactory().stringNode(free(node.asString()));
        }
        return node;
    }

    private Field ruleFor(String name) {
        for (Field f : fields) {
            if (f.name().matcher(name).matches()) {
                return f;
            }
        }
        return null;
    }

    private String value(String name, String value) {
        Field rule = ruleFor(name);
        return rule != null ? apply(rule.strategy(), rule.keep(), value) : free(value);
    }

    private String free(String text) {
        String out = text;
        for (Free p : patterns) {
            Matcher m = p.regex().matcher(out);
            out =
                    m.replaceAll(
                            r ->
                                    Matcher.quoteReplacement(
                                            apply(p.strategy(), p.keep(), r.group())));
        }
        return out;
    }

    /** Hides {@code value} with a strategy. */
    static String apply(Strategy strategy, int keep, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return switch (strategy) {
            case FULL -> "***";
            case KEEP_LAST -> {
                int k = Math.min(keep, value.length() / 2);
                yield "*".repeat(value.length() - k) + value.substring(value.length() - k);
            }
            case KEEP_FIRST -> {
                int k = Math.min(keep, value.length() / 2);
                yield value.substring(0, k) + "*".repeat(value.length() - k);
            }
            case EMAIL -> email(value);
            case INITIALS -> initials(value);
            case HASH -> "#" + hash(value);
        };
    }

    private static String email(String value) {
        int at = value.indexOf('@');
        return at <= 0 ? "***" : value.charAt(0) + "***" + value.substring(at);
    }

    private static String initials(String value) {
        StringBuilder out = new StringBuilder();
        for (String word : value.trim().split("\\s+")) {
            if (!word.isEmpty()) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(word.charAt(0)).append("***");
            }
        }
        return out.length() == 0 ? "***" : out.toString();
    }

    private static String hash(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
