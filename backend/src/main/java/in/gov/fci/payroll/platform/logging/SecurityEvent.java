package in.gov.fci.payroll.platform.logging;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A security-relevant event (login, lockout, role change, permission denied …) for the {@code
 * SECURITY_AUDIT} logger. Values are identifiers, never secrets or raw personal data.
 *
 * @param type upper-snake event type, e.g. {@code LOGIN_FAILURE}
 * @param outcome success or failure
 * @param actorId id of the acting user, or {@code anonymous}/{@code system}
 * @param targetType kind of object acted on, e.g. {@code USER}; may be null
 * @param targetId id of the object acted on; may be null
 * @param reason machine-readable reason code, e.g. {@code BAD_CREDENTIALS}; may be null
 * @param attributes additional non-sensitive attributes
 */
public record SecurityEvent(
        String type,
        Outcome outcome,
        String actorId,
        String targetType,
        String targetId,
        String reason,
        Map<String, String> attributes) {

    private static final Pattern TYPE = Pattern.compile("^[A-Z][A-Z_]{2,63}$");

    public enum Outcome {
        SUCCESS,
        FAILURE
    }

    public SecurityEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(outcome, "outcome");
        if (!TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("Security event type must be UPPER_SNAKE_CASE");
        }
        actorId = actorId == null ? "anonymous" : actorId;
        attributes =
                attributes == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static SecurityEvent success(String type, String actorId) {
        return new SecurityEvent(type, Outcome.SUCCESS, actorId, null, null, null, Map.of());
    }

    public static SecurityEvent failure(String type, String actorId, String reason) {
        return new SecurityEvent(type, Outcome.FAILURE, actorId, null, null, reason, Map.of());
    }

    public SecurityEvent withTarget(String newTargetType, String newTargetId) {
        return new SecurityEvent(
                type, outcome, actorId, newTargetType, newTargetId, reason, attributes);
    }

    public SecurityEvent withAttribute(String key, String value) {
        Map<String, String> copy = new LinkedHashMap<>(attributes);
        copy.put(key, value);
        return new SecurityEvent(type, outcome, actorId, targetType, targetId, reason, copy);
    }
}
