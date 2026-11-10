package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Every state-changing POST of a signed-in user carries an Idempotency-Key (ADR-0011). */
class IdempotencyIT extends ApiTestSupport {

    @Test
    void aRetryWithTheSameKeyReplaysAndADifferentBodyIsRefused() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        String key = UUID.randomUUID().toString();
        String body =
                json.writeValueAsString(
                        Map.of("screen", "M-01", "action", "EXPORT", "reason", "Monthly list"));
        MvcResult first =
                mvc.perform(
                                userRequest(maker, post("/v1/actions"))
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isOk())
                        .andReturn();
        MvcResult replay =
                mvc.perform(
                                userRequest(maker, post("/v1/actions"))
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Idempotent-Replayed", "true"))
                        .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        mvc.perform(
                        userRequest(maker, post("/v1/actions"))
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "screen", "M-01", "action", "EXPORT",
                                                        "reason", "Other"))))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void aMissingOrMalformedKeyIsRejected() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        String body = json.writeValueAsString(Map.of("screen", "M-01", "action", "EXPORT"));
        mvc.perform(
                        userRequest(maker, post("/v1/actions"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        userRequest(maker, post("/v1/actions"))
                                .header("Idempotency-Key", "not-a-uuid")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest());
    }
}
