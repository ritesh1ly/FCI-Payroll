package in.gov.fci.payroll.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportDelivered;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.export.ExportProperties;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.security.ScopeType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** SRS-G-13: the size check, the download headers and the streamed body of every export. */
class ExportStreamingTest {

    private final List<Object> published = new ArrayList<>();

    private final ExportStreaming exports =
            new ExportStreaming(
                    new ExportProperties(4, 20, Duration.ofMinutes(10), 3, 10),
                    published::add,
                    new StaticListableBeanFactory().getBeanProvider(CurrentAccess.class));

    @Test
    void SRS_G_13_exactlyTheLimitDownloadsOneMoreIsRefusedWithBothNumbers() {
        exports.checkSize(0);
        exports.checkSize(3);
        assertThatThrownBy(() -> exports.checkSize(4))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(CommonErrorCode.EXPORT_TOO_LARGE);
                            assertThat(e.getMessageArgs()).containsExactly(4L, 3);
                        });
    }

    @Test
    void SRS_G_13_csvDownloadIsNamedNeverCachedAndWrittenByTheJob() throws IOException {
        ResponseEntity<StreamingResponseBody> response =
                exports.stream(
                        "worklist",
                        ExportFormat.CSV,
                        sink -> {
                            sink.header("A");
                            sink.row("1");
                        });
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"worklist.csv\"");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("text/csv;charset=UTF-8");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("﻿\"A\"\r\n\"1\"\r\n");
    }

    @Test
    void SRS_G_13_excelAndTheDefaultFormat() throws IOException {
        ResponseEntity<StreamingResponseBody> xlsx =
                exports.stream("worklist", ExportFormat.XLSX, sink -> sink.header("A"));
        assertThat(xlsx.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("worklist.xlsx");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        xlsx.getBody().writeTo(out);
        // An .xlsx file is a zip archive.
        assertThat(out.toByteArray()).startsWith((byte) 'P', (byte) 'K');
        assertThat(
                        exports.stream("x", null, sink -> sink.header("A"))
                                .getHeaders()
                                .getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("x.csv");
    }

    @Test
    void SRS_G_13_aFailureWhileStreamingAbortsTheDownload() {
        ResponseEntity<StreamingResponseBody> response =
                exports.stream(
                        "x",
                        ExportFormat.CSV,
                        sink -> {
                            sink.header("A");
                            throw new IOException("database went away");
                        });
        assertThatThrownBy(() -> response.getBody().writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void SRS_20_24_aCompleteExportIsLoggedWithItsRowsAndAFailedOneIsNot() throws IOException {
        exports.stream(
                        "worklist",
                        ExportFormat.XLSX,
                        sink -> {
                            sink.header("A");
                            sink.row("1");
                            sink.row("2");
                        })
                .getBody()
                .writeTo(new ByteArrayOutputStream());
        assertThat(published)
                .containsExactly(
                        new ExportDelivered(
                                "worklist", ExportFormat.XLSX, 2, null, null, null, null));

        published.clear();
        ResponseEntity<StreamingResponseBody> failing =
                exports.stream(
                        "x",
                        ExportFormat.CSV,
                        sink -> {
                            throw new IOException("database went away");
                        });
        assertThatThrownBy(() -> failing.getBody().writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class);
        assertThat(published).isEmpty();
    }

    @Test
    void SRS_20_24_theRequesterAndTheScopeAreTakenOnTheRequestThread() throws IOException {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        AccessContext requester =
                new AccessContext(
                        7L,
                        "maker@fci-demo.test",
                        "Maker",
                        "s",
                        "P",
                        42L,
                        null,
                        List.of(group("AG-1"), group("AG-2")),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(42L));
        beans.addBean(
                "access",
                new CurrentAccess() {
                    @Override
                    public AccessContext current() {
                        return requester;
                    }

                    @Override
                    public LocalDate today() {
                        return LocalDate.of(2026, 10, 22);
                    }
                });
        ExportStreaming signedIn =
                new ExportStreaming(
                        new ExportProperties(4, 20, Duration.ofMinutes(10), 3, 10),
                        published::add,
                        beans.getBeanProvider(CurrentAccess.class));
        signedIn.stream("audit-trail", ExportFormat.CSV, sink -> sink.header("A"))
                .getBody()
                .writeTo(new ByteArrayOutputStream());
        assertThat(published)
                .containsExactly(
                        new ExportDelivered(
                                "audit-trail",
                                ExportFormat.CSV,
                                0,
                                "maker@fci-demo.test",
                                42L,
                                "AG-1,AG-2",
                                null));
    }

    @Test
    void SRS_22_29_theDownloadIsLoggedWithTheRequestParametersSortedAndMasked() throws IOException {
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/v1/x/export");
        request.addParameter("status", "ACTIVE");
        request.addParameter("office", "DH001", "KL001");
        request.addParameter("q", "DMOPO7101K");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            exports.stream("register", ExportFormat.CSV, sink -> sink.header("A"))
                    .getBody()
                    .writeTo(new ByteArrayOutputStream());
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
        assertThat(published)
                .singleElement()
                .isInstanceOfSatisfying(
                        ExportDelivered.class,
                        d -> {
                            assertThat(d.parameters())
                                    .startsWith("office=DH001,KL001&q=")
                                    .endsWith("&status=ACTIVE")
                                    .doesNotContain("DMOPO7101K");
                        });
        assertThat(ExportStreaming.parameters(Map.of())).isEmpty();
    }

    @Test
    void SRS_20_24_aRequestWithNoSessionIsStillExportedAndLoggedWithoutARequester()
            throws IOException {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean(
                "access",
                new CurrentAccess() {
                    @Override
                    public AccessContext current() {
                        throw new FciException(CommonErrorCode.AUTHENTICATION_REQUIRED);
                    }

                    @Override
                    public LocalDate today() {
                        return LocalDate.of(2026, 10, 22);
                    }
                });
        ExportStreaming anonymous =
                new ExportStreaming(
                        new ExportProperties(4, 20, Duration.ofMinutes(10), 3, 10),
                        published::add,
                        beans.getBeanProvider(CurrentAccess.class));
        anonymous.stream("register", ExportFormat.CSV, sink -> sink.header("A"))
                .getBody()
                .writeTo(new ByteArrayOutputStream());
        assertThat(published)
                .containsExactly(
                        new ExportDelivered(
                                "register", ExportFormat.CSV, 0, null, null, null, null));
    }

    @Test
    void SRS_G_13_aListOfNegativeRowsIsNotTooLargeAndTheLimitIsNeverExceededByOne() {
        exports.checkSize(-1);
        exports.checkSize(Long.MIN_VALUE);
        assertThatThrownBy(() -> exports.checkSize(Long.MAX_VALUE))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(CommonErrorCode.EXPORT_TOO_LARGE));
    }

    @Test
    void SRS_22_29_parametersOfARequestWithoutAnyAreAnEmptyTextNotNull() {
        assertThat(ExportStreaming.parameters(Map.of("a", new String[] {}))).isEqualTo("a=");
    }

    @Test
    void SRS_22_29_parametersAreCappedAtTheLimitAndKeptAtIt() {
        String exact = "x".repeat(ExportStreaming.MAX_PARAMETERS - 2);
        assertThat(ExportStreaming.parameters(Map.of("a", new String[] {exact})))
                .hasSize(ExportStreaming.MAX_PARAMETERS);
        assertThat(ExportStreaming.parameters(Map.of("a", new String[] {exact + "y"})))
                .hasSize(ExportStreaming.MAX_PARAMETERS)
                .endsWith("x");
    }

    @Test
    void SRS_20_24_anExportWhoseLogCannotBeWrittenIsAborted() {
        ExportStreaming unlogged =
                new ExportStreaming(
                        new ExportProperties(4, 20, Duration.ofMinutes(10), 3, 10),
                        event -> {
                            throw new IllegalStateException("audit store down");
                        },
                        new StaticListableBeanFactory().getBeanProvider(CurrentAccess.class));
        ResponseEntity<StreamingResponseBody> response =
                unlogged.stream("x", ExportFormat.CSV, sink -> sink.header("A"));
        assertThatThrownBy(() -> response.getBody().writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ActiveGroup group(String code) {
        return new ActiveGroup(
                1L,
                code,
                code,
                1L,
                "PAYROLL_MAKER",
                "Maker",
                ScopeType.DIVISION,
                false,
                Set.of(),
                Set.of(),
                AccessMode.FULL);
    }

    @Test
    void theExportThreadCarriesTheRequestsLogContextAndRestoresItsOwn() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        MDC.put("correlationId", "c-42");
        Runnable decorated =
                ExportAsyncConfig.withLogContext()
                        .decorate(() -> seen.set(MDC.get("correlationId")));
        MDC.clear();
        Thread exportThread =
                new Thread(
                        () -> {
                            MDC.put("correlationId", "own");
                            decorated.run();
                            assertThat(MDC.get("correlationId")).isEqualTo("own");
                        });
        exportThread.start();
        exportThread.join();
        assertThat(seen).hasValue("c-42");
        // Without a request context the export thread runs with none, and keeps none.
        Runnable bare =
                ExportAsyncConfig.withLogContext()
                        .decorate(() -> seen.set(MDC.get("correlationId")));
        bare.run();
        assertThat(seen.get()).isNull();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
