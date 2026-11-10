package in.gov.fci.payroll.iam.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;

/** {@code app_user_preference} (FCILekha extension): the UI language of a user. */
@Entity
@Table(name = "app_user_preference")
public class UserPreference {

    public static final Set<String> LOCALES = Set.of("en", "hi");

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "preferred_locale", nullable = false, length = 10)
    private String preferredLocale;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserPreference() {}

    public UserPreference(Long userId, String locale, Instant now) {
        this.userId = userId;
        setLocale(locale, now);
    }

    public final void setLocale(String locale, Instant now) {
        if (!LOCALES.contains(locale)) {
            throw new IllegalArgumentException("Unsupported locale");
        }
        this.preferredLocale = locale;
        this.updatedAt = now;
    }

    public Long getUserId() {
        return userId;
    }

    public String getPreferredLocale() {
        return preferredLocale;
    }
}
