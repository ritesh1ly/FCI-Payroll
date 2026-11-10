package in.gov.fci.payroll.iam.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.organisation.business.model.LocationType;
import java.lang.reflect.Field;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Mutation gap of login_persona_role.allowed_location_types: the answer is an ordinary mutable set
 * of the listed types, and also when none is listed.
 */
class LoginPersonaRoleMutationGapsTest {

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
    void theListedTypesAreReturnedInTheDeclarationOrderAndTheSetIsMutable() throws Exception {
        Set<LocationType> types = withTypes("DEPOT, HQ").allowedLocationTypes();
        assertThat(types).containsExactly(LocationType.HQ, LocationType.DEPOT);
        types.add(LocationType.ZO);
        assertThat(types).containsExactly(LocationType.HQ, LocationType.ZO, LocationType.DEPOT);
    }

    @Test
    void noTypesIsAnEmptyMutableSet() throws Exception {
        Set<LocationType> none = withTypes(null).allowedLocationTypes();
        assertThat(none).isEmpty();
        none.add(LocationType.HQ);
        assertThat(none).containsExactly(LocationType.HQ);
    }
}
