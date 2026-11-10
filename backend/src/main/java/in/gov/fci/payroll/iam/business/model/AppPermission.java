package in.gov.fci.payroll.iam.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code app_permission} (target DDL S27): one (screen, action) pair. */
@Entity
@Table(name = "app_permission")
public class AppPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "permission_id")
    private Long id;

    @Column(name = "screen_code", nullable = false, length = 20)
    private String screenCode;

    @Column(name = "action_code", nullable = false, length = 40)
    private String actionCode;

    @Column(name = "permission_name", length = 200)
    private String name;

    protected AppPermission() {}

    /** {@code M-01:VIEW}. */
    public String key() {
        return screenCode + ":" + actionCode;
    }

    public Long getId() {
        return id;
    }

    public String getScreenCode() {
        return screenCode;
    }

    public String getActionCode() {
        return actionCode;
    }

    public String getName() {
        return name;
    }
}
