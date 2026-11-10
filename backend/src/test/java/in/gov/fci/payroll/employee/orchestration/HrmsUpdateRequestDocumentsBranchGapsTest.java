package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The order copies of an HRMS update request on the member's own eSeva screen (SRS-17-15, SRS-G-31,
 * R-RBAC data scope): a session without an employee record, or another member's request, reads as
 * not found (no enumeration); the member's own request lists its copies.
 */
class HrmsUpdateRequestDocumentsBranchGapsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final HrmsUpdateRequestDocuments service =
            new HrmsUpdateRequestDocuments(
                    jdbc, mock(OrgDirectory.class), mock(Authorization.class), documents);

    HrmsUpdateRequestDocumentsBranchGapsTest() {
        when(jdbc.queryForList(anyString(), eq(41L)))
                .thenReturn(List.of(Map.of("EMPLOYEE_ID", 7L, "LOCATION_ID", 300L)));
    }

    private static AccessContext member(Long employeeId) {
        return new AccessContext(
                2L,
                "member@x",
                "Member",
                "s",
                "E",
                300L,
                employeeId,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of());
    }

    @Test
    void SRS_17_15_aSessionWithoutAnEmployeeOrAnotherMembersRequestIsNotFound() {
        for (Long who : new Long[] {null, 8L}) {
            assertThatThrownBy(() -> service.list(member(who), 41L, true))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
        }
        verifyNoInteractions(documents);
        assertThat(service.list(member(7L), 41L, true)).isEmpty();
    }
}
