package in.gov.fci.payroll.platform.logging;

import java.util.List;

/** MDC keys shared by every FCI application. Values are always sanitised before being put. */
public final class MdcKeys {

    public static final String CORRELATION_ID = "correlationId";
    public static final String USER_ID = "userId";
    public static final String SESSION_ID = "sessionId";
    public static final String CLIENT_IP = "clientIp";
    public static final String HTTP_METHOD = "httpMethod";
    public static final String URI = "uri";
    public static final String APP = "app";
    public static final String DURATION_MS = "durationMs";

    /** Keys owned by the FCI filters; trace and span ids are owned by Micrometer Tracing. */
    public static final List<String> REQUEST_KEYS =
            List.of(
                    CORRELATION_ID,
                    USER_ID,
                    SESSION_ID,
                    CLIENT_IP,
                    HTTP_METHOD,
                    URI,
                    APP,
                    DURATION_MS);

    /**
     * HTTP header carrying the correlation id between NGINX, the Next.js server and the backend.
     */
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    /** Request attribute under which the effective correlation id is exposed. */
    public static final String CORRELATION_ATTRIBUTE = MdcKeys.class.getName() + ".correlationId";

    private MdcKeys() {}
}
