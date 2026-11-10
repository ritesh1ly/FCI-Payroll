package in.gov.fci.payroll.platform.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import in.gov.fci.payroll.platform.logging.LogCapture;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** Kills the PIT survivor of {@link ProblemResponder#write}: a filter-written problem is logged. */
class ProblemResponderMutationGapsTest {

    private final ProblemResponder responder =
            new ProblemResponder(
                    new ProblemDetailFactory("https://errors.fci.gov.in/", null, Clock.systemUTC()),
                    new ProblemDetailWriter(new JsonMapper()));

    @Test
    void SRS_EH_1_aFilterWrittenProblemIsLoggedOnceWithItsCodeAndStatus() throws Exception {
        try (LogCapture capture = LogCapture.ofThisThread(ProblemResponder.class, Level.INFO)) {
            responder.write(
                    null,
                    CommonErrorCode.PAYLOAD_TOO_LARGE,
                    new MockHttpServletRequest("POST", "/v1/x"),
                    new MockHttpServletResponse());
            assertThat(capture.events()).hasSize(1);
            ILoggingEvent event = capture.events().get(0);
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .contains("errorCode=" + CommonErrorCode.PAYLOAD_TOO_LARGE.code())
                    .contains("status=413")
                    .contains("exception=none");
        }
    }

    @Test
    void SRS_EH_1_aForbiddenIsLoggedAtWarnAndAServerErrorAtErrorWithTheException()
            throws Exception {
        try (LogCapture capture = LogCapture.ofThisThread(ProblemResponder.class, Level.INFO)) {
            responder.write(
                    null,
                    CommonErrorCode.ACCESS_DENIED,
                    new MockHttpServletRequest("POST", "/v1/x"),
                    new MockHttpServletResponse());
            responder.write(
                    new IllegalStateException("boom"),
                    CommonErrorCode.INTERNAL_ERROR,
                    new MockHttpServletRequest("POST", "/v1/x"),
                    new MockHttpServletResponse());
            assertThat(capture.events())
                    .extracting(ILoggingEvent::getLevel)
                    .containsExactly(Level.WARN, Level.ERROR);
            assertThat(capture.messages().get(1))
                    .contains("exception=java.lang.IllegalStateException")
                    .contains("message=boom");
        }
    }
}
