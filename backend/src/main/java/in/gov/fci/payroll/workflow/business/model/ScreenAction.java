package in.gov.fci.payroll.workflow.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code screen_action_inventory} (target DDL S05): the governed buttons of a screen. {@code
 * allowed_role_codes} is display only; {@code app_role_permission} is authoritative.
 */
@Entity
@Table(name = "screen_action_inventory")
public class ScreenAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inventory_id")
    private Long id;

    @Column(name = "screen_code", nullable = false, length = 20)
    private String screenCode;

    @Column(name = "screen_name", length = 200)
    private String screenName;

    @Column(name = "action_code", nullable = false, length = 40)
    private String actionCode;

    @Column(name = "action_label", length = 200)
    private String actionLabel;

    @Column(name = "allowed_role_codes", length = 500)
    private String allowedRoleCodes;

    @Column(name = "disabled_reason_msg", length = 30)
    private String disabledReasonMsg;

    protected ScreenAction() {}

    public String getScreenCode() {
        return screenCode;
    }

    public String getScreenName() {
        return screenName;
    }

    public String getActionCode() {
        return actionCode;
    }

    /** An i18n label key, e.g. {@code action.m01.editRecord}. */
    public String getActionLabel() {
        return actionLabel;
    }

    public String getAllowedRoleCodes() {
        return allowedRoleCodes;
    }

    /** An i18n message key shown on the disabled button, e.g. {@code reason.checkerOnly}. */
    public String getDisabledReasonMsg() {
        return disabledReasonMsg;
    }
}
