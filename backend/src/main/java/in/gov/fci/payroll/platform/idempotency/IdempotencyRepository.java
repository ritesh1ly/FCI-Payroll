package in.gov.fci.payroll.platform.idempotency;

import java.time.Instant;
import java.util.Optional;

/** Persistence port for idempotency records. */
public interface IdempotencyRepository {

    Optional<IdempotencyRecord> findById(String id);

    /** Inserts and flushes, so a concurrent duplicate fails immediately on the primary key. */
    IdempotencyRecord saveAndFlush(IdempotencyRecord record);

    IdempotencyRecord save(IdempotencyRecord record);

    void deleteById(String id);

    int purgeExpired(Instant cutoff);
}
