package in.gov.fci.payroll.allowance.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.allowance.business.model.CafeteriaElection;
import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects.Task;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-16 Cafeteria Allowance from HRMS through I-21 (SRS-1-73; plan 10 D10-6): a selection that is
 * empty, not JSON, not an object or without components is not readable; an approved selection
 * supersedes only the version in force (never one already superseded) and a pay code missing from
 * M-07 refuses it.
 */
class CafeteriaEffectsTest {

    private static final String OK =
            "{\"finYear\":\"2026-27\",\"effectiveFrom\":\"2026-10-01\","
                    + "\"components\":[{\"payCode\":951,\"amount\":\"1000\"}]}";

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final AllowanceRepositories.Elections elections =
            mock(AllowanceRepositories.Elections.class);
    private final ClaimCatalogue catalogue = mock(ClaimCatalogue.class);
    private final CafeteriaArrears arrears = mock(CafeteriaArrears.class);
    private final CafeteriaEffects effects =
            new CafeteriaEffects(
                    elections,
                    mock(AllowanceRepositories.ElectionLines.class),
                    catalogue,
                    mock(AuditService.class),
                    db.jdbc,
                    JsonMapper.builder().build(),
                    arrears);

    CafeteriaEffectsTest() {
        when(catalogue.member(7L))
                .thenReturn(
                        new ClaimCatalogue.MemberFacts(
                                7L, "E7", "Asha", "STAFF", "IN_SERVICE", null, 40L));
        when(catalogue.payCodeActive(anyInt(), any())).thenReturn(true);
        // The database assigns the id on save.
        when(elections.save(any()))
                .thenAnswer(
                        call -> {
                            Object e = call.getArgument(0);
                            org.springframework.test.util.ReflectionTestUtils.setField(
                                    e, "id", 21L);
                            return e;
                        });
    }

    @Test
    void SRS_1_73_aSelectionThatIsNotAnObjectWithComponentsIsNotReadable() {
        for (String bad :
                Arrays.asList(
                        null,
                        "",
                        "{bad",
                        "[1]",
                        "{\"finYear\":\"2026-27\",\"effectiveFrom\":\"2026-10-01\"}",
                        "{\"finYear\":\"2026-27\",\"effectiveFrom\":\"2026-10-01\",\"components\":[]}")) {
            assertThatThrownBy(() -> effects.check(CafeteriaEffects.FIELD, 7L, bad))
                    .as(String.valueOf(bad))
                    .isInstanceOf(FciException.class);
        }
    }

    private static Task task() {
        return new Task(
                5L, "T-5", 7L, CafeteriaEffects.FIELD, OK, LocalDate.of(2026, 10, 1), "HR-5");
    }

    @Test
    void SRS_1_73_anApprovalSupersedesOnlyTheVersionInForce() {
        CafeteriaElection applied = mock(CafeteriaElection.class);
        when(applied.getStatus()).thenReturn(CafeteriaElection.Status.APPLIED.name());
        when(applied.getVersionNo()).thenReturn(2);
        CafeteriaElection superseded = mock(CafeteriaElection.class);
        when(superseded.getStatus()).thenReturn(CafeteriaElection.Status.SUPERSEDED.name());
        when(elections.findByEmployeeIdAndFinYearOrderByVersionNoDesc(7L, "2026-27"))
                .thenReturn(List.of(applied, superseded));
        db.rows("FROM pay_component WHERE legacy_pay_code", cols(31L));
        effects.apply(task(), "checker@x", 9L, Instant.parse("2026-10-29T06:00:00Z"));
        verify(applied).supersededFrom(LocalDate.of(2026, 10, 1));
        verify(superseded, never()).supersededFrom(any());
    }

    @Test
    void SRS_11_16_aVersionWithAnArrearRaisedIsSupersededToo_andTheNewOneIsSettled() {
        CafeteriaElection raised = mock(CafeteriaElection.class);
        when(raised.getStatus()).thenReturn(CafeteriaElection.Status.ARREAR_RAISED.name());
        when(raised.getVersionNo()).thenReturn(1);
        when(elections.findByEmployeeIdAndFinYearOrderByVersionNoDesc(7L, "2026-27"))
                .thenReturn(List.of(raised));
        db.rows("FROM pay_component WHERE legacy_pay_code", cols(31L));
        effects.apply(task(), "checker@x", 9L, Instant.parse("2026-10-29T06:00:00Z"));
        verify(raised).supersededFrom(LocalDate.of(2026, 10, 1));
        verify(elections).saveAndFlush(raised);
        verify(arrears)
                .settle(
                        any(),
                        org.mockito.ArgumentMatchers.eq("checker@x"),
                        org.mockito.ArgumentMatchers.eq(9L));
    }

    @Test
    void SRS_1_73_aPayCodeMissingFromTheCatalogueRefusesTheSelection() {
        when(elections.findByEmployeeIdAndFinYearOrderByVersionNoDesc(7L, "2026-27"))
                .thenReturn(List.of());
        db.value("FROM pay_component WHERE legacy_pay_code", ScriptedJdbc.NULL);
        assertThatThrownBy(
                        () ->
                                effects.apply(
                                        task(),
                                        "checker@x",
                                        9L,
                                        Instant.parse("2026-10-29T06:00:00Z")))
                .isInstanceOf(FciException.class);
    }
}
