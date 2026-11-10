package in.gov.fci.payroll.iam.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

/**
 * The six personas of the live login page (plus the auditor) map one-to-one onto the {@code
 * login_persona} codes; an unknown, blank or differently cased id is no persona (SRS-G-48 sign-in).
 */
class PersonasTest {

    @Test
    void SRS_G_48_everyPersonaIsFoundByItsLiveIdAndByItsApiCode() {
        assertThat(Personas.ALL).hasSize(7);
        for (Personas.Persona persona : Personas.ALL) {
            assertThat(Personas.byId(persona.id())).contains(persona);
            assertThat(Personas.byApiCode(persona.apiCode())).contains(persona);
            assertThat(persona.route()).isEqualTo(Personas.WORKSPACE);
        }
    }

    @Test
    void SRS_G_48_theIdsAndTheApiCodesAreUniqueSoALookupNeverMeetsTwo() {
        assertThat(new HashSet<>(Personas.ALL.stream().map(Personas.Persona::id).toList()))
                .hasSameSizeAs(Personas.ALL);
        assertThat(new HashSet<>(Personas.ALL.stream().map(Personas.Persona::apiCode).toList()))
                .hasSameSizeAs(Personas.ALL);
    }

    @Test
    void SRS_G_48_anUnknownBlankNullOrDifferentlyCasedIdIsNoPersona() {
        assertThat(Personas.byId("payroll")).isPresent();
        assertThat(Personas.byId("PAYROLL")).as("ids are lower case").isEmpty();
        assertThat(Personas.byId("")).isEmpty();
        assertThat(Personas.byId(null)).isEmpty();
        assertThat(Personas.byId("kotwal")).isEmpty();
        assertThat(Personas.byApiCode("payroll")).as("codes are upper case").isEmpty();
        assertThat(Personas.byApiCode("PAYROLL")).isPresent();
        assertThat(Personas.byApiCode(null)).isEmpty();
        assertThat(Personas.byApiCode(" ")).isEmpty();
    }

    @Test
    void SRS_G_48_theLiveIdOfAPersonaDiffersFromItsApiCodeWhereTheLoginPageSaysSo() {
        assertThat(Personas.byId("manager").orElseThrow().apiCode()).isEqualTo("DDO_MANAGER");
        assertThat(Personas.byId("sysadmin").orElseThrow().apiCode()).isEqualTo("SYSTEM_ADMIN");
        assertThat(Personas.byApiCode("EMPLOYEE").orElseThrow().id()).isEqualTo("employee");
    }

    @Test
    void SRS_G_48_aLocationLabelIsTypeNameAndCodeAndDataValuesAreNeverTranslated() {
        assertThat(Personas.locationLabel("DO", "Delhi HQ", "DH001"))
                .isEqualTo("DO — Delhi HQ (DH001)");
        assertThat(Personas.locationLabel(null, null, null)).isEqualTo("null — null (null)");
    }
}
