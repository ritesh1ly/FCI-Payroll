package in.gov.fci.payroll.integration.business;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bearer token Payroll holds for HRMS (SRS-INT-4): taken from the answer to {@code POST token}
 * ({@code data.token}, as the collection's own test script reads it) and kept until shortly before
 * it expires. Pure and thread-safe; the clock is passed in. The token is a secret: it is never
 * logged and {@link #toString()} does not show it.
 */
public final class HrmsBearer {

    /** A parsed token answer. */
    public record Issued(String token, Duration lifetime) {}

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private String token;
    private Instant renewAt = Instant.EPOCH;

    /**
     * Reads {@code data.token} and {@code data.expires_in} (seconds) from a token answer. A token
     * without a stated lifetime is usable for this one call only (lifetime zero), never kept on a
     * guess.
     */
    public static Optional<Issued> read(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode data = MAPPER.readTree(body).path("data");
            JsonNode t = data.path("token");
            if (!t.isString() || t.asString().isBlank()) {
                return Optional.empty();
            }
            JsonNode seconds = data.path("expires_in");
            long life = seconds.isNumber() && seconds.asLong() > 0 ? seconds.asLong() : 0;
            return Optional.of(new Issued(t.asString().strip(), Duration.ofSeconds(life)));
        } catch (JacksonException notJson) {
            return Optional.empty();
        }
    }

    /** The held token, if it is still good at {@code now}. */
    public synchronized Optional<String> current(Instant now) {
        return token != null && now.isBefore(renewAt) ? Optional.of(token) : Optional.empty();
    }

    /** Keeps a token until {@code skew} before its lifetime ends. */
    public synchronized void keep(Issued issued, Instant now, Duration skew) {
        Duration usable = issued.lifetime().minus(skew);
        this.token = issued.token();
        this.renewAt = usable.isNegative() || usable.isZero() ? now : now.plus(usable);
    }

    /**
     * Forgets the token HRMS refused, but only if it is still the held one: when several callers
     * meet the same expired token, the first one fetches a new token and the others must keep it,
     * not discard it.
     *
     * @return whether the refused token was the held one (this caller must fetch a new token)
     */
    public synchronized boolean invalidate(String refused) {
        if (token != null && token.equals(refused)) {
            token = null;
            renewAt = Instant.EPOCH;
            return true;
        }
        return false;
    }

    /** Forgets the token (HRMS refused it; the next call logs in again). */
    public synchronized void invalidate() {
        token = null;
        renewAt = Instant.EPOCH;
    }

    @Override
    public synchronized String toString() {
        return "HrmsBearer[held=" + (token != null) + "]";
    }
}
