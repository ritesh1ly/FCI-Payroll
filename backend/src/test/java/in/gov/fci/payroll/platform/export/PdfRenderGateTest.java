package in.gov.fci.payroll.platform.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * OD-144, performance SLA (1,000 concurrent payslip downloads on salary day): the PDF bulkhead
 * renders at most {@code max-concurrent} documents at once, refuses the rest after {@code max-wait}
 * with 503 and Retry-After, keeps no slot after a failure, and audits a render failure.
 */
class PdfRenderGateTest {

    private final List<Object> published = new ArrayList<>();

    private PdfRenderGate gate(int permits, Duration wait) {
        return new PdfRenderGate(permits, wait, Duration.ofSeconds(7), published::add);
    }

    @Test
    void OD_144_neverMoreThanMaxConcurrentRenderAtOnceAndTheRestAreRefusedWithRetryAfter()
            throws Exception {
        PdfRenderGate gate = gate(4, Duration.ofMillis(100));
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        int callers = 40;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    try {
                                        gate.render(
                                                "doc",
                                                () -> {
                                                    int now = running.incrementAndGet();
                                                    peak.accumulateAndGet(now, Math::max);
                                                    try {
                                                        release.await(5, TimeUnit.SECONDS);
                                                    } catch (InterruptedException e) {
                                                        Thread.currentThread().interrupt();
                                                    }
                                                    running.decrementAndGet();
                                                    return new byte[] {1};
                                                });
                                        return "ok";
                                    } catch (PdfRenderGate.Busy busy) {
                                        assertThat(busy.retryAfterSeconds()).isEqualTo(7);
                                        assertThat(busy.getErrorCode())
                                                .isEqualTo(CommonErrorCode.EXPORT_BUSY);
                                        return "busy";
                                    }
                                }));
            }
            // Let the waiting callers time out while four renders hold their slots.
            Thread.sleep(400);
            release.countDown();
            int ok = 0;
            int busy = 0;
            for (Future<String> r : results) {
                if ("ok".equals(r.get(10, TimeUnit.SECONDS))) {
                    ok++;
                } else {
                    busy++;
                }
            }
            assertThat(peak.get()).isLessThanOrEqualTo(4);
            assertThat(ok).isGreaterThanOrEqualTo(4);
            assertThat(busy).isPositive();
            assertThat(ok + busy).isEqualTo(callers);
            assertThat(gate.available()).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void OD_144_aFailedRenderFreesItsSlotIsAuditedAndAnsweredAsAnInternalError() {
        PdfRenderGate gate = gate(1, Duration.ofMillis(10));
        assertThatThrownBy(
                        () ->
                                gate.render(
                                        "payslip-202609",
                                        () -> {
                                            throw new IllegalStateException("font");
                                        }))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(CommonErrorCode.INTERNAL_ERROR));
        assertThat(published)
                .containsExactly(
                        new ExportFailed(
                                "payslip-202609", ExportFormat.PDF, "IllegalStateException"));
        assertThat(gate.available()).isEqualTo(1);
        assertThat(gate.render("again", () -> new byte[] {2})).containsExactly(2);
    }

    @Test
    void OD_144_aRefusalOfTheReadPassesThroughUnchangedAndIsNotAudited() {
        PdfRenderGate gate = gate(1, Duration.ofMillis(10));
        assertThatThrownBy(
                        () ->
                                gate.render(
                                        "x",
                                        () -> {
                                            throw new FciException(CommonErrorCode.NOT_FOUND);
                                        }))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
        assertThat(published).isEmpty();
        assertThat(gate.available()).isEqualTo(1);
    }

    @Test
    void OD_144_anInterruptedWaitIsBusyAndKeepsTheInterrupt() {
        PdfRenderGate gate = gate(1, Duration.ofSeconds(5));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> gate.render("x", () -> new byte[0]))
                    .isInstanceOf(PdfRenderGate.Busy.class);
            assertThat(Thread.interrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void OD_144_atLeastOneSlotAndARetryAfterOfAtLeastOneSecond() {
        assertThatThrownBy(() -> new PdfRenderGate(0, Duration.ZERO, Duration.ZERO, e -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        PdfRenderGate zero = new PdfRenderGate(1, Duration.ZERO, Duration.ZERO, e -> {});
        CountDownLatch hold = new CountDownLatch(1);
        Thread holder =
                new Thread(
                        () ->
                                zero.render(
                                        "h",
                                        () -> {
                                            try {
                                                hold.await(5, TimeUnit.SECONDS);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            return new byte[0];
                                        }));
        holder.start();
        try {
            while (zero.available() > 0) {
                Thread.onSpinWait();
            }
            assertThatThrownBy(() -> zero.render("x", () -> new byte[0]))
                    .isInstanceOfSatisfying(
                            PdfRenderGate.Busy.class,
                            b -> assertThat(b.retryAfterSeconds()).isEqualTo(1));
        } finally {
            hold.countDown();
        }
    }
}
