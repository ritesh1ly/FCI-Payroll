package in.gov.fci.payroll.integration.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.HrmsGateway;
import in.gov.fci.payroll.integration.api.HrmsReply;
import in.gov.fci.payroll.integration.business.IntegrationErrorCode;
import in.gov.fci.payroll.integration.config.HrmsConsoleProperties;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.SensitiveDataMasker;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * X-03 HRMS API console (SRS-INT-33, SRS-INT-6, R-AUD, R-PII; DDL audit_log): the Administrator
 * (X-03 EDIT) calls one of the HRMS APIs Payroll is configured to CALL through the {@link
 * HrmsGateway}, so every outbound API can be exercised from the screen. Off unless {@code
 * fci.integration.hrms.console.enabled} is true. Every call is audited with the reason, the
 * outcome, the HTTP status and HRMS's error code - never the query, the body or the answer's data.
 * The answer's data is returned masked and cut at {@link #DATA_LIMIT} characters.
 */
@Service
public class HrmsConsoleService {

    static final String SCREEN = "X-03";

    /** Longest data text returned. */
    public static final int DATA_LIMIT = 4000;

    /**
     * The call.
     *
     * @param query URL parameters, at most 20
     * @param body the JSON body, any shape
     * @param reason why the Administrator calls (mandatory)
     */
    public record CallRequest(
            @Size(max = 20)
                    Map<
                                    @PrintableAscii @Size(min = 1, max = 60) String,
                                    @PrintableAscii @Size(max = 200) String>
                            query,
            JsonNode body,
            @NotBlank @Size(max = 500) @PrintableAscii String reason) {}

    /** What HRMS answered, as the console shows it. */
    public record CallResult(
            String code,
            String method,
            HrmsReply.Outcome outcome,
            int httpStatus,
            String errorCode,
            String message,
            List<String> problems,
            boolean duplicate,
            int attempts,
            long elapsedMillis,
            String data) {}

    private final HrmsGateway gateway;
    private final HrmsConsoleProperties settings;
    private final Authorization policy;
    private final AuditService audit;

    public HrmsConsoleService(
            HrmsGateway gateway,
            HrmsConsoleProperties settings,
            Authorization policy,
            AuditService audit) {
        this.gateway = gateway;
        this.settings = settings;
        this.policy = policy;
        this.audit = audit;
    }

    /** Calls the API {@code code} (HrmsApi.code()) and audits the call. */
    @Transactional
    public CallResult call(AccessContext context, String code, CallRequest request) {
        policy.require(context, SCREEN, "EDIT");
        if (!settings.on()) {
            throw new FciException(IntegrationErrorCode.CONSOLE_DISABLED);
        }
        HrmsApi api =
                Arrays.stream(HrmsApi.values())
                        .filter(a -> a.code().equals(code))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new FciException(
                                                IntegrationErrorCode.UNKNOWN_INTERFACE,
                                                new Object[] {code},
                                                List.of(),
                                                null));
        JsonNode body = request.body() == null || request.body().isNull() ? null : request.body();
        long started = System.nanoTime();
        HrmsReply reply;
        try {
            reply = gateway.call(api, request.query(), body, null);
        } catch (RuntimeException failure) {
            // The transaction rolls back with the failure, so the attempt is written on its own.
            audit.recordIndependently(
                    context.loginId(), entry(code, "EXCEPTION " + failureCode(failure), request));
            throw failure;
        }
        long elapsed = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        audit.record(
                context.loginId(),
                entry(
                        code,
                        reply.outcome() + " " + reply.httpStatus() + " " + reply.errorCode(),
                        request));
        return new CallResult(
                api.code(),
                api.method(),
                reply.outcome(),
                reply.httpStatus(),
                reply.errorCode(),
                reply.message(),
                reply.problems(),
                reply.duplicate(),
                reply.attempts(),
                elapsed,
                data(reply.data()));
    }

    private static String failureCode(RuntimeException failure) {
        return failure instanceof FciException f
                ? f.getErrorCode().code()
                : failure.getClass().getSimpleName();
    }

    private static AuditService.Entry entry(String code, String outcome, CallRequest request) {
        return AuditService.Entry.change(
                        "hrms_api_call", code, "call", null, outcome, request.reason())
                .module(SCREEN);
    }

    /** The data as JSON text: masked (R-PII), then cut at {@link #DATA_LIMIT}. */
    static String data(JsonNode data) {
        if (data == null) {
            return null;
        }
        String text = SensitiveDataMasker.mask(data.toString());
        return text.length() <= DATA_LIMIT ? text : text.substring(0, DATA_LIMIT);
    }
}
