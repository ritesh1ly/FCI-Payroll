package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.ActorDirectory.Actor;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * X-04 Maker and Checker (SRS-G-2): each signed-in user by name and the EIS number of the employee
 * record; a system or eSeva user without an employee record shows no number.
 */
class AuditActorsTest {

    private static final Instant NOW = Instant.parse("2026-10-23T05:00:00Z");

    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final AccessRepositories.Roles roles = mock(AccessRepositories.Roles.class);
    private final AuditActors actors =
            new AuditActors(
                    users, employees, roles, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

    @Test
    void SRS_G_2_eachMakerAndCheckerIsShownByNameAndEmployeeNumber() {
        AppUser checker =
                AppUser.create(
                        "checker@fci.gov.in",
                        7L,
                        "Anita Rao",
                        AppUser.Category.OPERATIONS,
                        null,
                        null,
                        300L,
                        "admin",
                        NOW);
        AppUser system =
                AppUser.create(
                        "batch@fci.gov.in",
                        null,
                        "Batch",
                        AppUser.Category.SYSTEM,
                        null,
                        null,
                        null,
                        "admin",
                        NOW);
        List<String> logins = List.of("checker@fci.gov.in", "batch@fci.gov.in");
        when(users.findByLoginIdIn(logins)).thenReturn(List.of(checker, system));
        when(employees.eisOf(7L)).thenReturn(Optional.of("104882"));
        when(employees.eisOf(null)).thenReturn(Optional.empty());
        Map<String, Actor> result = actors.actors(logins);
        assertThat(result)
                .containsEntry(
                        "checker@fci.gov.in",
                        new Actor("checker@fci.gov.in", "Anita Rao", "104882"))
                .containsEntry("batch@fci.gov.in", new Actor("batch@fci.gov.in", "Batch", null));
    }

    @Test
    void SRS_G_2_noLoginsReadNoUsers() {
        assertThat(actors.actors(null)).isEmpty();
        assertThat(actors.actors(List.of())).isEmpty();
        verify(users, never()).findByLoginIdIn(any());
    }

    /** X-04 All users: the holders of the roles of a group; a group no role matches is nobody. */
    @Test
    void wireframe_aUserGroupIsTheHoldersOfItsRoles() {
        java.util.List<in.gov.fci.payroll.iam.business.model.AppRole> held =
                java.util.List.of(
                        role("PAYROLL_MAKER"),
                        role("PAYROLL_CHECKER"),
                        role("RULE_APPROVER"),
                        role("SYSTEM_ADMINISTRATOR"));
        org.mockito.Mockito.when(roles.findAllByOrderByCode()).thenReturn(held);
        org.mockito.Mockito.when(
                        users.findLoginsHoldingRoles(
                                java.util.List.of("PAYROLL_CHECKER", "RULE_APPROVER"),
                                java.time.LocalDate.of(2026, 10, 23)))
                .thenReturn(java.util.List.of("b@x", "a@x"));
        org.assertj.core.api.Assertions.assertThat(actors.loginsOf("CHECKER"))
                .containsExactly("a@x", "b@x");
        org.assertj.core.api.Assertions.assertThat(actors.loginsOf("NOBODY")).isEmpty();
    }

    private static in.gov.fci.payroll.iam.business.model.AppRole role(String code) {
        in.gov.fci.payroll.iam.business.model.AppRole role =
                mock(in.gov.fci.payroll.iam.business.model.AppRole.class);
        org.mockito.Mockito.when(role.getCode()).thenReturn(code);
        return role;
    }
}
