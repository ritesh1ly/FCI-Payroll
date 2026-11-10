package in.gov.fci.payroll.audit.persistence;

import in.gov.fci.payroll.audit.business.model.AuditEvent;
import in.gov.fci.payroll.audit.business.model.LegacyAuditRecord;
import in.gov.fci.payroll.audit.business.model.RetentionClass;
import in.gov.fci.payroll.audit.business.model.SecurityEventRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Target DDL S05: the append-only audit trail and the security events (v4.0); §28 the Legacy Audit
 * table (read only: no write method); §2 the retention classes.
 */
public final class AuditRepositories {

    private AuditRepositories() {}

    public interface Audit extends Repository<AuditEvent, Long> {
        AuditEvent save(AuditEvent event);

        /**
         * SRS l.1226, US-03-06: the trail search. Record type, user and field are matched without
         * regard to case and as a part of the stored name; the caller passes each one lower-cased,
         * with its LIKE wildcards escaped ({@code Likes}) and wrapped in {@code %} (null = any).
         */
        @Query(
                "select a from AuditEvent a where a.changedAt >= :from and a.changedAt < :to"
                        + " and (:entity is null or lower(a.entityName) like :entity escape '\\')"
                        + " and (:actor is null or lower(a.changedBy) like :actor escape '\\')"
                        + " and (:field is null or lower(a.fieldName) like :field escape '\\')"
                        + " and (:term is null or lower(a.entityName) like :term escape '\\'"
                        + " or lower(a.entityKey) like :term escape '\\'"
                        + " or lower(a.changedBy) like :term escape '\\'"
                        + " or lower(a.fieldName) like :term escape '\\')"
                        + " and (:byEntity = 0 or a.entityName in :entities)"
                        + " and (:byUser = 0 or a.changedBy in :users)"
                        // An int, not a boolean: a boolean bound beside the LIKE terms is compared
                        // with the rendered literal 0 on H2 ("BOOLEAN and INTEGER not comparable").
                        + " and (:scoped = 0 or a.locationId in :locations)"
                        + " order by a.changedAt desc, a.id desc")
        Page<AuditEvent> search(
                @Param("from") Instant from,
                @Param("to") Instant to,
                @Param("entity") String entity,
                @Param("actor") String actor,
                @Param("field") String field,
                @Param("term") String term,
                @Param("byEntity") int byEntity,
                @Param("entities") Collection<String> entities,
                @Param("byUser") int byUser,
                @Param("users") Collection<String> users,
                @Param("scoped") int scoped,
                @Param("locations") Collection<Long> locations,
                Pageable pageable);

        /**
         * X-04 tile "Audit Rows - This Cycle": rows in a window within the same scope as the search
         * (the window bounds the partitions read).
         */
        @Query(
                "select count(a) from AuditEvent a where a.changedAt >= :from and a.changedAt < :to"
                        + " and (:scoped = 0 or a.locationId in :locations)")
        long countInWindow(
                @Param("from") Instant from,
                @Param("to") Instant to,
                @Param("scoped") int scoped,
                @Param("locations") Collection<Long> locations);

        /** One row, for the X-04 order link. */
        Optional<AuditEvent> findById(Long id);

        /**
         * SRS-20-29: one record's native rows, oldest first; the id breaks ties so a paged read
         * (screen or export) never skips or repeats a row.
         */
        Page<AuditEvent> findByEntityNameAndEntityKeyInOrderByChangedAtAscIdAsc(
                String entityName, Collection<String> entityKeys, Pageable pageable);

        /** SRS-15-29: the latest native row of a record (who changed it last, and when). */
        Optional<AuditEvent> findFirstByEntityNameAndEntityKeyOrderByChangedAtDescIdDesc(
                String entityName, String entityKey);

        /** SRS-20-3: every native row of a record (reconstruction reads the whole history). */
        List<AuditEvent> findByEntityNameAndEntityKeyIn(
                String entityName, Collection<String> entityKeys);
    }

    public interface SecurityEvents extends Repository<SecurityEventRecord, Long> {
        SecurityEventRecord save(SecurityEventRecord event);

        /** OD-130: the offences of a user since the last reinstatement. */
        long countByActingUserAndEventTypeAndOccurredAtGreaterThan(
                String actingUser, String eventType, Instant after);

        /** OD-130: this session's offence is already counted. */
        boolean existsByActingUserAndEventTypeAndTargetEntity(
                String actingUser, String eventType, String targetEntity);

        /** OD-130: the latest suspension or reset of an account. */
        Optional<SecurityEventRecord>
                findFirstByEventTypeAndTargetEntityAndTargetKeyOrderByOccurredAtDesc(
                        String eventType, String targetEntity, String targetKey);

        /** OD-130: the offences behind a suspension, newest first. */
        List<SecurityEventRecord>
                findByActingUserAndEventTypeAndOccurredAtGreaterThanOrderByOccurredAtDesc(
                        String actingUser, String eventType, Instant after, Pageable pageable);

        /** OD-130: a user's offence record (A-01), newest first. */
        Page<SecurityEventRecord> findByActingUserAndEventTypeInOrderByOccurredAtDesc(
                String actingUser, Collection<String> eventTypes, Pageable pageable);

        List<SecurityEventRecord> findByEventTypeAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                String eventType, Instant since);
    }

    /** SRS-20-21: the Legacy Audit table is read only - this repository has no write method. */
    public interface LegacyAudit extends Repository<LegacyAuditRecord, Long> {

        /** X-04 tile: the rows held in the Legacy Audit table (Headquarters reads the count). */
        long count();

        /**
         * SRS-20-23: a record's legacy rows, oldest first (the id breaks ties). {@code national}
         * false leaves out rows without a resolved member (Headquarters only, plan decision D-3).
         */
        @Query(
                "select l from LegacyAuditRecord l where l.entityName = :entity"
                        + " and l.entityKey = :key"
                        + " and (:national = true or l.employeeId is not null)"
                        + " order by l.changedAt asc, l.id asc")
        Page<LegacyAuditRecord> forRecord(
                @Param("entity") String entity,
                @Param("key") String key,
                @Param("national") boolean national,
                Pageable pageable);
    }

    public interface RetentionClasses extends Repository<RetentionClass, Long> {
        List<RetentionClass> findAllByOrderByCodeAsc();

        Optional<RetentionClass> findByCode(String code);

        RetentionClass save(RetentionClass retentionClass);
    }
}
