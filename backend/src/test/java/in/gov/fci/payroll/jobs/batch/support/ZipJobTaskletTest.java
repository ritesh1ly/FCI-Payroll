package in.gov.fci.payroll.jobs.batch.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.export.ZipBundle;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.test.MetaDataInstanceFactory;

/**
 * OD-149, SRS-NF-2, SRS-NF-7: a bulk job writes its ZIP {@value ZipJobTasklet#BATCH} documents a
 * call (progress), leaves a failed member out and lists it (skipped, the file still made), refuses
 * when its plan refuses (a month not locked), and a restart makes the same file again.
 */
class ZipJobTaskletTest {

    /** A plan of {@code n} members, those in {@code failing} failing to render. */
    private static final class Plan implements ZipJobTasklet.Plan<Integer> {
        final int n;
        final List<Integer> failing;
        final List<byte[]> stored = new ArrayList<>();
        int written = -1;
        int leftOut = -1;
        boolean locked = true;

        Plan(int n, Integer... failing) {
            this.n = n;
            this.failing = List.of(failing);
        }

        @Override
        public List<Integer> items() {
            if (!locked) {
                throw new IllegalStateException("SRS l.3389: the month is not locked");
            }
            return IntStream.range(0, n).boxed().toList();
        }

        @Override
        public LocalDateTime entryTime() {
            return LocalDateTime.of(2026, 9, 1, 0, 0);
        }

        @Override
        public String name(Integer i) {
            return String.format("payslip-%04d-202609.pdf", i);
        }

        @Override
        public byte[] render(Integer i) {
            if (failing.contains(i)) {
                throw new IllegalStateException("render");
            }
            return ("pdf" + i).getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public void store(byte[] zip, int written, int leftOut) {
            stored.add(zip);
            this.written = written;
            this.leftOut = leftOut;
        }
    }

    private static StepExecution step() {
        return MetaDataInstanceFactory.createStepExecution(
                MetaDataInstanceFactory.createJobExecution("payslipBulk", 1L, 1L),
                "payslipBulkStep",
                1L);
    }

    /** Runs the tasklet to its end; returns the calls it took. */
    private static int run(ZipJobTasklet<Integer> tasklet, StepExecution step) throws Exception {
        ChunkContext chunk = new ChunkContext(new StepContext(step));
        int calls = 0;
        RepeatStatus status;
        do {
            StepContribution c = step.createStepContribution();
            status = tasklet.execute(c, chunk);
            step.apply(c);
            calls++;
        } while (status == RepeatStatus.CONTINUABLE);
        tasklet.afterStep(step);
        return calls;
    }

    private static List<String> names(byte[] zip) throws Exception {
        List<String> out = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                out.add(e.getName());
            }
        }
        return out;
    }

    @Test
    void SRS_NF_2_theFileIsWrittenFiftyAtATimeAndProgressIsCounted() throws Exception {
        Plan plan = new Plan(120);
        StepExecution step = step();
        assertThat(run(new ZipJobTasklet<>(plan), step)).isEqualTo(3);
        assertThat(step.getReadCount()).isEqualTo(120);
        assertThat(step.getWriteCount()).isEqualTo(120);
        assertThat(plan.stored).hasSize(1);
        assertThat(names(plan.stored.getFirst()))
                .hasSize(120)
                .startsWith("payslip-0000-202609.pdf")
                .endsWith("payslip-0119-202609.pdf");
        assertThat(step.getExecutionContext().getString(BatchKeys.SUMMARY))
                .isEqualTo("documents 120, in the file 120, left out 0");
    }

    @Test
    void SRS_NF_7_aFailedMemberIsLeftOutListedAndCountedAsSkipped() throws Exception {
        Plan plan = new Plan(3, 1);
        StepExecution step = step();
        run(new ZipJobTasklet<>(plan), step);
        assertThat(names(plan.stored.getFirst()))
                .containsExactly(
                        "payslip-0000-202609.pdf", "payslip-0002-202609.pdf", ZipBundle.MANIFEST);
        assertThat(plan.written).isEqualTo(2);
        assertThat(plan.leftOut).isEqualTo(1);
        assertThat(step.getProcessSkipCount()).isEqualTo(1);
        assertThat(step.getWriteCount()).isEqualTo(2);
    }

    @Test
    void SRS_l3389_aPlanThatRefusesFailsTheStepAndStoresNothing() {
        Plan plan = new Plan(3);
        plan.locked = false;
        StepExecution step = step();
        ZipJobTasklet<Integer> tasklet = new ZipJobTasklet<>(plan);
        assertThatThrownBy(
                        () ->
                                tasklet.execute(
                                        step.createStepContribution(),
                                        new ChunkContext(new StepContext(step))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not locked");
        tasklet.afterStep(step);
        assertThat(plan.stored).isEmpty();
    }

    @Test
    void SRS_NF_7_aRestartMakesTheSameFileAgainNothingRepeatedOrLost() throws Exception {
        Plan first = new Plan(75, 10);
        run(new ZipJobTasklet<>(first), step());
        Plan again = new Plan(75, 10);
        run(new ZipJobTasklet<>(again), step());
        assertThat(again.stored.getFirst()).isEqualTo(first.stored.getFirst());
        assertThat(names(again.stored.getFirst())).hasSize(75).doesNotHaveDuplicates();
    }

    @Test
    void OD_149_anEmptyUnitGivesAnEmptyFile() throws Exception {
        Plan plan = new Plan(0);
        StepExecution step = step();
        assertThat(run(new ZipJobTasklet<>(plan), step)).isEqualTo(1);
        assertThat(names(plan.stored.getFirst())).isEmpty();
        assertThat(plan.written).isZero();
    }

    @Test
    void OD_149_afterStepWithoutARunDoesNothing() {
        StepExecution step = step();
        assertThat(new ZipJobTasklet<>(new Plan(1)).afterStep(step))
                .isEqualTo(step.getExitStatus());
    }
}
