package in.gov.fci.payroll.platform.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

/** SRS-EH-1: the message sources and the locale of the problem details. */
class ProblemDetailFactoryMutationGapsTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-05T10:15:30Z"), ZoneOffset.UTC);

    @Test
    void SRS_EH_1_theApplicationMessageSourceIsConsultedAfterTheCommonBundle() {
        StaticMessageSource application = new StaticMessageSource();
        application.addMessage("app.only.key", Locale.ENGLISH, "From the application");
        ProblemDetailFactory factory =
                new ProblemDetailFactory("https://errors.fci.gov.in/", application, CLOCK);
        assertThat(factory.message("app.only.key", null, "fallback", Locale.ENGLISH))
                .isEqualTo("From the application");
        assertThat(factory.messageSource()).isNotNull();
    }

    @Test
    void SRS_EH_1_withoutAnApplicationMessageSourceTheSuppliedDefaultIsUsed() {
        ProblemDetailFactory factory =
                new ProblemDetailFactory("https://errors.fci.gov.in/", null, CLOCK);
        assertThat(factory.message("app.only.key", null, "fallback", Locale.ENGLISH))
                .isEqualTo("fallback");
    }

    @Test
    void SRS_EH_1_theLocaleIsAlwaysEnglishWhateverIsRequested() {
        assertThat(ProblemDetailFactory.resolveLocale(Locale.FRENCH)).isEqualTo(Locale.ENGLISH);
        assertThat(ProblemDetailFactory.resolveLocale(Locale.forLanguageTag("hi-IN")))
                .isEqualTo(Locale.ENGLISH);
        assertThat(ProblemDetailFactory.resolveLocale(null)).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void SRS_EH_1_aMessageRequestedInHindiIsResolvedInEnglish() {
        StaticMessageSource application = new StaticMessageSource();
        application.addMessage("app.only.key", Locale.ENGLISH, "English text");
        application.addMessage("app.only.key", Locale.forLanguageTag("hi"), "Hindi text");
        ProblemDetailFactory factory =
                new ProblemDetailFactory("https://errors.fci.gov.in/", application, CLOCK);
        assertThat(factory.message("app.only.key", null, "x", Locale.forLanguageTag("hi")))
                .isEqualTo("English text");
    }
}
