package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * R-PII: the masking policy SHIPPED in the first document of {@code application.yaml} ({@code
 * fci.logging.masking}), bound as the application binds it, applied to realistic HRMS bodies. The
 * data are fake but in real formats.
 */
class MaskingDefaultsTest {

    private static MaskingProperties properties;
    private static MaskingPolicy policy;

    @BeforeAll
    static void loadTheShippedPolicy() throws IOException {
        PropertySource<?> first =
                new YamlPropertySourceLoader()
                        .load("application", new ClassPathResource("application.yaml"))
                        .getFirst();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(first);
        properties =
                new Binder(ConfigurationPropertySources.get(environment))
                        .bind("fci.logging.masking", Bindable.of(MaskingProperties.class))
                        .orElseThrow(() -> new AssertionError("fci.logging.masking did not bind"));
        policy = new MaskingPolicy(properties);
    }

    private static String mask(String json) {
        return policy.body(json);
    }

    @Test
    void R_PII_theShippedPolicyBindsAndIsConfigured() {
        assertThat(properties.fieldRules()).hasSize(9);
        assertThat(properties.patternRules()).hasSize(4);
        assertThat(policy.configured()).isTrue();
    }

    @Test
    void R_PII_secretsAreHiddenCompletely() {
        assertThat(
                        mask(
                                "{\"password\":\"p@ss\",\"token\":\"abc\",\"api_key\":\"k1\","
                                        + "\"authorization\":\"Bearer x\",\"otp\":\"123456\"}"))
                .isEqualTo(
                        "{\"password\":\"***\",\"token\":\"***\",\"api_key\":\"***\","
                                + "\"authorization\":\"***\",\"otp\":\"***\"}");
    }

    @Test
    void R_PII_secretKeyNamesAreMatchedWhateverTheirCaseAndSeparator() {
        assertThat(
                        mask(
                                "{\"API_KEY\":\"a\",\"Api-Key\":\"b\",\"apikey\":\"c\","
                                    + "\"access_token\":\"d\",\"newPassword\":\"e\","
                                    + "\"client_secret\":\"f\",\"pin\":\"1234\",\"cvv\":\"123\"}"))
                .isEqualTo(
                        "{\"API_KEY\":\"***\",\"Api-Key\":\"***\",\"apikey\":\"***\","
                                + "\"access_token\":\"***\",\"newPassword\":\"***\","
                                + "\"client_secret\":\"***\",\"pin\":\"***\",\"cvv\":\"***\"}");
    }

    @Test
    void R_PII_panKeepsTheLastFour() {
        assertThat(
                        mask(
                                "{\"pan\":\"ABCPS1234K\",\"pan_no\":\"ABCPS1234K\","
                                        + "\"PAN_NUMBER\":\"ABCPS1234K\"}"))
                .isEqualTo(
                        "{\"pan\":\"******234K\",\"pan_no\":\"******234K\","
                                + "\"PAN_NUMBER\":\"******234K\"}");
    }

    @Test
    void R_PII_aadhaarKeepsTheLastFourAlsoWhenSentAsANumber() {
        assertThat(
                        mask(
                                "{\"aadhaar\":\"234567890123\",\"aadhaar_no\":\"234567890123\","
                                    + "\"uid\":\"234567890123\",\"aadhar_number\":234567890123}"))
                .isEqualTo(
                        "{\"aadhaar\":\"********0123\",\"aadhaar_no\":\"********0123\","
                                + "\"uid\":\"********0123\",\"aadhar_number\":\"********0123\"}");
    }

    @Test
    void R_PII_bankAccountKeepsTheLastFour() {
        assertThat(
                        mask(
                                "{\"bank_account\":\"123456789012\",\"account_no\":\"123456789012\","
                                    + "\"account_number\":\"98765432101234\","
                                    + "\"acct_no\":\"123456789012\"}"))
                .isEqualTo(
                        "{\"bank_account\":\"********9012\",\"account_no\":\"********9012\","
                                + "\"account_number\":\"**********1234\","
                                + "\"acct_no\":\"********9012\"}");
    }

    @Test
    void R_PII_cpfCodeKeepsTheLastFour() {
        assertThat(mask("{\"cpf_code\":\"12345678\",\"cpf_account_no\":\"12345678\"}"))
                .isEqualTo("{\"cpf_code\":\"****5678\",\"cpf_account_no\":\"****5678\"}");
    }

    @Test
    void R_PII_aMobileNumberSentAsAJsonNumberKeepsTheLastThree() {
        assertThat(
                        mask(
                                "{\"mobile\":9876543210,\"mobile_number\":9876543210,"
                                    + "\"phone\":\"9876543210\",\"contact_no\":\"9876543210\"}"))
                .isEqualTo(
                        "{\"mobile\":\"*******210\",\"mobile_number\":\"*******210\","
                                + "\"phone\":\"*******210\",\"contact_no\":\"*******210\"}");
    }

    @Test
    void R_PII_anEmailKeepsTheFirstLetterAndTheDomain() {
        assertThat(
                        mask(
                                "{\"e_mail\":\"rakesh.sharma@fci-demo.test\","
                                        + "\"email\":\"rakesh.sharma@fci-demo.test\","
                                        + "\"EMAIL_ID\":\"rakesh.sharma@fci-demo.test\"}"))
                .isEqualTo(
                        "{\"e_mail\":\"r***@fci-demo.test\",\"email\":\"r***@fci-demo.test\","
                                + "\"EMAIL_ID\":\"r***@fci-demo.test\"}");
    }

    @Test
    void R_PII_aNameKeepsTheInitialsOfEachWord() {
        assertThat(
                        mask(
                                "{\"emp_name\":\"Rakesh Sharma\",\"first_name\":\"Rakesh\","
                                        + "\"name\":\"Rakesh Sharma\",\"last_name\":\"Sharma\"}"))
                .isEqualTo(
                        "{\"emp_name\":\"R*** S***\",\"first_name\":\"R***\","
                                + "\"name\":\"R*** S***\",\"last_name\":\"S***\"}");
    }

    @Test
    void R_PII_nameRuleMatchesTheWholeKeyOnly() {
        // "dept_name" is not a person's name key: the value is only checked as free text
        assertThat(mask("{\"dept_name\":\"Finance\",\"username\":\"rsharma\"}"))
                .isEqualTo("{\"dept_name\":\"Finance\",\"username\":\"rsharma\"}");
    }

    @Test
    void R_PII_dateOfBirthIsHiddenCompletely() {
        assertThat(
                        mask(
                                "{\"dob\":\"1990-05-17\",\"date_of_birth\":\"1990-05-17\","
                                        + "\"birth_date\":\"1990-05-17\"}"))
                .isEqualTo("{\"dob\":\"***\",\"date_of_birth\":\"***\",\"birth_date\":\"***\"}");
    }

    @Test
    void R_PII_aPanAMobileAnEmailAndAnAadhaarInFreeTextAreMasked() {
        assertThat(
                        mask(
                                "{\"remarks\":\"PAN ABCPS1234K mobile 9876543210 mail"
                                        + " rakesh.sharma@fci-demo.test aadhaar 234567890123\"}"))
                .isEqualTo(
                        "{\"remarks\":\"PAN ******234K mobile *******210 mail r***@fci-demo.test"
                                + " aadhaar ********0123\"}");
    }

    @Test
    void R_PII_plainTextIsMaskedByThePatternRules() {
        assertThat(policy.body("call 9876543210 re ABCPS1234K"))
                .isEqualTo("call *******210 re ******234K");
    }

    @Test
    void R_PII_fieldsThatMustStayReadableStayReadable() {
        String readable =
                "{\"request_id\":\"REQ-20261105-0001\",\"emp_num\":\"10001234\",\"amount\":45000,"
                        + "\"status\":\"OK\",\"ota_yymm\":\"2610\",\"pay_code\":\"BASIC\"}";
        assertThat(mask(readable)).isEqualTo(readable);
    }

    @Test
    void R_PII_aTwelveDigitRunInsideALongerDigitStringIsNotAnAadhaar() {
        String body = "{\"remarks\":\"ref 2345678901234 and 123456789012345678 end\"}";
        assertThat(mask(body)).isEqualTo(body);
        // letters next to the run are not digits: it is still an Aadhaar-like number
        assertThat(mask("{\"remarks\":\"X234567890123Y\"}"))
                .isEqualTo("{\"remarks\":\"X********0123Y\"}");
    }

    @Test
    void R_PII_aTenDigitNumberIsAMobileOnlyIfItStartsWithSixToNineAndIsNotPartOfALongerRun() {
        String body = "{\"remarks\":\"5876543210 and 98765432101 and 59876543210\"}";
        assertThat(mask(body)).isEqualTo(body);
        assertThat(mask("{\"remarks\":\"6876543210\"}")).isEqualTo("{\"remarks\":\"*******210\"}");
    }

    @Test
    void R_PII_theFirstFieldRuleThatMatchesDecides() {
        // secrets come first: the PAN here is hidden completely, not shown with its last four
        assertThat(mask("{\"api_key_pan\":\"ABCPS1234K\"}")).isEqualTo("{\"api_key_pan\":\"***\"}");
        assertThat(mask("{\"pan_token\":\"ABCPS1234K\"}")).isEqualTo("{\"pan_token\":\"***\"}");
        // mobile/phone comes before e-mail: the address is cut like a number
        assertThat(mask("{\"mobile_email\":\"a@fci-demo.test\"}"))
                .isEqualTo("{\"mobile_email\":\"" + "*".repeat(12) + "est\"}");
    }

    @Test
    void R_PII_aKeyThatMerelyContainsPinIsOverMaskedWhichIsTheSafeSide() {
        // shipped rule 1 is a substring rule: documents the (safe) over-masking
        assertThat(mask("{\"mapping\":\"x\",\"shipping\":\"y\"}"))
                .isEqualTo("{\"mapping\":\"***\",\"shipping\":\"***\"}");
    }

    @Test
    void R_PII_aRecordInsideAnArrayInsideAnObjectIsMasked() {
        assertThat(
                        mask(
                                "{\"employees\":[{\"emp_name\":\"Rakesh"
                                        + " Sharma\",\"pan\":\"ABCPS1234K\","
                                        + "\"emp_num\":\"10001234\"}],\"count\":1}"))
                .isEqualTo(
                        "{\"employees\":[{\"emp_name\":\"R*** S***\",\"pan\":\"******234K\","
                                + "\"emp_num\":\"10001234\"}],\"count\":1}");
    }

    @Test
    void R_PII_aQueryStringIsMaskedByParameterName() {
        assertThat(policy.query("pan=ABCPS1234K&mobile=9876543210&token=abc&page=2"))
                .isEqualTo("pan=******234K&mobile=*******210&token=***&page=2");
        assertThat(policy.query("emp_num=10001234&e_mail=rakesh.sharma@fci-demo.test"))
                .isEqualTo("emp_num=10001234&e_mail=r***@fci-demo.test");
    }

    @Test
    void R_PII_theShippedRuleNamesAreTheDocumentedOnes() {
        List<String> names =
                properties.fieldRules().stream().map(r -> r.strategy().name()).toList();
        assertThat(names)
                .containsExactly(
                        "FULL",
                        "FULL",
                        "KEEP_LAST",
                        "KEEP_LAST",
                        "KEEP_LAST",
                        "KEEP_LAST",
                        "KEEP_LAST",
                        "EMAIL",
                        "INITIALS");
    }

    @Test
    void R_PII_aOneMegabyteBodyIsMaskedInUnderTwoSeconds() {
        // a long run of characters that could start an e-mail address, a worst case for the
        // e-mail pattern; the body is cut to MaskingPolicy.LIMIT before any rule runs
        List<String> bodies =
                List.of(
                        "a".repeat(1 << 20),
                        "{\"remarks\":\"" + "a".repeat(1 << 20) + "\"}",
                        "9".repeat(1 << 20),
                        "A".repeat(1 << 20));
        // The budget is the masking work's own CPU time on this thread: wall-clock time also counts
        // the time the
        // thread waits for a core, which a loaded machine (the full parallel suite beside a running
        // stack) inflates
        // to ~3 s while the work itself takes ~0.9 s (FLAKY-1, 2026-11-09). Same 2 s limit.
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        boolean cpu = threads.isCurrentThreadCpuTimeSupported();
        for (String body : bodies) {
            long start = cpu ? threads.getCurrentThreadCpuTime() : System.nanoTime();
            String out = policy.body(body);
            long end = cpu ? threads.getCurrentThreadCpuTime() : System.nanoTime();
            long millis = (end - start) / 1_000_000;
            assertThat(out).isNotNull();
            assertThat(millis)
                    .as((cpu ? "CPU" : "elapsed") + " ms for a body of " + body.length())
                    .isLessThan(2_000);
        }
    }
}
