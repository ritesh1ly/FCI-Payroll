package in.gov.fci.payroll.iam.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code app_menu_item} (V078): one entry of a navigation group, as Wireframes v6.1 draws it
 * (OD-121). The id is the pack's screen id ({@code m2d}, {@code i4b} ...), so one screen may appear
 * under several groups with its own label and view (the labour masters per engine, the loans view
 * of I-04): {@code route_suffix} is appended to the screen's route ({@code &engine=DPS}). The label
 * is an i18n key ({@code nav.<id>}); the permission is the screen's {@code <code>:VIEW}, as before.
 */
@Entity
@Table(name = "app_menu_item")
public class AppMenuItem {

    @Id
    @Column(name = "menu_item_id", length = 20)
    private String id;

    @Column(name = "group_code", nullable = false, length = 40)
    private String groupCode;

    @Column(name = "item_order", nullable = false)
    private int itemOrder;

    @Column(name = "screen_code", nullable = false, length = 20)
    private String screenCode;

    @Column(name = "route_suffix", length = 60)
    private String routeSuffix;

    protected AppMenuItem() {}

    public String getId() {
        return id;
    }

    public String getGroupCode() {
        return groupCode;
    }

    public int getItemOrder() {
        return itemOrder;
    }

    public String getScreenCode() {
        return screenCode;
    }

    /** The view appended to the screen's route; empty when the entry opens the screen as such. */
    public String getRouteSuffix() {
        return routeSuffix == null ? "" : routeSuffix;
    }
}
