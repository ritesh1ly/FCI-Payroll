package in.gov.fci.payroll.platform.idempotency;

import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Platform table: idempotency keys of state-changing requests. */
final class JpaTokenRepositories {

    private JpaTokenRepositories() {}

    interface IdempotencyRecords
            extends IdempotencyRepository, Repository<IdempotencyRecord, String> {

        @Override
        @Modifying
        @Query("delete from IdempotencyRecord r where r.expiresAt < :cutoff")
        int purgeExpired(@Param("cutoff") Instant cutoff);
    }
}
