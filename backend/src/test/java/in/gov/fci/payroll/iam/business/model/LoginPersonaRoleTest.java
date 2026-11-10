package in.gov.fci.payroll.iam.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.organisation.business.model.LocationType;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/** login_persona_role.allowed_location_types: the location types a persona's role may log in at. */
class LoginPersonaRoleTest {

    private static LoginPersonaRole withTypes(String csv) throws Exception {
        var c = LoginPersonaRole.class.getDeclaredConstructor();
        c.setAccessible(true);
        LoginPersonaRole role = c.newInstance();
        Field f = LoginPersonaRole.class.getDeclaredField("allowedLocationTypes");
        f.setAccessible(true);
        f.set(role, csv);
        return role;
    }

    @Test
    void noTypesMeansTheUsersOwnRecord() throws Exception {
        assertThat(withTypes(null).allowedLocationTypes()).isEmpty();
        assertThat(withTypes("").allowedLocationTypes()).isEmpty();
        assertThat(withTypes("   ").allowedLocationTypes()).isEmpty();
    }

    @Test
    void aCommaListIsReadWithSurroundingSpaces() throws Exception {
        LocationType[] all = LocationType.values();
        String csv = all[0].name() + " , " + all[all.length - 1].name();
        assertThat(withTypes(csv).allowedLocationTypes())
                .containsExactlyInAnyOrder(all[0], all[all.length - 1]);
        assertThat(withTypes(all[0].name() + "," + all[0].name()).allowedLocationTypes())
                .containsExactly(all[0]);
    }

    @Test
    void anUnknownTypeIsAConfigurationError() throws Exception {
        assertThatThrownBy(() -> withTypes("NOT_A_TYPE").allowedLocationTypes())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
