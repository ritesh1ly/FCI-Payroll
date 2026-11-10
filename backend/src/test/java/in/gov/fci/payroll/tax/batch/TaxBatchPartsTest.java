package in.gov.fci.payroll.tax.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.tax.business.model.Form16Certificate;
import in.gov.fci.payroll.tax.business.model.ReturnDeductee;
import in.gov.fci.payroll.tax.orchestration.Form16Service;
import in.gov.fci.payroll.tax.orchestration.ReturnService;
import in.gov.fci.payroll.tax.orchestration.TaxComputationService;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The parts of the Module 08 background jobs (SRS-NF-7; C-10, C-11): the run opened once and closed
 * only when complete, the requester named, the per-member processors filtering what the screens
 * pass over. The whole jobs run in TaxBackgroundJobsIT.
 */
class TaxBatchPartsTest {

    private static final String YEAR = "2025-26";

    private final OrgDirectory org = mock(OrgDirectory.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Form16Service form16 = mock(Form16Service.class);
    private final ReturnService returns = mock(ReturnService.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);

    private OrgLocation unit() {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(50L);
        when(unit.getCode()).thenReturn("DH001");
        when(org.cycleUnitOf(7L)).thenReturn(Optional.of(unit));
        return unit;
    }

    private static JobExecution execution(boolean quarter) {
        JobParametersBuilder p =
                new JobParametersBuilder()
                        .addLong(BatchKeys.REQUEST_ID, 9L)
                        .addString("locationId", "7")
                        .addString("finYear", YEAR);
        if (quarter) {
            p.addString("quarter", "Q4");
        }
        return new JobExecution(1L, new JobInstance(1L, "tax"), p.toJobParameters());
    }

    private void requestedBy(List<String> logins) {
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(logins);
    }

    @Test
    void SRS_NF_7_partBIsOpenedOnceInTheRequestersNameAndAuditedWhenComplete() {
        unit();
        requestedBy(List.of("tax.maker@x"));
        when(form16.preparePartB(50L, YEAR))
                .thenReturn(new Form16Service.Prepared(LocalDate.of(2026, 3, 31), 31L, 2));
        JobExecutionListener run = TaxRunListeners.partB(org, jdbc, form16);
        JobExecution e = execution(false);

        run.beforeJob(e);

        assertThat(e.getExecutionContext().getString(TaxJobKeys.ACTOR)).isEqualTo("tax.maker@x");
        assertThat(e.getExecutionContext().getLong(TaxJobKeys.UNIT_ID)).isEqualTo(50L);
        assertThat(e.getExecutionContext().getString(TaxJobKeys.YEAR_END)).isEqualTo("2026-03-31");
        assertThat(e.getExecutionContext().getLong(TaxJobKeys.VERSION_ID)).isEqualTo(31L);
        // A restart finds the run opened: nothing is checked or opened again.
        run.beforeJob(e);
        verify(form16).preparePartB(50L, YEAR);

        // Not complete: no audit record.
        e.setStatus(BatchStatus.FAILED);
        run.afterJob(e);
        verify(form16, never()).recordPartBRun(any(), any(), any(), anyLong(), anyLong(), anyInt());

        StepExecution manager = new StepExecution(2L, "taxForm16PartBStep", e);
        manager.setWriteCount(99);
        StepExecution worker = new StepExecution(3L, "taxForm16PartBWorker:partition0", e);
        worker.setWriteCount(4);
        worker.setFilterCount(6);
        e.addStepExecutions(List.of(manager, worker));
        e.setStatus(BatchStatus.COMPLETED);
        run.afterJob(e);
        verify(form16).recordPartBRun("tax.maker@x", "DH001", YEAR, 4, 6, 2);
    }

    @Test
    void SRS_NF_7_aStoppingOrUnopenedRunIsNotOpenedOrClosed() {
        JobExecutionListener run = TaxRunListeners.partB(org, jdbc, form16);
        JobExecution stopping = execution(false);
        stopping.setStatus(BatchStatus.STOPPING);
        run.beforeJob(stopping);
        JobExecution refused = execution(false);
        refused.setStatus(BatchStatus.COMPLETED);
        run.afterJob(refused);

        JobExecutionListener quarter = TaxRunListeners.quarterReturn(org, jdbc, returns);
        quarter.beforeJob(stopping);
        quarter.afterJob(refused);

        verifyNoInteractions(form16, returns, jdbc);
    }

    @Test
    void SRS_NF_7_theQuarterIsOpenedAsDraftAndFinishedOnlyWhenComplete() {
        unit();
        requestedBy(List.of());
        when(returns.openReturn("system", 50L, YEAR, "Q4", null, true))
                .thenReturn(
                        new ReturnService.Opened(
                                21L,
                                50L,
                                YEAR,
                                "Q4",
                                List.of("202601", "202602", "202603"),
                                LocalDate.of(2026, 3, 31),
                                null));
        JobExecutionListener run = TaxRunListeners.quarterReturn(org, jdbc, returns);
        JobExecution e = execution(true);

        run.beforeJob(e);

        assertThat(e.getExecutionContext().getString(TaxJobKeys.ACTOR))
                .as("no requester on record: the system")
                .isEqualTo("system");
        assertThat(e.getExecutionContext().getLong(TaxJobKeys.RETURN_ID)).isEqualTo(21L);
        assertThat(e.getExecutionContext().getString(TaxJobKeys.MONTHS))
                .isEqualTo("202601,202602,202603");
        assertThat(e.getExecutionContext().containsKey(TaxJobKeys.CHALLAN_ID)).isFalse();

        e.setStatus(BatchStatus.FAILED);
        run.afterJob(e);
        verify(returns, never()).finishReturn(any(), any());
        e.setStatus(BatchStatus.COMPLETED);
        run.afterJob(e);
        verify(returns).finishReturn("system", 21L);
    }

    @Test
    void SRS_7_7_theChallanOfTheQuarterIsKeptAndANullRequesterIsTheSystem() {
        unit();
        java.util.ArrayList<String> none = new java.util.ArrayList<>();
        none.add(null);
        requestedBy(none);
        when(returns.openReturn("system", 50L, YEAR, "Q4", null, true))
                .thenReturn(
                        new ReturnService.Opened(
                                21L,
                                50L,
                                YEAR,
                                "Q4",
                                List.of("202601"),
                                LocalDate.of(2026, 3, 31),
                                77L));
        JobExecution e = execution(true);

        TaxRunListeners.quarterReturn(org, jdbc, returns).beforeJob(e);

        assertThat(e.getExecutionContext().getLong(TaxJobKeys.CHALLAN_ID)).isEqualTo(77L);
    }

    @Test
    void SRS_NF_7_theUnitsLocationsAreThoseItPaysFor() {
        OrgLocation unit = unit();
        OrgLocation other = mock(OrgLocation.class);
        when(other.getId()).thenReturn(60L);
        when(org.subtree(50L)).thenReturn(Set.of(50L, 51L, 60L, 61L));
        when(org.cycleUnitOf(50L)).thenReturn(Optional.of(unit));
        when(org.cycleUnitOf(51L)).thenReturn(Optional.of(unit));
        when(org.cycleUnitOf(60L)).thenReturn(Optional.of(other));
        when(org.cycleUnitOf(61L)).thenReturn(Optional.empty());

        assertThat(TaxMemberReader.locationsOf(org, 50L)).containsExactlyInAnyOrder(50L, 51L);
        assertThat(new TaxMemberReader.Row(5L).itemKey()).isEqualTo("employee:5");
        when(org.cycleUnitOf(8L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> TaxMemberReader.unitOf(org, 8L))
                .isInstanceOf(in.gov.fci.payroll.platform.error.FciException.class);
    }

    @Test
    void SRS_NF_7_theReaderPagesTheUnitsMembersByRecordIdAndRestarts() throws Exception {
        org.h2.jdbcx.JdbcDataSource h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:taxreader;MODE=Oracle;DB_CLOSE_DELAY=-1");
        try (var c = h2.getConnection();
                var s = c.createStatement()) {
            s.execute(
                    "CREATE TABLE IF NOT EXISTS employee (employee_id BIGINT, location_id BIGINT)");
            s.execute("DELETE FROM employee");
            s.execute("INSERT INTO employee VALUES (3, 50), (1, 50), (2, 51), (4, 60)");
        }
        var reader = TaxMemberReader.create("taxMemberReader", h2, List.of(50L, 51L), 2);
        var context = new org.springframework.batch.infrastructure.item.ExecutionContext();
        reader.open(context);
        List<Long> read = new java.util.ArrayList<>();
        for (TaxMemberReader.Row r = reader.read(); r != null; r = reader.read()) {
            read.add(r.employeeId());
        }
        reader.update(context);
        reader.close();
        assertThat(read).containsExactly(1L, 2L, 3L);
        assertThat(context.isEmpty()).as("its position is saved for a restart").isFalse();
    }

    @Test
    void SRS_7_19_partBOfAMemberIsCertifiedOrTalliedWithoutItsAmount() {
        TaxRepositories.Form16s certificates = mock(TaxRepositories.Form16s.class);
        TaxComputationService.Run rates = mock(TaxComputationService.Run.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-25T06:00:00Z"), ZoneOffset.UTC);
        PartBProcessor processor =
                new PartBProcessor(
                        employees,
                        form16,
                        certificates,
                        rates,
                        clock,
                        "tax.maker@x",
                        YEAR,
                        LocalDate.of(2026, 3, 31),
                        31L);
        EmployeeDirectory.Member asha =
                new EmployeeDirectory.Member(1L, "870001", "A", 7L, "ACTIVE");
        EmployeeDirectory.Member lata =
                new EmployeeDirectory.Member(2L, "870005", "L", 7L, "ACTIVE");
        Form16Certificate certificate = mock(Form16Certificate.class);
        when(employees.member(1L)).thenReturn(Optional.of(asha));
        when(employees.member(2L)).thenReturn(Optional.of(lata));
        when(employees.member(3L)).thenReturn(Optional.empty());
        when(certificates.findByEmployeeIdAndFinYear(any(), eq(YEAR))).thenReturn(Optional.empty());
        when(form16.partBOf(
                        "tax.maker@x",
                        asha,
                        YEAR,
                        LocalDate.of(2026, 3, 31),
                        31L,
                        rates,
                        null,
                        clock.instant()))
                .thenReturn(new Form16Service.PartB(certificate, null));
        when(form16.partBOf(
                        "tax.maker@x",
                        lata,
                        YEAR,
                        LocalDate.of(2026, 3, 31),
                        31L,
                        rates,
                        null,
                        clock.instant()))
                .thenReturn(
                        new Form16Service.PartB(
                                null,
                                new Form16Service.Skipped(
                                        "870005", "actual tax differs from the TDS by Rs 83400")));

        assertThat(processor.process(new TaxMemberReader.Row(1L))).isSameAs(certificate);
        assertThat(processor.process(new TaxMemberReader.Row(2L))).isNull();
        assertThat(processor.process(new TaxMemberReader.Row(3L))).isNull();
        assertThat(PartBProcessor.category("actual tax differs from the TDS by Rs 83400"))
                .isEqualTo("year-end mismatch (X-05)");
        assertThat(PartBProcessor.category("the year is not final"))
                .isEqualTo("the year is not final");

        StepExecution step =
                new StepExecution(1L, "taxForm16PartBWorker:partition0", execution(false));
        assertThat(processor.afterStep(step)).isEqualTo(step.getExitStatus());
        assertThat(step.getExecutionContext().getString(PartBProcessor.NOT_CERTIFIED))
                .isEqualTo("{no member record=1, year-end mismatch (X-05)=1}")
                .doesNotContain("83400");
        StepExecution clean =
                new StepExecution(2L, "taxForm16PartBWorker:partition1", execution(false));
        PartBProcessor quiet =
                new PartBProcessor(
                        employees,
                        form16,
                        certificates,
                        rates,
                        clock,
                        "a",
                        YEAR,
                        LocalDate.of(2026, 3, 31),
                        31L);
        assertThat(quiet.afterStep(clean)).isEqualTo(clean.getExitStatus());
        assertThat(clean.getExecutionContext().containsKey(PartBProcessor.NOT_CERTIFIED)).isFalse();
    }

    @Test
    void SRS_7_7_aDeducteeRowIsTheScreensAndAMissingMemberIsFiltered() {
        TaxComputationService.Run rates = mock(TaxComputationService.Run.class);
        ReturnService.Opened opened =
                new ReturnService.Opened(
                        21L, 50L, YEAR, "Q4", List.of("202601"), LocalDate.of(2026, 3, 31), null);
        DeducteeProcessor processor = new DeducteeProcessor(employees, returns, opened, rates);
        EmployeeDirectory.Member asha =
                new EmployeeDirectory.Member(1L, "870001", "A", 7L, "ACTIVE");
        ReturnDeductee existing = mock(ReturnDeductee.class);
        when(employees.member(1L)).thenReturn(Optional.of(asha));
        when(employees.member(3L)).thenReturn(Optional.empty());
        when(returns.existingRow(21L, 1L)).thenReturn(existing);
        when(returns.deducteeOf(asha, opened, rates, existing)).thenReturn(Optional.of(existing));

        assertThat(processor.process(new TaxMemberReader.Row(1L))).isSameAs(existing);
        assertThat(processor.process(new TaxMemberReader.Row(3L))).isNull();
    }
}
