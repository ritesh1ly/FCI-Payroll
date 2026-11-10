package in.gov.fci.payroll.platform.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** SRS-EH-1: a problem written to a response replaces anything already buffered, as UTF-8. */
class ProblemDetailWriterMutationGapsTest {

    @Test
    void SRS_EH_1_bufferedContentIsDiscardedAndTheBodyIsUtf8Json() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getOutputStream().write("partial page".getBytes(StandardCharsets.UTF_8));
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle("Busy");

        new ProblemDetailWriter(new JsonMapper()).write(response, problem);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).startsWith("{").doesNotContain("partial page").contains("\"Busy\"");
    }
}
