package in.gov.fci.payroll.eseva.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.loans.api.LoanInstalmentChanged;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * SRS-12-10 / BR-LOAN-009 and SRS-17-12: a loan change told with parts missing shows them blank,
 * never "null"; with the template configured but no registered e-mail the e-mail is suppressed for
 * that reason, and with one it is queued.
 */
class MemberNotificationsEdgeTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final MemberNotifications notifications =
            new MemberNotifications(
                    db.jdbc,
                    mock(Authorization.class),
                    Clock.fixed(Instant.parse("2026-10-29T06:00:00Z"), ZoneOffset.UTC));

    MemberNotificationsEdgeTest() {
        db.rows(
                "FROM notification_template WHERE",
                row(
                        "template_id", 5L,
                        "subject_en", "Loan instalment changed",
                        "body_en", "Your loan {loan}: {change} from {from}"));
    }

    @Test
    void SRS_12_10_aChangeWithPartsMissingShowsThemBlankAndWithoutAnEmailIsSuppressed() {
        db.rows("SELECT email_address FROM employee", cols(" "));
        notifications.on(new LoanInstalmentChanged(7L, null, null, null, null));
        ScriptedJdbc.Update inApp = db.updates("'IN_APP'").getFirst();
        assertThat((String) inApp.args().get(4)).doesNotContain("null");
        assertThat(db.updates("'EMAIL'"))
                .singleElement()
                .satisfies(
                        u -> assertThat(u.args()).contains("SUPPRESSED", "No registered e-mail"));
    }

    @Test
    void SRS_17_12_withATemplateAndAnEmailTheEmailIsQueued() {
        db.rows("SELECT email_address FROM employee", cols("asha@example.test"));
        notifications.on(
                new LoanInstalmentChanged(
                        7L, "LN-1", "RESCHEDULED", new java.math.BigDecimal("1500"), "202611"));
        assertThat(db.updates("'EMAIL'"))
                .singleElement()
                .satisfies(u -> assertThat(u.args()).contains("QUEUED"));
    }
}
