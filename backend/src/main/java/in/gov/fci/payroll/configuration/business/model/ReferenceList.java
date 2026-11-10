package in.gov.fci.payroll.configuration.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.type.YesNoConverter;

/** {@code reference_list}: M-16 reference list: a named list of codes (target DDL v4.0 S02). */
@Entity
@Table(name = "reference_list")
public class ReferenceList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "list_id")
    private Long listId;

    @Column(name = "list_code", nullable = false, length = 40)
    private String listCode;

    @Column(name = "list_name", nullable = false, length = 150)
    private String listName;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "owning_module", length = 40)
    private String owningModule;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_system_list", nullable = false, length = 1)
    private Boolean isSystemList;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected ReferenceList() {}

    public Long getListId() {
        return listId;
    }

    public String getListCode() {
        return listCode;
    }

    public String getListName() {
        return listName;
    }

    public String getDescription() {
        return description;
    }

    public String getOwningModule() {
        return owningModule;
    }

    public boolean isIsSystemList() {
        return Boolean.TRUE.equals(isSystemList);
    }
}
