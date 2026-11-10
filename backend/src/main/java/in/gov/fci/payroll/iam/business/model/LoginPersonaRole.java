package in.gov.fci.payroll.iam.business.model;

import in.gov.fci.payroll.organisation.business.model.LocationType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * {@code login_persona_role} (FCILekha extension): a role a persona admits and, per role, the
 * location types selectable as the login location (none: the user's own record).
 */
@Entity
@Table(name = "login_persona_role")
public class LoginPersonaRole {

    /** Composite key (persona, role). */
    @Embeddable
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "persona_code", nullable = false, length = 20)
        private String personaCode;

        @Column(name = "role_id", nullable = false)
        private Long roleId;

        protected Key() {}

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && Objects.equals(personaCode, key.personaCode)
                    && Objects.equals(roleId, key.roleId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(personaCode, roleId);
        }
    }

    @EmbeddedId private Key key;

    @Column(name = "allowed_location_types", length = 60)
    private String allowedLocationTypes;

    protected LoginPersonaRole() {}

    public String getPersonaCode() {
        return key.personaCode;
    }

    public Long getRoleId() {
        return key.roleId;
    }

    /** Empty: no location is chosen; the scope is the user's own employee record. */
    public Set<LocationType> allowedLocationTypes() {
        if (allowedLocationTypes == null || allowedLocationTypes.isBlank()) {
            return EnumSet.noneOf(LocationType.class);
        }
        Set<LocationType> types = EnumSet.noneOf(LocationType.class);
        Arrays.stream(allowedLocationTypes.split(","))
                .map(String::strip)
                .map(LocationType::valueOf)
                .forEach(types::add);
        return types;
    }
}
