package in.gov.fci.payroll.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.platform.error.ProblemResponder;
import in.gov.fci.payroll.platform.export.ExportProperties;
import in.gov.fci.payroll.platform.idempotency.IdempotencyFilter;
import in.gov.fci.payroll.platform.idempotency.IdempotencyService;
import in.gov.fci.payroll.platform.security.CurrentUser;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

/** Web configuration, response helpers and the export pool: gaps found by mutation testing. */
@ResourceLock("thread-context")
class WebMutationGapsTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void theIdempotencyFilterRunsAfterSecurityUnderItsOwnName() {
        FilterRegistrationBean<IdempotencyFilter> registration =
                new WebConfig()
                        .idempotencyFilter(
                                mock(IdempotencyService.class),
                                mock(CurrentUser.class),
                                mock(ProblemResponder.class));
        assertThat(registration).isNotNull();
        assertThat(registration.getFilter()).isInstanceOf(IdempotencyFilter.class);
        assertThat(registration.getOrder()).isEqualTo(-98);
        assertThat(registration.getFilterName()).isEqualTo("fciIdempotencyFilter");
    }

    @Test
    void noStoreAnswers200WithNoStoreAndTheBody() {
        ResponseEntity<String> response = Responses.noStore("personal");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("personal");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void aMatchingEtagIsAnswered304WithoutABodyAndWithoutLoadingTheData() {
        AtomicInteger loads = new AtomicInteger();
        ResponseEntity<String> response =
                Responses.revalidated(
                        "\"v7\"",
                        "\"v7\"",
                        () -> {
                            loads.incrementAndGet();
                            return "data";
                        });
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getETag()).isEqualTo("\"v7\"");
        assertThat(response.getHeaders().getCacheControl()).contains("no-cache", "private");
        assertThat(loads).hasValue(0);
    }

    @Test
    void aDifferentOrMissingEtagIsAnswered200WithTheDataAndTheNewTag() {
        for (String presented : new String[] {"\"v6\"", null}) {
            ResponseEntity<String> response =
                    Responses.revalidated("\"v7\"", presented, () -> "data");
            assertThat(response).isNotNull();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("data");
            HttpHeaders headers = response.getHeaders();
            assertThat(headers.getETag()).isEqualTo("\"v7\"");
            assertThat(headers.getCacheControl()).contains("no-cache", "private");
        }
    }

    @Test
    void theExportPoolIsBoundedNamedAndDrainedOnShutdown() {
        ExportProperties properties = new ExportProperties(3, 7, Duration.ofMinutes(10), 1000, 50);
        ThreadPoolTaskExecutor executor = new ExportAsyncConfig.Pool().exportExecutor(properties);
        assertThat(executor).isNotNull();
        assertThat(executor.getThreadNamePrefix()).isEqualTo("export-");
        assertThat(executor.getCorePoolSize()).isEqualTo(3);
        assertThat(executor.getMaxPoolSize()).isEqualTo(3);
        assertThat(executor.getQueueCapacity()).isEqualTo(7);
        assertThat(ReflectionTestUtils.getField(executor, "taskDecorator")).isNotNull();
        assertThat(ReflectionTestUtils.getField(executor, "waitForTasksToCompleteOnShutdown"))
                .isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(executor, "awaitTerminationMillis"))
                .isEqualTo(20_000L);
    }

    @Test
    void theExportPoolCarriesTheLogContextOntoTheExportThread() throws Exception {
        ThreadPoolTaskExecutor executor =
                new ExportAsyncConfig.Pool()
                        .exportExecutor(
                                new ExportProperties(1, 1, Duration.ofMinutes(10), 1000, 50));
        executor.initialize();
        try {
            MDC.put("correlationId", "corr-export-0001");
            AtomicReference<String> seen = new AtomicReference<>();
            executor.submit(() -> seen.set(MDC.get("correlationId"))).get();
            assertThat(seen.get()).isEqualTo("corr-export-0001");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void theLogContextOfTheExportThreadIsRestoredAfterTheTask() {
        MDC.put("a", "1");
        Runnable decorated = ExportAsyncConfig.withLogContext().decorate(() -> {});
        MDC.clear();
        MDC.put("b", "2");
        AtomicReference<String> inside = new AtomicReference<>();
        Runnable probe =
                ExportAsyncConfig.withLogContext()
                        .decorate(() -> inside.set(MDC.get("a") + "/" + MDC.get("b")));
        decorated.run();
        assertThat(MDC.get("b")).isEqualTo("2");
        assertThat(MDC.get("a")).isNull();
        probe.run();
        assertThat(inside.get()).isEqualTo("null/2");
        assertThat(MDC.get("b")).isEqualTo("2");
    }

    @Test
    void aTaskDecoratedWithoutAnyContextRunsWithoutOneAndLeavesNoneBehind() {
        MDC.clear();
        AtomicReference<String> inside = new AtomicReference<>("unset");
        Runnable decorated =
                ExportAsyncConfig.withLogContext().decorate(() -> inside.set(MDC.get("x")));
        MDC.put("x", "leftover");
        decorated.run();
        assertThat(inside.get()).isNull();
        assertThat(MDC.get("x")).isEqualTo("leftover");
    }
}
