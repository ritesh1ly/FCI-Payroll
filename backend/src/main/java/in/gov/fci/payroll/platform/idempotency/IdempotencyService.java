package in.gov.fci.payroll.platform.idempotency;

import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency-Key handling for state-changing POSTs (ADR-0011). Records are keyed by {@code
 * sha256(userId:key)}, so keys never collide between users, and compared by a hash of the request,
 * so a key reused for a different request is rejected.
 */
@Service
public class IdempotencyService {

    /** What to do with an incoming request. */
    public sealed interface Decision {
        /** First time: process, then call {@link #complete} or {@link #abandon}. */
        record Proceed(String recordId) implements Decision {}

        /** Seen and completed: replay the stored response. */
        record Replay(int status, String contentType, String body) implements Decision {}

        /** Seen and still being processed. */
        record InProgress() implements Decision {}

        /** Same key, different request. */
        record Mismatch() implements Decision {}
    }

    private final IdempotencyRepository repository;
    private final Clock clock;
    private final ApiProperties properties;

    public IdempotencyService(
            IdempotencyRepository repository, Clock clock, ApiProperties properties) {
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision begin(String userId, String key, String requestHash) {
        String id = SecureTokens.sha256Hex(userId + ":" + key);
        Optional<IdempotencyRecord> existing = repository.findById(id);
        if (existing.isPresent()) {
            return decide(existing.get(), requestHash);
        }
        Instant now = clock.instant();
        try {
            repository.saveAndFlush(
                    IdempotencyRecord.start(
                            id,
                            userId,
                            requestHash,
                            now,
                            now.plus(properties.idempotency().ttl())));
            return new Decision.Proceed(id);
        } catch (DataIntegrityViolationException concurrentInsert) {
            return new Decision.InProgress();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String recordId, int status, String contentType, String body) {
        repository
                .findById(recordId)
                .ifPresent(
                        record -> {
                            record.complete(status, contentType, body);
                            repository.save(record);
                        });
    }

    /** Forgets a record whose request failed with a server error, so the client may retry. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandon(String recordId) {
        repository.deleteById(recordId);
    }

    private static Decision decide(IdempotencyRecord record, String requestHash) {
        if (!SecureTokens.constantTimeEquals(record.getRequestHash(), requestHash)) {
            return new Decision.Mismatch();
        }
        if (record.getStatus() == IdempotencyRecord.Status.IN_PROGRESS) {
            return new Decision.InProgress();
        }
        return new Decision.Replay(
                record.getResponseStatus(), record.getContentType(), record.getResponseBody());
    }
}
