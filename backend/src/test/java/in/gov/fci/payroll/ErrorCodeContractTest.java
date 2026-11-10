package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.MessageSeverity;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * Every error code of every module (RFC 9457 problem responses; the i18n bundle keys them): a
 * stable MOD-nnn-NAME code unique across the application whose number is its HTTP status, a type
 * path, a title and a user-safe detail.
 */
class ErrorCodeContractTest {

    private static List<ErrorCode> all() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(
                            org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
                                    bd) {
                        return true;
                    }
                };
        scanner.addIncludeFilter(new AssignableTypeFilter(ErrorCode.class));
        List<ErrorCode> codes = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents("in.gov.fci.payroll")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            if (type.isEnum()) {
                for (Object constant : type.getEnumConstants()) {
                    codes.add((ErrorCode) constant);
                }
            }
        }
        return codes;
    }

    @Test
    void everyErrorCodeIsWellFormedAndCarriesItsStatus() throws Exception {
        List<ErrorCode> codes = all();
        assertThat(codes).hasSizeGreaterThan(40);
        for (ErrorCode c : codes) {
            String name = c.getClass().getSimpleName() + "." + ((Enum<?>) c).name();
            assertThat(c.code()).as(name).matches("^[A-Z]{2,5}-\\d{3}-[A-Z0-9-]+$");
            assertThat(c.status()).as(name).isNotNull();
            assertThat(c.code().substring(c.code().indexOf('-') + 1, c.code().indexOf('-') + 4))
                    .as("%s: the number is the HTTP status", name)
                    .isEqualTo(String.valueOf(c.status().value()));
            assertThat(c.type()).as(name).isNotBlank().doesNotContain(" ");
            assertThat(c.defaultTitle()).as(name).isNotBlank();
            assertThat(c.defaultDetail()).as(name).isNotBlank();
        }
    }

    /**
     * Deliberately one answer (ADR-0008, no enumeration): a location that does not exist and one
     * the user may not sign in at must be indistinguishable to the caller.
     */
    private static final java.util.Set<String> SHARED_ON_PURPOSE =
            java.util.Set.of("ApiErrorCode.UNKNOWN_LOCATION");

    @Test
    void noTwoErrorsShareACode() throws Exception {
        Map<String, String> seen = new HashMap<>();
        for (ErrorCode c : all()) {
            String owner = c.getClass().getSimpleName() + "." + ((Enum<?>) c).name();
            if (SHARED_ON_PURPOSE.contains(owner)) {
                assertThat(c.code()).isEqualTo("AUTH-403-LOCATION");
                continue;
            }
            String previous = seen.put(c.code(), owner);
            assertThat(previous).as("%s and %s share %s", previous, owner, c.code()).isNull();
        }
    }

    /**
     * SRS-EH-1, SRS-EH-2 (US-GAP-22): every message of the catalogue carries its severity and the
     * key of its corrective action; refusals of access, sign-in and lockouts are Security
     * conditions, every other refusal is Blocking unless SRS-EH-5 names it a Security condition.
     */
    @Test
    void SRS_EH_1_everyMessageCarriesItsSeverityAndCorrectiveAction() throws Exception {
        for (ErrorCode c : all()) {
            int status = c.status().value();
            MessageSeverity expected =
                    status == 401
                                    || status == 403
                                    || status == 423
                                    || HISTORY_EDIT.contains(c.code())
                            ? MessageSeverity.SECURITY
                            : MessageSeverity.BLOCKING;
            assertThat(c.severity()).as(c.code()).isEqualTo(expected);
            assertThat(c.actionKey()).isEqualTo("error." + c.code() + ".action");
        }
    }

    /** SRS-EH-5: the refusals of an attempted edit of a history row (application and database). */
    private static final Set<String> HISTORY_EDIT =
            Set.of("AUD-405-IMMUTABLE", "AUD-409-IMMUTABLE");

    /**
     * SRS-EH-5 (US-GAP-116): an attempted edit of a history row is a Security condition whatever
     * its HTTP status (405 from the application, 409 from the immutability trigger), held against
     * the code; a Blocking code of the same status stays Blocking. The other SRS-EH-5 examples are
     * Security by their status: access outside the Access Group scope (403) and a lockout (423).
     */
    @Test
    void SRS_EH_5_anAttemptedHistoryRowEditIsASecurityConditionNotBlocking() throws Exception {
        Map<String, ErrorCode> byCode = new HashMap<>();
        for (ErrorCode c : all()) {
            byCode.putIfAbsent(c.code(), c);
        }
        for (String code : HISTORY_EDIT) {
            assertThat(byCode).as(code).containsKey(code);
            assertThat(byCode.get(code).severity()).as(code).isEqualTo(MessageSeverity.SECURITY);
        }
        assertThat(byCode.get("AUD-405-IMMUTABLE").status().value()).isEqualTo(405);
        assertThat(byCode.get("AUD-409-IMMUTABLE").status().value()).isEqualTo(409);
        // Same status, not a Security condition: still Blocking.
        assertThat(byCode.get("AUD-409-VERSIONS").severity()).isEqualTo(MessageSeverity.BLOCKING);
        assertThat(MessageSeverity.ofStatus(405)).isEqualTo(MessageSeverity.BLOCKING);
        assertThat(MessageSeverity.ofStatus(409)).isEqualTo(MessageSeverity.BLOCKING);
        // Scope (403) and lockouts (423) by their status.
        assertThat(byCode.get("AUTH-423-LOCKED").severity()).isEqualTo(MessageSeverity.SECURITY);
        assertThat(byCode.get("AUTH-423-ACCOUNT-LOCKED").severity())
                .isEqualTo(MessageSeverity.SECURITY);
        assertThat(MessageSeverity.ofStatus(403)).isEqualTo(MessageSeverity.SECURITY);
        assertThat(MessageSeverity.ofStatus(422)).isEqualTo(MessageSeverity.BLOCKING);
    }

    /**
     * SRS-EH-2, SRS-G-40: the corrective action of every HTTP status a message uses is in both
     * label bundles, so a message without its own action text still says what to do next.
     */
    @Test
    void SRS_EH_2_everyStatusHasItsCorrectiveActionInEnglishAndHindi() throws Exception {
        Set<Integer> statuses = new TreeSet<>();
        for (ErrorCode c : all()) {
            statuses.add(c.status().value());
        }
        for (String locale : List.of("en", "hi")) {
            Properties bundle = bundle(locale);
            for (int status : statuses) {
                assertThat(bundle.getProperty("error.action." + status))
                        .as("error.action.%s in messages_%s", status, locale)
                        .isNotBlank();
            }
        }
    }

    private static Properties bundle(String locale) throws IOException {
        Properties p = new Properties();
        try (InputStream in =
                ErrorCodeContractTest.class.getResourceAsStream(
                        "/i18n/messages_" + locale + ".properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }
}
