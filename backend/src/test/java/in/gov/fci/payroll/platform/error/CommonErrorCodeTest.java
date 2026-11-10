package in.gov.fci.payroll.platform.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SRS-EH-2 (every message has a stable catalogued code), SRS-EH-3 (never a technical exception): an
 * error raised by the framework itself is answered with the catalogued code closest to its HTTP
 * status, never a raw status page; an unknown client status reads as a malformed request and any
 * server status as an internal error.
 */
class CommonErrorCodeTest {

    @Test
    void SRS_EH_2_aFrameworkStatusMapsToItsCataloguedError() {
        Map<Integer, CommonErrorCode> expected =
                Map.ofEntries(
                        Map.entry(400, CommonErrorCode.MALFORMED_REQUEST),
                        Map.entry(401, CommonErrorCode.AUTHENTICATION_REQUIRED),
                        Map.entry(403, CommonErrorCode.ACCESS_DENIED),
                        Map.entry(404, CommonErrorCode.NOT_FOUND),
                        Map.entry(405, CommonErrorCode.METHOD_NOT_ALLOWED),
                        Map.entry(406, CommonErrorCode.NOT_ACCEPTABLE),
                        Map.entry(409, CommonErrorCode.CONFLICT),
                        Map.entry(412, CommonErrorCode.PRECONDITION_FAILED),
                        Map.entry(413, CommonErrorCode.PAYLOAD_TOO_LARGE),
                        Map.entry(415, CommonErrorCode.UNSUPPORTED_MEDIA_TYPE),
                        Map.entry(428, CommonErrorCode.PRECONDITION_REQUIRED),
                        Map.entry(429, CommonErrorCode.TOO_MANY_REQUESTS),
                        Map.entry(503, CommonErrorCode.SERVICE_UNAVAILABLE),
                        Map.entry(418, CommonErrorCode.MALFORMED_REQUEST),
                        Map.entry(500, CommonErrorCode.INTERNAL_ERROR),
                        Map.entry(502, CommonErrorCode.INTERNAL_ERROR));
        expected.forEach(
                (status, code) ->
                        assertThat(CommonErrorCode.forStatus(status))
                                .as("HTTP " + status)
                                .isEqualTo(code));
    }
}
