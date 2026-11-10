package in.gov.fci.payroll.integration.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * SRS-INT-4, R-PII: the bearer token Payroll holds for HRMS - read from the answer to the token
 * call (data.token, as the collection's own test script reads it), kept until shortly before it
 * expires, forgotten on a refusal, never shown.
 */
class HrmsBearerTest {

    private static final Instant T0 = Instant.parse("2026-11-06T04:00:00Z");

    @Test
    void SRS_INT_4_theTokenAndItsLifetimeAreReadFromTheAnswer() {
        HrmsBearer.Issued issued =
                HrmsBearer.read(
                                "{\"status\":\"success\",\"data\":{\"token\":\" abc123"
                                        + " \",\"token_type\":\"Bearer\",\"expires_in\":3600}}")
                        .orElseThrow();
        assertThat(issued.token()).isEqualTo("abc123");
        assertThat(issued.lifetime()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void SRS_INT_4_aTokenWithoutALifetimeIsUsableForThisCallOnly() {
        for (String life :
                new String[] {
                    "", ",\"expires_in\":0", ",\"expires_in\":-5", ",\"expires_in\":\"x\""
                }) {
            HrmsBearer.Issued issued =
                    HrmsBearer.read("{\"data\":{\"token\":\"t\"" + life + "}}").orElseThrow();
            assertThat(issued.lifetime()).as(life).isEqualTo(Duration.ZERO);
        }
    }

    @Test
    void SRS_INT_4_anAnswerWithoutAUsableTokenGivesNothing() {
        for (String body :
                new String[] {
                    null,
                    "",
                    " ",
                    "not json",
                    "{\"data\":{}}",
                    "{\"data\":{\"token\":\"\"}}",
                    "{\"data\":{\"token\":\"   \"}}",
                    "{\"data\":{\"token\":5}}",
                    "{\"token\":\"top-level\"}",
                    "[]"
                }) {
            assertThat(HrmsBearer.read(body)).as(String.valueOf(body)).isEmpty();
        }
    }

    @Test
    void SRS_INT_4_aKeptTokenIsGoodUntilTheSkewBeforeItsExpiry() {
        HrmsBearer bearer = new HrmsBearer();
        assertThat(bearer.current(T0)).isEmpty();
        bearer.keep(
                new HrmsBearer.Issued("T1", Duration.ofSeconds(120)), T0, Duration.ofSeconds(60));
        assertThat(bearer.current(T0)).contains("T1");
        assertThat(bearer.current(T0.plusSeconds(59))).contains("T1");
        assertThat(bearer.current(T0.plusSeconds(60))).isEmpty();
        assertThat(bearer.current(T0.plusSeconds(600))).isEmpty();
    }

    @Test
    void SRS_INT_4_aLifetimeNotLongerThanTheSkewIsNeverKept() {
        HrmsBearer bearer = new HrmsBearer();
        bearer.keep(new HrmsBearer.Issued("T", Duration.ofSeconds(60)), T0, Duration.ofSeconds(60));
        assertThat(bearer.current(T0)).isEmpty();
        bearer.keep(new HrmsBearer.Issued("T", Duration.ofSeconds(30)), T0, Duration.ofSeconds(60));
        assertThat(bearer.current(T0)).isEmpty();
        bearer.keep(new HrmsBearer.Issued("T", Duration.ZERO), T0, Duration.ZERO);
        assertThat(bearer.current(T0)).isEmpty();
    }

    @Test
    void SRS_INT_4_aNewTokenReplacesTheOldAndInvalidateForgetsIt() {
        HrmsBearer bearer = new HrmsBearer();
        bearer.keep(new HrmsBearer.Issued("T1", Duration.ofHours(1)), T0, Duration.ZERO);
        bearer.keep(new HrmsBearer.Issued("T2", Duration.ofHours(1)), T0, Duration.ZERO);
        assertThat(bearer.current(T0)).contains("T2");
        bearer.invalidate();
        assertThat(bearer.current(T0)).isEmpty();
        bearer.invalidate();
        assertThat(bearer.current(T0)).isEmpty();
    }

    @Test
    void SRS_INT_4_invalidateByTokenClearsTheMatchingTokenAndSaysSo() {
        HrmsBearer bearer = new HrmsBearer();
        bearer.keep(new HrmsBearer.Issued("T1", Duration.ofHours(1)), T0, Duration.ZERO);
        assertThat(bearer.invalidate("T1")).isTrue();
        assertThat(bearer.current(T0)).isEmpty();
        assertThat(bearer.toString()).isEqualTo("HrmsBearer[held=false]");
    }

    @Test
    void SRS_INT_4_invalidateByTokenKeepsADifferentTokenAndSaysNo() {
        HrmsBearer bearer = new HrmsBearer();
        bearer.keep(new HrmsBearer.Issued("T2", Duration.ofHours(1)), T0, Duration.ZERO);
        assertThat(bearer.invalidate("T1")).isFalse();
        assertThat(bearer.current(T0)).contains("T2");
    }

    @Test
    void SRS_INT_4_invalidateByTokenWithNoneHeldReturnsFalse() {
        HrmsBearer bearer = new HrmsBearer();
        assertThat(bearer.invalidate("T1")).isFalse();
        assertThat(bearer.invalidate((String) null)).isFalse();
        bearer.keep(new HrmsBearer.Issued("T1", Duration.ofHours(1)), T0, Duration.ZERO);
        bearer.invalidate();
        assertThat(bearer.invalidate("T1")).as("already forgotten").isFalse();
    }

    @Test
    void SRS_INT_4_invalidateByNullKeepsTheHeldToken() {
        HrmsBearer bearer = new HrmsBearer();
        bearer.keep(new HrmsBearer.Issued("T1", Duration.ofHours(1)), T0, Duration.ZERO);
        assertThat(bearer.invalidate((String) null)).isFalse();
        assertThat(bearer.current(T0)).contains("T1");
    }

    @Test
    void SRS_INT_4_afterARenewalTheOldRefusedTokenCannotClearTheNewOne() {
        HrmsBearer bearer = new HrmsBearer();
        bearer.keep(new HrmsBearer.Issued("OLD", Duration.ofHours(1)), T0, Duration.ZERO);
        assertThat(bearer.invalidate("OLD")).as("first caller").isTrue();
        bearer.keep(new HrmsBearer.Issued("NEW", Duration.ofHours(1)), T0, Duration.ZERO);
        assertThat(bearer.invalidate("OLD")).as("second caller, same stale token").isFalse();
        assertThat(bearer.current(T0)).contains("NEW");
    }

    @Test
    void R_PII_theTokenIsNeverInToString() {
        HrmsBearer bearer = new HrmsBearer();
        assertThat(bearer.toString()).isEqualTo("HrmsBearer[held=false]");
        bearer.keep(
                new HrmsBearer.Issued("super-secret-token", Duration.ofHours(1)),
                T0,
                Duration.ZERO);
        assertThat(bearer.toString())
                .isEqualTo("HrmsBearer[held=true]")
                .doesNotContain("super-secret");
    }
}
