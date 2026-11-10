package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.business.model.AppRole.BaseRole;
import in.gov.fci.payroll.iam.business.model.AppRole.RoleType;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The workflow's role mapping (SRS-22-8: roles are configuration; DDL {@code app_role}: a MODULE
 * role is mapped to one of the seven functional roles by {@code base_role_code}, a READ_ONLY role
 * to none). A module role acts at a level naming the FUNCTIONAL role of its base role; no other
 * role stands in for another.
 */
class RoleCatalogueEquivalenceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);

    private final AccessRepositories.Roles roles = mock(AccessRepositories.Roles.class);
    private final RoleCatalogueEquivalence equivalence = new RoleCatalogueEquivalence(roles);

    private AppRole role(String code, RoleType type, BaseRole base) {
        AppRole role = AppRole.create(code, code, null, true, type, base, FROM);
        when(roles.findByCode(code)).thenReturn(Optional.of(role));
        return role;
    }

    @Test
    void SRS_22_8_aModuleRoleActsAsTheFunctionalRoleOfItsBaseRole() {
        role("RULE_APPROVER", RoleType.MODULE, BaseRole.CHECKER);
        role("PAYROLL_CHECKER", RoleType.FUNCTIONAL, BaseRole.CHECKER);

        assertThat(equivalence.actsAs("RULE_APPROVER", "PAYROLL_CHECKER")).isTrue();
        // Never the other way round: the level names the module role, which only its holders have.
        assertThat(equivalence.actsAs("PAYROLL_CHECKER", "RULE_APPROVER")).isFalse();
    }

    @Test
    void SRS_22_8_aModuleRoleNeverActsAsAFunctionalRoleOfAnotherBase() {
        role("RULE_APPROVER", RoleType.MODULE, BaseRole.CHECKER);
        role("PAYROLL_MAKER", RoleType.FUNCTIONAL, BaseRole.MAKER);

        assertThat(equivalence.actsAs("RULE_APPROVER", "PAYROLL_MAKER")).isFalse();
    }

    @Test
    void SRS_22_8_twoModuleRolesOfOneBaseNeverStandInForEachOther() {
        role("RULE_APPROVER", RoleType.MODULE, BaseRole.CHECKER);
        role("CPF_DIVISION", RoleType.MODULE, BaseRole.CHECKER);

        assertThat(equivalence.actsAs("RULE_APPROVER", "CPF_DIVISION")).isFalse();
    }

    @Test
    void SRS_22_8_twoFunctionalRolesOfOneBaseNeverStandInForEachOther() {
        role("PAYROLL_CHECKER", RoleType.FUNCTIONAL, BaseRole.CHECKER);
        role("ZONE_CHECKER", RoleType.FUNCTIONAL, BaseRole.CHECKER);

        assertThat(equivalence.actsAs("PAYROLL_CHECKER", "ZONE_CHECKER")).isFalse();
    }

    @Test
    void SRS_22_27_aReadOnlyRoleActsAtNoLevel() {
        role("AUDITOR", RoleType.READ_ONLY, null);
        role("PAYROLL_CHECKER", RoleType.FUNCTIONAL, BaseRole.CHECKER);

        assertThat(equivalence.actsAs("AUDITOR", "PAYROLL_CHECKER")).isFalse();
    }

    @Test
    void SRS_22_8_aRoleActsAsItselfWithoutReadingTheCatalogue() {
        assertThat(equivalence.actsAs("PAYROLL_CHECKER", "PAYROLL_CHECKER")).isTrue();
        verifyNoInteractions(roles);
    }

    @Test
    void SRS_22_8_aMissingOrUnknownRoleNeverActs() {
        role("PAYROLL_CHECKER", RoleType.FUNCTIONAL, BaseRole.CHECKER);
        role("RULE_APPROVER", RoleType.MODULE, BaseRole.CHECKER);

        assertThat(equivalence.actsAs(null, "PAYROLL_CHECKER")).isFalse();
        assertThat(equivalence.actsAs("RULE_APPROVER", null)).isFalse();
        // A code no longer (or never) in the catalogue.
        assertThat(equivalence.actsAs("RETIRED_ROLE", "PAYROLL_CHECKER")).isFalse();
        assertThat(equivalence.actsAs("RULE_APPROVER", "RETIRED_LEVEL_ROLE")).isFalse();
    }

    @Test
    void SRS_22_8_aRoleIsActiveFromItsStartUntilItIsRetired() {
        AppRole approver = role("RULE_APPROVER", RoleType.MODULE, BaseRole.CHECKER);

        assertThat(equivalence.isActiveRole("RULE_APPROVER", FROM)).isTrue();
        assertThat(equivalence.isActiveRole("RULE_APPROVER", FROM.minusDays(1))).isFalse();
        assertThat(equivalence.isActiveRole("UNKNOWN", FROM)).isFalse();
        assertThat(equivalence.isActiveRole(null, FROM)).isFalse();

        approver.retire(FROM.plusDays(10));
        assertThat(equivalence.isActiveRole("RULE_APPROVER", FROM.plusDays(5))).isFalse();
    }
}
