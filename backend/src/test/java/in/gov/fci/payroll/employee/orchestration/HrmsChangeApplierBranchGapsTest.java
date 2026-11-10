package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The I-21 applier's lookups at their edges (SRS-1-28, plan 16 D16-4): a task without a field code
 * has no owning module; a field whose source registry has no employee column is not applicable.
 */
class HrmsChangeApplierBranchGapsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final HrmsChangeApplier applier =
            new HrmsChangeApplier(jdbc, mock(AuditService.class), mock(SystemParameters.class));

    @Test
    void SRS_1_28_noFieldCodeHasNoOwningModuleAndARegisteredOneHasIts() {
        HrmsTaskEffects loans = mock(HrmsTaskEffects.class);
        when(loans.fields()).thenReturn(Set.of("LOAN_FORECLOSURE"));
        applier.setEffects(List.of(loans));
        assertThat(applier.effectFor(null)).isEmpty();
        assertThat(applier.effectFor("LOAN_FORECLOSURE")).containsSame(loans);
        assertThat(applier.effectFor("PAN")).isEmpty();
    }

    @Test
    void SRS_1_28_aFieldWhoseSourceHasNoEmployeeColumnIsNotApplicable() {
        Map<String, Object> field = new HashMap<>();
        field.put("FIELD_CODE", "NICKNAME");
        field.put("FIELD_SOURCE_ID", 5L);
        when(jdbc.queryForList(anyString(), eq(String.class), anyLong())).thenReturn(List.of());
        assertThat(applier.column(field)).isNull();
        assertThat(applier.applicable(field)).isFalse();
        assertThat(applier.current(field, 7L)).isNull();
    }
}
