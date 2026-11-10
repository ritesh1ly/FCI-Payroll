package in.gov.fci.payroll.iam.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * {@code app_user_password_history} (target DDL S27): the service keeps the configured depth
 * (default 5) and rejects re-use of any hash held here.
 */
@Entity
@Table(name = "app_user_password_history")
public class PasswordHistoryEntry {

    /** Composite key (user, changed at). */
    @Embeddable
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "user_id", nullable = false)
        private Long userId;

        @Column(name = "changed_at", nullable = false)
        private Instant changedAt;

        protected Key() {}

        public Key(Long userId, Instant changedAt) {
            this.userId = userId;
            this.changedAt = changedAt;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && Objects.equals(userId, key.userId)
                    && Objects.equals(changedAt, key.changedAt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, changedAt);
        }
    }

    @EmbeddedId private Key key;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    protected PasswordHistoryEntry() {}

    public PasswordHistoryEntry(Long userId, Instant changedAt, String passwordHash) {
        this.key = new Key(userId, changedAt);
        this.passwordHash = passwordHash;
    }

    public Instant getChangedAt() {
        return key.changedAt;
    }

    public String getPasswordHash() {
        return passwordHash;
    }
}
