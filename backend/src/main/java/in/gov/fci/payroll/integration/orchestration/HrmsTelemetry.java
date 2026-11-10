package in.gov.fci.payroll.integration.orchestration;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import in.gov.fci.payroll.integration.config.HrmsLogProperties;
import in.gov.fci.payroll.platform.logging.ExchangeLog;
import in.gov.fci.payroll.platform.logging.MaskingPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpMethod;

/**
 * Telemetry of Payroll's calls to HRMS (SRS-INT-6, R-INT, R-PII): the HRMS settings on top of the
 * common {@link ExchangeLog} of the platform (one structured event per attempt on logger {@code
 * FCI_HRMS_EXCHANGE}, metrics {@code fci.hrms.client.*}, bodies masked by the configured {@link
 * MaskingPolicy}). The token call never shows a body. Counters: {@code retries} and {@code
 * bulkhead.refused} (tag api) and {@code token} (tag result: fetched, renewed, refused).
 */
final class HrmsTelemetry {

    /** Which call an attempt belongs to. */
    record Ctx(String interfaceCode, String api, int attempt) {}

    private final ExchangeLog log;
    private final boolean allowUnmasked;

    /**
     * @param allowUnmasked whether {@code mask-data: N} is honoured; where it is not (anything but
     *     dev, local and demo), masking stays on whatever the setting says
     */
    HrmsTelemetry(
            HrmsLogProperties settings,
            MaskingPolicy policy,
            MeterRegistry registry,
            boolean allowUnmasked) {
        this.allowUnmasked = allowUnmasked;
        this.log =
                new ExchangeLog(
                        "FCI_HRMS_EXCHANGE",
                        "hrms",
                        "fci.hrms.client",
                        settings.logBodies(),
                        settings.maxBody(),
                        policy,
                        registry,
                        Set.of(HrmsApi.TOKEN.code()));
    }

    /** Records one attempt (a reply, or a failure with or without a reply). */
    void exchange(
            Ctx ctx,
            HttpMethod method,
            URI uri,
            long millis,
            String requestBody,
            HrmsClient.Response answered,
            String failure,
            HrmsApiProperties.Resolved policy) {
        log.exchange(
                new ExchangeLog.Call(ctx.interfaceCode(), ctx.api(), ctx.attempt()),
                method.name(),
                uri,
                millis,
                requestBody,
                answered == null ? null : answered.status(),
                answered == null ? null : answered.body(),
                failure,
                new ExchangeLog.Options(
                        policy.logRequest(),
                        policy.logResponse(),
                        policy.maskData() || !allowUnmasked));
    }

    /** A retry of {@code api} is about to happen. */
    void retry(String api) {
        log.count("retries", "api", api);
    }

    /** The bulkhead of {@code api} refused a call. */
    void bulkheadRefused(String api) {
        log.count("bulkhead.refused", "api", api);
    }

    /** The token was fetched, renewed after HRMS refused it, or its request was refused. */
    void token(String result) {
        log.count("token", "result", result);
    }
}
