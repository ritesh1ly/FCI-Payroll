package in.gov.fci.payroll.payroll.batch.arrear;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.batch.support.InvalidRecordException;
import in.gov.fci.payroll.payroll.batch.reader.EmployeeRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DA_ARREAR step parts (BR-ARR-DA-002 / 003; v4.0 UC-02 E1): per member and month the DA in force
 * now on the basis the ACTUAL result used, less the DA it paid; members without a differential are
 * filtered; months without an ACTUAL result are counted, never invented; a missing DA rate skips
 * the member as an invalid record. The event is raised once per execution and COMPUTED only after a
 * completed run.
 */
class DaArrearStepTest {

    private static final YearMonth JUL = YearMonth.of(2026, 7);
    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final YearMonth SEP = YearMonth.of(2026, 9);

    private JdbcTemplate jdbc;
    private PayConfiguration configuration;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        configuration = mock(PayConfiguration.class);
        when(configuration.staffConfiguration(any(LocalDate.class)))
                .thenAnswer(
                        call -> {
                            LocalDate day = call.getArgument(0);
                            BigDecimal ida =
                                    day.getMonthValue() >= 8
                                            ? new BigDecimal("53")
                                            : new BigDecimal("50");
                            return new PayConfiguration.Snapshot(
                                    day,
                                    Map.of("IDA", ida),
                                    Map.of(),
                                    Map.of(),
                                    false,
                                    Map.of(),
                                    Map.of(),
                                    "h");
                        });
    }

    private static Map<String, Object> line(
            String yymm, long component, String paid, String basis) {
        return Map.of(
                "PAYROLL_YYMM",
                yymm,
                "COMPONENT_ID",
                component,
                "AMOUNT",
                new BigDecimal(paid),
                "BASIS_AMOUNT",
                new BigDecimal(basis));
    }

    private DaArrearProcessor processor(YearMonth from, YearMonth to) {
        return new DaArrearProcessor(jdbc, configuration, 41L, from, to);
    }

    private static EmployeeRow member(long id, String pattern) {
        return new EmployeeRow(id, 4L, new BigDecimal("50000"), "N", pattern);
    }

    @Test
    void BR_ARR_DA_002_SRS_8_2_eachMonthsDifferentialUsesTheRateInForceForThatMonth() {
        when(jdbc.queryForList(anyString(), eq(7L), eq("202607"), eq("202609"), eq(111)))
                .thenReturn(
                        List.of(
                                line("202607", 111, "25000", "50000"),
                                line("202608", 111, "25000", "50000"),
                                line("202609", 111, "26500", "50000")));
        DaArrearProcessor p = processor(JUL, SEP);
        DaArrearProcessor.MemberArrear a = p.process(member(7L, "IDA"));
        assertThat(a.eventId()).isEqualTo(41L);
        assertThat(a.employeeId()).isEqualTo(7L);
        // July at 50 % has no differential; August at 53 % has 1500; September was already paid at
        // 53 %.
        assertThat(a.details()).hasSize(1);
        DaArrearProcessor.Detail d = a.details().get(0);
        assertThat(d.yymm()).isEqualTo("202608");
        assertThat(d.periodDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(d.componentId()).isEqualTo(111L);
        assertThat(d.original()).isEqualByComparingTo("25000");
        assertThat(d.recomputed()).isEqualByComparingTo("26500");
        assertThat(d.arrear()).isEqualByComparingTo("1500");
        // The rates of a month are read once, however many members.
        p.process(member(7L, "IDA"));
        verify(configuration, org.mockito.Mockito.times(3))
                .staffConfiguration(any(LocalDate.class));
    }

    @Test
    void BR_ARR_DA_003_aMemberWithoutADifferentialIsFilteredAndMissingMonthsAreCounted() {
        when(jdbc.queryForList(anyString(), eq(8L), anyString(), anyString(), eq(111)))
                .thenReturn(List.of(line("202608", 111, "26500", "50000")));
        DaArrearProcessor p = processor(JUL, SEP);
        assertThat(p.process(member(8L, "IDA"))).isNull();
        when(jdbc.queryForList(anyString(), eq(9L), anyString(), anyString(), eq(111)))
                .thenReturn(List.of());
        assertThat(p.process(member(9L, "IDA"))).isNull();
        StepExecution step =
                MetaDataInstanceFactory.createStepExecution(
                        MetaDataInstanceFactory.createJobExecution("daArrear", 1L, 1L),
                        "daArrearStep",
                        1L);
        step.setReadCount(2);
        step.setWriteCount(0);
        step.setFilterCount(2);
        p.afterStep(step);
        // Member 8: July and September have no ACTUAL result (2); member 9: none of three (3).
        assertThat(step.getExecutionContext().getLong(DaArrearProcessor.MONTHS_WITHOUT_RESULT))
                .isEqualTo(5L);
        assertThat(step.getExecutionContext().getString(BatchKeys.SUMMARY))
                .contains("Arrear event 41")
                .contains("2026-07 to 2026-09")
                .contains("members read 2")
                .contains("without one 2")
                .contains("without an ACTUAL result 5");
    }

    @Test
    void UC_02_E1_aMissingDaRateSkipsTheMemberAsInvalid() {
        when(jdbc.queryForList(anyString(), eq(7L), anyString(), anyString(), eq(111)))
                .thenReturn(List.of(line("202608", 111, "25000", "50000")));
        assertThatThrownBy(() -> processor(AUG, AUG).process(member(7L, "CDA")))
                .isInstanceOf(InvalidRecordException.class)
                .hasMessageContaining("DA rate not seeded for CDA in 2026-08");
        assertThatThrownBy(() -> processor(AUG, AUG).process(member(7L, null)))
                .isInstanceOf(InvalidRecordException.class);
    }

    @Test
    void UC_02_E1_aLineWithoutItsBasisIsInvalidNotGuessed() {
        java.util.HashMap<String, Object> noBasis =
                new java.util.HashMap<>(line("202608", 111, "25000", "1"));
        noBasis.put("BASIS_AMOUNT", null);
        when(jdbc.queryForList(anyString(), eq(7L), anyString(), anyString(), eq(111)))
                .thenReturn(List.of(noBasis));
        assertThatThrownBy(() -> processor(AUG, AUG).process(member(7L, "IDA")))
                .isInstanceOf(InvalidRecordException.class);
    }

    @Test
    void aProcessorWithoutAnEventWritesToNoEvent() {
        when(jdbc.queryForList(anyString(), eq(7L), anyString(), anyString(), eq(111)))
                .thenReturn(List.of(line("202608", 111, "25000", "50000")));
        assertThat(
                        new DaArrearProcessor(jdbc, configuration, null, AUG, AUG)
                                .process(member(7L, "IDA"))
                                .eventId())
                .isEqualTo(-1L);
    }

    // ---------------------------------------------------------------- writer

    @Test
    @SuppressWarnings("unchecked")
    void theWriterInsertsOneArrearDetailPerDifferentialOfTheChunk() {
        ArrearDetailWriter writer = new ArrearDetailWriter(jdbc);
        DaArrearProcessor.Detail aug =
                new DaArrearProcessor.Detail(
                        111,
                        "202608",
                        LocalDate.of(2026, 8, 1),
                        BigDecimal.TEN,
                        new BigDecimal("12"),
                        new BigDecimal("2"));
        DaArrearProcessor.Detail sep =
                new DaArrearProcessor.Detail(
                        111,
                        "202609",
                        LocalDate.of(2026, 9, 1),
                        BigDecimal.TEN,
                        new BigDecimal("13"),
                        new BigDecimal("3"));
        writer.write(
                Chunk.of(
                        new DaArrearProcessor.MemberArrear(41, 7, List.of(aug, sep)),
                        new DaArrearProcessor.MemberArrear(41, 8, List.of(aug))));
        ArgumentCaptor<List<Object[]>> rows = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(contains("INSERT INTO arrear_detail"), rows.capture());
        assertThat(rows.getValue()).hasSize(3);
        assertThat(rows.getValue().get(0))
                .containsExactly(
                        41L,
                        7L,
                        111L,
                        "202608",
                        BigDecimal.TEN,
                        new BigDecimal("12"),
                        new BigDecimal("2"),
                        java.sql.Date.valueOf("2026-08-01"));
        assertThat(rows.getValue().get(2)[1]).isEqualTo(8L);
    }

    @Test
    void anEmptyChunkWritesNothing() {
        new ArrearDetailWriter(jdbc)
                .write(Chunk.of(new DaArrearProcessor.MemberArrear(41, 7, List.of())));
        verify(jdbc, never()).batchUpdate(anyString(), anyList());
    }

    private static String contains(String s) {
        return org.mockito.ArgumentMatchers.contains(s);
    }

    // ---------------------------------------------------------------- event listener

    private static JobExecution execution(BatchStatus status) {
        JobParameters params =
                new JobParametersBuilder()
                        .addLong(BatchKeys.REQUEST_ID, 5L)
                        .addString("locationId", "4")
                        .addString("fromPeriod", "2026-07")
                        .addString("toPeriod", "2026-09")
                        .toJobParameters();
        JobExecution e = MetaDataInstanceFactory.createJobExecution("daArrear", 1L, 1L, params);
        e.setStatus(status);
        return e;
    }

    @Test
    void theEventIsRaisedOncePerExecutionForTheNextOpenPeriod() {
        ArrearEvents events = mock(ArrearEvents.class);
        when(events.raise(5L, 4L, JUL, SEP, YearMonth.of(2026, 10), "DA_REVISION", "DA"))
                .thenReturn(77L);
        ArrearEventListener listener =
                new ArrearEventListener(events, () -> YearMonth.of(2026, 10));
        JobExecution e = execution(BatchStatus.STARTING);
        listener.beforeJob(e);
        assertThat(e.getExecutionContext().getLong(DaArrearJobConfig.EVENT_ID)).isEqualTo(77L);
        // A restart keeps its event.
        listener.beforeJob(e);
        verify(events, org.mockito.Mockito.times(1))
                .raise(anyLong(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void aStoppingExecutionRaisesNoEvent() {
        ArrearEvents events = mock(ArrearEvents.class);
        new ArrearEventListener(events, () -> YearMonth.of(2026, 10))
                .beforeJob(execution(BatchStatus.STOPPING));
        verify(events, never()).raise(anyLong(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void theEventIsComputedOnlyAfterACompletedRunThatRaisedIt() {
        ArrearEvents events = mock(ArrearEvents.class);
        ArrearEventListener listener =
                new ArrearEventListener(events, () -> YearMonth.of(2026, 10));
        JobExecution failed = execution(BatchStatus.FAILED);
        failed.getExecutionContext().putLong(DaArrearJobConfig.EVENT_ID, 77L);
        listener.afterJob(failed);
        verify(events, never()).computed(anyLong());
        listener.afterJob(execution(BatchStatus.COMPLETED));
        verify(events, never()).computed(anyLong());
        JobExecution done = execution(BatchStatus.COMPLETED);
        done.getExecutionContext().putLong(DaArrearJobConfig.EVENT_ID, 77L);
        listener.afterJob(done);
        verify(events).computed(77L);
    }
}
