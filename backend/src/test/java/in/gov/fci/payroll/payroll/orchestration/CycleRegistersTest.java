package in.gov.fci.payroll.payroll.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.cpf.orchestration.CpfContributionDeduction;
import in.gov.fci.payroll.nps.orchestration.NpsDeduction;
import in.gov.fci.payroll.payroll.api.StaffDeductionRunner;
import in.gov.fci.payroll.payroll.business.CycleGates;
import in.gov.fci.payroll.tax.orchestration.ProfessionalTaxDeduction;
import in.gov.fci.payroll.tax.orchestration.TdsDeduction;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The registers a completed Actual run produces for gates G4 and G5 (SRS-15-18, SRS l.1523-1524;
 * FRS V0.5 6.11.4 Step 6; DDL v4.0 cycle_control_total, cycle_statutory_status). The Salary Control
 * Register is built from the signed lines by component type, so a line that disagrees with the
 * result header shows as a G4 difference; the disbursement register from the CURRENT results; a
 * statutory process is complete only when its source took part, with the members left uncomputed; a
 * failed run leaves every process FAILED; every row is audited (R-AUD). Amounts are fixtures.
 */
class CycleRegistersTest {

    private static final CycleRegisters.Run RUN =
            new CycleRegisters.Run(41L, 77L, 9L, LocalDate.of(2026, 8, 1), "STAFF");

    private static final Set<String> ALL =
            Set.of("CPF_PF", "INCOME_TAX", "PROFESSIONAL_TAX", "NPS_DEDUCTION");

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final StaffDeductionRunner runner = mock(StaffDeductionRunner.class);
    private final AuditService audit = mock(AuditService.class);
    private final CycleRegisters registers =
            new CycleRegisters(
                    db.jdbc,
                    runner,
                    audit,
                    Clock.fixed(Instant.parse("2026-11-09T06:00:00Z"), ZoneOffset.UTC));

    /** Three members whose header totals are 50000 / 8000 / 42000; lines as given. */
    private void computed(String earnings, String deductions, long blocked) {
        db.value("FROM payroll_result WHERE run_id", 3L)
                .rows(
                        "FROM payroll_result r JOIN payroll_result_component",
                        row("ctype", "EARNING", "amount", new BigDecimal(earnings)),
                        row("ctype", "DEDUCTION", "amount", new BigDecimal(deductions)))
                .rows(
                        "result_status = 'CURRENT'",
                        row(
                                "members",
                                3L,
                                "gross",
                                new BigDecimal("50000.00"),
                                "deductions",
                                new BigDecimal("8000.00"),
                                "net",
                                new BigDecimal("42000.00")))
                .value("FROM payroll_exception", blocked);
    }

    /** The total the producer wrote for {@code source}, as gate G4 reads it. */
    private CycleGates.Total written(String source) {
        List<Object> a =
                db.updates("INSERT INTO cycle_control_total").stream()
                        .filter(u -> u.args().get(2).equals(source))
                        .findFirst()
                        .orElseThrow()
                        .args();
        return new CycleGates.Total(
                source,
                (Long) a.get(3),
                (BigDecimal) a.get(4),
                (BigDecimal) a.get(5),
                (BigDecimal) a.get(6));
    }

    private static CycleGates.Total payrollResult() {
        return new CycleGates.Total(
                "PAYROLL_RESULT",
                3,
                new BigDecimal("50000.00"),
                new BigDecimal("8000.00"),
                new BigDecimal("42000.00"));
    }

    /** The statutory rows written: process -> [state, computed, uncomputed, completed at]. */
    private Map<Object, List<Object>> statuses(String statement) {
        Map<Object, List<Object>> byProcess = new java.util.TreeMap<>();
        for (ScriptedJdbc.Update u : db.updates(statement)) {
            List<Object> a = u.args();
            if (statement.startsWith("INSERT")) {
                byProcess.put(a.get(1), a.subList(2, 6));
            } else {
                byProcess.put(a.get(5), a.subList(0, 4));
            }
        }
        return byProcess;
    }

    @SuppressWarnings("unchecked")
    private List<AuditService.Entry> trail() {
        ArgumentCaptor<Collection<AuditService.Entry>> captor =
                ArgumentCaptor.forClass(Collection.class);
        verify(audit).recordAll(eq("SYSTEM"), captor.capture());
        return new ArrayList<>(captor.getValue());
    }

    @Test
    void SRS_15_18_G4_theThreeTotalsAgreeWhenTheLinesAddUpToTheHeader() {
        when(runner.statutoryProcesses()).thenReturn(ALL);
        computed("50000.00", "-8000.00", 0);

        registers.completed(RUN);

        CycleGates.Total scr = written("SALARY_CONTROL_REGISTER");
        assertThat(scr.members()).isEqualTo(3);
        assertThat(scr.gross()).isEqualByComparingTo("50000.00");
        assertThat(scr.deductions())
                .as("deduction lines are negative")
                .isEqualByComparingTo("8000");
        assertThat(scr.net()).isEqualByComparingTo("42000.00");
        CycleGates.Total disbursement = written("DISBURSEMENT_REGISTER");
        assertThat(disbursement.net()).isEqualByComparingTo("42000.00");
        // The partition keys of the run are bound (Oracle review F6).
        assertThat(db.queries("r.run_id = ? AND r.period_date = ? AND r.org_unit_id = ?"))
                .hasSize(1);
        CycleGates.Gate g4 =
                CycleGates.g4(Map.of("STAFF", List.of(payrollResult(), scr, disbursement)));
        assertThat(g4.met()).as(g4.detail()).isTrue();
    }

    @Test
    void SRS_15_15_G4_aLineThatDisagreesWithTheHeaderKeepsTheHardGateUnmet() {
        when(runner.statutoryProcesses()).thenReturn(ALL);
        // One deduction line is 1000 short of the header's total deductions.
        computed("50000.00", "-7000.00", 0);

        registers.completed(RUN);

        CycleGates.Total scr = written("SALARY_CONTROL_REGISTER");
        assertThat(scr.deductions()).isEqualByComparingTo("7000");
        assertThat(scr.net()).isEqualByComparingTo("43000");
        CycleGates.Gate g4 =
                CycleGates.g4(
                        Map.of(
                                "STAFF",
                                List.of(payrollResult(), scr, written("DISBURSEMENT_REGISTER"))));
        assertThat(g4.met()).isFalse();
        assertThat(g4.detail()).isEqualTo("STAFF: totals differ");
    }

    @Test
    void SRS_15_18_G4_anEmptyRunWritesZeroTotals() {
        when(runner.statutoryProcesses()).thenReturn(ALL);
        db.value("FROM payroll_result WHERE run_id", ScriptedJdbc.NULL)
                .rows(
                        "result_status = 'CURRENT'",
                        row(
                                "members",
                                0L,
                                "gross",
                                BigDecimal.ZERO,
                                "deductions",
                                BigDecimal.ZERO,
                                "net",
                                BigDecimal.ZERO));

        registers.completed(RUN);

        CycleGates.Total scr = written("SALARY_CONTROL_REGISTER");
        assertThat(scr.members()).isZero();
        assertThat(scr.gross()).isEqualByComparingTo("0");
        assertThat(scr.net()).isEqualByComparingTo("0");
        assertThat(statuses("INSERT INTO cycle_statutory_status").get("CPF_PF"))
                .startsWith("COMPLETE", 0L, 0L);
    }

    @Test
    void SRS_15_18_G5_eachProcessWithASourceIsCompleteAndAnUncomputedMemberKeepsG5Unmet() {
        when(runner.statutoryProcesses()).thenReturn(ALL);
        computed("50000.00", "-8000.00", 2);

        registers.completed(RUN);

        Map<Object, List<Object>> rows = statuses("INSERT INTO cycle_statutory_status");
        assertThat(rows).containsOnlyKeys(ALL.toArray());
        rows.values()
                .forEach(
                        r -> {
                            assertThat(r.subList(0, 3)).containsExactly("COMPLETE", 3L, 2L);
                            assertThat(r.get(3)).as("completed at").isNotNull();
                        });
        List<CycleGates.Statutory> read =
                rows.entrySet().stream()
                        .map(
                                e ->
                                        new CycleGates.Statutory(
                                                (String) e.getKey(),
                                                (String) e.getValue().get(0),
                                                (Long) e.getValue().get(2)))
                        .toList();
        assertThat(CycleGates.g5(read).met()).as("two members left uncomputed").isFalse();
        // SRS-15-16: the Checker may accept it only by naming at least the two members.
        assertThat(CycleGates.g5Exclusion(read, 1)).contains("UNNAMED_MEMBERS:2");
        assertThat(CycleGates.g5Exclusion(read, 2)).isEmpty();
    }

    @Test
    void SRS_15_18_G5_everyMemberComputedMeetsG5() {
        when(runner.statutoryProcesses()).thenReturn(ALL);
        computed("50000.00", "-8000.00", 0);

        registers.completed(RUN);

        List<CycleGates.Statutory> read =
                statuses("INSERT INTO cycle_statutory_status").entrySet().stream()
                        .map(
                                e ->
                                        new CycleGates.Statutory(
                                                (String) e.getKey(),
                                                (String) e.getValue().get(0),
                                                (Long) e.getValue().get(2)))
                        .toList();
        assertThat(CycleGates.g5(read).met()).isTrue();
    }

    @Test
    void SRS_15_18_G5_aProcessWithoutASourceIsNeverReportedComplete() {
        when(runner.statutoryProcesses())
                .thenReturn(Set.of("CPF_PF", "INCOME_TAX", "PROFESSIONAL_TAX"));
        computed("50000.00", "-8000.00", 0);

        registers.completed(RUN);

        Map<Object, List<Object>> rows = statuses("INSERT INTO cycle_statutory_status");
        assertThat(rows.get("NPS_DEDUCTION")).containsExactly("NOT_STARTED", null, null, null);
        List<CycleGates.Statutory> read =
                rows.entrySet().stream()
                        .map(
                                e ->
                                        new CycleGates.Statutory(
                                                (String) e.getKey(),
                                                (String) e.getValue().get(0),
                                                (Long) e.getValue().get(2)))
                        .toList();
        CycleGates.Gate g5 = CycleGates.g5(read);
        assertThat(g5.met()).isFalse();
        assertThat(g5.detail()).isEqualTo("Not complete: NPS_DEDUCTION");
        assertThat(CycleGates.g5Exclusion(read, 5))
                .as("no member exclusion covers a process that did not run")
                .contains("PROCESS_NOT_COMPLETE:NPS_DEDUCTION");
    }

    @Test
    void SRS_EH_6_aFailedRunLeavesEveryProcessFailedAndWritesNoTotal() {
        when(runner.statutoryProcesses()).thenReturn(Set.of("CPF_PF", "INCOME_TAX"));
        db.value("FROM payroll_result WHERE run_id", 2L);

        registers.failed(RUN);

        assertThat(db.updates("cycle_control_total")).isEmpty();
        Map<Object, List<Object>> rows = statuses("INSERT INTO cycle_statutory_status");
        assertThat(rows.get("CPF_PF")).containsExactly("FAILED", 2L, null, null);
        assertThat(rows.get("INCOME_TAX")).containsExactly("FAILED", 2L, null, null);
        assertThat(rows.get("NPS_DEDUCTION")).containsExactly("NOT_STARTED", null, null, null);
        assertThat(trail())
                .hasSize(4)
                .allSatisfy(
                        e -> {
                            assertThat(e.entityName()).isEqualTo("cycle_statutory_status");
                            assertThat(e.reason()).isEqualTo("Actual run 77 failed");
                        });
    }

    @Test
    void R_AUD_aReComputationUpdatesTheRowsAndAuditsTheValuesBefore() {
        when(runner.statutoryProcesses()).thenReturn(ALL);
        computed("50000.00", "-8000.00", 0);
        db.rows(
                        "FROM cycle_control_total WHERE",
                        row(
                                "m",
                                2L,
                                "g",
                                new BigDecimal("40000.00"),
                                "d",
                                new BigDecimal("6000.00"),
                                "n",
                                new BigDecimal("34000.00")))
                .rows("SELECT process_state FROM cycle_statutory_status", row("s", "FAILED"));

        registers.completed(RUN);

        assertThat(db.updates("INSERT INTO")).isEmpty();
        assertThat(db.updates("UPDATE cycle_control_total")).hasSize(2);
        assertThat(statuses("UPDATE cycle_statutory_status").get("CPF_PF"))
                .startsWith("COMPLETE", 3L, 0L);
        List<AuditService.Entry> trail = trail();
        assertThat(trail).hasSize(6);
        AuditService.Entry scr = trail.get(0);
        assertThat(scr.entityName()).isEqualTo("cycle_control_total");
        assertThat(scr.entityKey()).isEqualTo("41:STAFF:SALARY_CONTROL_REGISTER");
        assertThat(scr.oldValue())
                .isEqualTo("2 members, gross 40000.00, deductions 6000.00, net 34000.00");
        assertThat(scr.newValue()).startsWith("3 members, gross 50000.00");
        assertThat(scr.reason()).isEqualTo("Produced by Actual run 77 (FRS 6.11.4 Step 6)");
        assertThat(scr.moduleCode()).isEqualTo("C-02");
        assertThat(trail.get(2).oldValue()).isEqualTo("FAILED");
        assertThat(trail.get(2).newValue()).isEqualTo("COMPLETE");
        verify(audit).recordAll(eq("SYSTEM"), anyCollection());
    }

    @Test
    void SRS_15_18_G5_theFourStatutorySourcesNameTheirProcess() {
        assertThat(mock(CpfContributionDeduction.class, CALLS_REAL_METHODS).statutoryProcess())
                .contains("CPF_PF");
        assertThat(mock(TdsDeduction.class, CALLS_REAL_METHODS).statutoryProcess())
                .contains("INCOME_TAX");
        assertThat(mock(ProfessionalTaxDeduction.class, CALLS_REAL_METHODS).statutoryProcess())
                .contains("PROFESSIONAL_TAX");
        assertThat(mock(NpsDeduction.class, CALLS_REAL_METHODS).statutoryProcess())
                .contains("NPS_DEDUCTION");
    }
}
