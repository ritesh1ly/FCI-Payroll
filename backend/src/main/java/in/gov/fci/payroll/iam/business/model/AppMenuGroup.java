package in.gov.fci.payroll.iam.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code app_menu_group} (V078): one navigation group of the workspace sidebar, exactly as
 * Wireframes v6.1 draws it (OD-121: the wireframe is a frozen requirement; SRS-G-54 screen
 * designs). The groups are the pack's NAVGROUPS in their order; {@code home} is the header-less
 * first entry. The label is an i18n key ({@code menu.section.<group_code>}, en and hi), never a
 * literal.
 */
@Entity
@Table(name = "app_menu_group")
public class AppMenuGroup {

    @Id
    @Column(name = "group_code", length = 40)
    private String code;

    @Column(name = "group_order", nullable = false)
    private int groupOrder;

    /** The pack's section number ("3.2.1", "3.3 · 4"), for the review reports; blank for home. */
    @Column(name = "section_ref", length = 20)
    private String sectionRef;

    protected AppMenuGroup() {}

    public String getCode() {
        return code;
    }

    public int getGroupOrder() {
        return groupOrder;
    }

    public String getSectionRef() {
        return sectionRef == null ? "" : sectionRef;
    }
}
