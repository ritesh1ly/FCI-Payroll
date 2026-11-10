package in.gov.fci.payroll.iam.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.type.YesNoConverter;

/**
 * {@code login_persona} (FCILekha extension): one "Sign in as" button of the login page. A UI
 * persona, not a role; it admits roles through {@link LoginPersonaRole}.
 */
@Entity
@Table(name = "login_persona")
public class LoginPersona {

    @Id
    @Column(name = "persona_code", length = 20)
    private String code;

    @Column(name = "label_key", nullable = false, length = 80)
    private String labelKey;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "icon_name", nullable = false, length = 40)
    private String iconName;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "location_required", nullable = false, length = 1)
    private Boolean locationRequired;

    @Column(name = "landing_screen_code", nullable = false, length = 20)
    private String landingScreenCode;

    @Column(name = "user_category", nullable = false, length = 15)
    private String userCategory;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "active_flag", nullable = false, length = 1)
    private Boolean active;

    protected LoginPersona() {}

    public String getCode() {
        return code;
    }

    public String getLabelKey() {
        return labelKey;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public String getIconName() {
        return iconName;
    }

    public boolean isLocationRequired() {
        return Boolean.TRUE.equals(locationRequired);
    }

    public String getLandingScreenCode() {
        return landingScreenCode;
    }

    public String getUserCategory() {
        return userCategory;
    }

    public boolean isActive() {
        return Boolean.TRUE.equals(active);
    }
}
