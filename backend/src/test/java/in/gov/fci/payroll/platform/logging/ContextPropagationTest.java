package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

// Thread-local logging / security context: never alongside another such test.
@ResourceLock("thread-context")
class ContextPropagationTest {

    @AfterEach
    void clear() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void contextExecutorCarriesMdcAndSecurityContext() throws Exception {
        MDC.put(MdcKeys.CORRELATION_ID, "corr-12345678");
        Authentication auth =
                UsernamePasswordAuthenticationToken.authenticated("u-1", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        AtomicReference<String> correlation = new AtomicReference<>();
        AtomicReference<Authentication> seenAuth = new AtomicReference<>();
        AtomicReference<String> thread = new AtomicReference<>();

        CompletableFuture.runAsync(
                        () -> {
                            correlation.set(MDC.get(MdcKeys.CORRELATION_ID));
                            seenAuth.set(SecurityContextHolder.getContext().getAuthentication());
                            thread.set(Thread.currentThread().getName());
                        },
                        ContextPropagation.contextExecutor("test-", 1))
                .get(5, TimeUnit.SECONDS);

        assertThat(correlation.get()).isEqualTo("corr-12345678");
        assertThat(seenAuth.get()).isSameAs(auth);
        assertThat(thread.get()).as("ran on the executor, not the caller").startsWith("test-");
    }

    @Test
    void restoresWorkerThreadContextAfterTask() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try {
            MDC.put("k", "caller");
            pool.submit(ContextPropagation.wrapCallable(() -> MDC.get("k"))).get();
            MDC.clear();

            String leftover = pool.submit(() -> MDC.get("k")).get();
            assertThat(leftover).isNull();
            String leftoverAuth =
                    pool.submit(
                                    () ->
                                            String.valueOf(
                                                    SecurityContextHolder.getContext()
                                                            .getAuthentication()))
                            .get();
            assertThat(leftoverAuth).isEqualTo("null");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void wrapsSuppliersAndCallables() throws Exception {
        MDC.put("k", "v");
        var supplier = ContextPropagation.wrapSupplier(() -> MDC.get("k"));
        var callable = ContextPropagation.wrapCallable(() -> MDC.get("k"));
        MDC.clear();

        assertThat(supplier.get()).isEqualTo("v");
        assertThat(callable.call()).isEqualTo("v");
        assertThat(MDC.get("k")).isNull();
    }

    @Test
    void propagatesExceptionsUnchanged() {
        var failing =
                ContextPropagation.wrapSupplier(
                        (java.util.function.Supplier<String>)
                                () -> {
                                    throw new IllegalArgumentException("bad");
                                });
        assertThatThrownBy(failing::get).isInstanceOf(IllegalArgumentException.class);

        Runnable decorated = ContextPropagation.taskDecorator().decorate(() -> MDC.put("x", "y"));
        decorated.run();
        assertThat(MDC.get("x")).isNull();
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneaky(Throwable t) throws E {
        throw (E) t;
    }

    @Test
    void aCheckedExceptionFromACallableReachesTheCallerUnchangedAndTheContextIsRestored() {
        MDC.put("k", "submitter");
        var failing =
                ContextPropagation.wrapCallable(
                        () -> {
                            throw new java.io.IOException("disk full");
                        });
        MDC.put("k", "worker");
        assertThatThrownBy(failing::call)
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("disk full");
        assertThat(MDC.get("k")).as("the worker's own context is back").isEqualTo("worker");
    }

    @Test
    void aCheckedExceptionSneakedThroughASupplierIsWrappedAndTheContextIsRestored() {
        MDC.put("k", "submitter");
        var failing =
                ContextPropagation.wrapSupplier(
                        (java.util.function.Supplier<String>)
                                () -> {
                                    ContextPropagationTest.<RuntimeException>sneaky(
                                            new java.io.IOException("reset"));
                                    return "never";
                                });
        MDC.put("k", "worker");
        assertThatThrownBy(failing::get)
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(java.io.IOException.class);
        assertThat(MDC.get("k")).isEqualTo("worker");
    }

    @Test
    void anErrorIsRethrownAsItIsNeverWrapped() {
        var failing =
                ContextPropagation.wrapSupplier(
                        (java.util.function.Supplier<String>)
                                () -> {
                                    throw new AssertionError("fatal");
                                });
        assertThatThrownBy(failing::get).isInstanceOf(AssertionError.class).hasMessage("fatal");
    }

    @Test
    void aTaskSubmittedWithNoMdcRunsWithAnEmptyMdcEvenWhereTheWorkerHasOne() {
        MDC.clear();
        Runnable task =
                ContextPropagation.wrap(
                        () -> assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty());
        MDC.put("worker-key", "worker-value");
        task.run();
        assertThat(MDC.get("worker-key")).as("restored afterwards").isEqualTo("worker-value");
    }

    @Test
    void aTaskSubmittedWithoutAnAuthenticationRunsAnonymouslyAndTheWorkersIsRestored() {
        Runnable task =
                ContextPropagation.wrap(
                        () ->
                                assertThat(SecurityContextHolder.getContext().getAuthentication())
                                        .isNull());
        Authentication workers =
                UsernamePasswordAuthenticationToken.authenticated("worker", null, List.of());
        org.springframework.security.core.context.SecurityContext own =
                new org.springframework.security.core.context.SecurityContextImpl(workers);
        // the snapshot was taken with the (empty) context of the submitting thread; the worker
        // has meanwhile authenticated in a context of its own
        SecurityContextHolder.setContext(own);
        task.run();
        assertThat(SecurityContextHolder.getContext()).isSameAs(own);
    }
}
