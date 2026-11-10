package in.gov.fci.payroll.iam.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.type.YesNoConverter;

/**
 * {@code app_screen} (V012): the screen registry as Module, Header and Screen, the way the
 * screen-access screens navigate it. {@code override_protected} screens (administration) can never
 * be granted to one user: access management is reachable only through roles and access groups.
 */
@Entity
@Table(name = "app_screen")
public class AppScreen {

    @Id
    @Column(name = "screen_code", length = 20)
    private String code;

    @Column(name = "module_name", nullable = false, length = 60)
    private String moduleName;

    @Column(name = "header_name", nullable = false, length = 80)
    private String headerName;

    @Column(name = "screen_name", nullable = false, length = 200)
    private String screenName;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "override_protected", nullable = false, length = 1)
    private Boolean overrideProtected;

    protected AppScreen() {}

    public String getCode() {
        return code;
    }

    public String getModuleName() {
        return moduleName;
    }

    public String getHeaderName() {
        return headerName;
    }

    public String getScreenName() {
        return screenName;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public boolean isOverrideProtected() {
        return Boolean.TRUE.equals(overrideProtected);
    }
}
