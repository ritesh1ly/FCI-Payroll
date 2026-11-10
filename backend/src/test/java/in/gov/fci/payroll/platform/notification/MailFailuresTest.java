package in.gov.fci.payroll.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.notification.MailFailures.Kind;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

/**
 * The failure classes of an e-mail (OD-148; SRS-17-12, SRS-INT-6, SRS-INT-16; RFC 5321; PENDING
 * MAIL-2): 4xx, time-outs, refused or unknown hosts and TLS faults are transient; 550 / 551 / 553 /
 * 554 and refused addresses are recipient bounces; 552 is a size refusal; 530 / 534 / 535 and
 * rejected credentials stop the channel; other 5xx are permanent; anything unrecognised is
 * transient (retried, never lost).
 */
class MailFailuresTest {

    private static Kind kind(Throwable t) {
        return MailFailures.classify(t).kind();
    }

    private static MailSendException reply(String text) {
        return new MailSendException(text);
    }

    @Test
    void SRS_INT_16_fourHundredRepliesTimeOutsRefusedHostsAndTlsAreTransient() {
        for (String r :
                new String[] {
                    "421 4.3.2 Service not available",
                    "450 4.2.1 Mailbox busy",
                    "451 4.3.0 Local error",
                    "452 4.3.1 Insufficient storage"
                }) {
            assertThat(MailFailures.classify(reply(r)))
                    .isEqualTo(
                            new MailFailures.Failure(
                                    Kind.TRANSIENT, Integer.parseInt(r.substring(0, 3))));
        }
        assertThat(kind(new MailSendException("x", new SocketTimeoutException("read timed out"))))
                .isEqualTo(Kind.TRANSIENT);
        assertThat(kind(new MailSendException("x", new ConnectException("refused"))))
                .isEqualTo(Kind.TRANSIENT);
        assertThat(kind(new MailSendException("x", new UnknownHostException("smtp"))))
                .isEqualTo(Kind.TRANSIENT);
        assertThat(kind(new MailSendException("x", new SSLHandshakeException("handshake"))))
                .isEqualTo(Kind.TRANSIENT);
        assertThat(kind(new IllegalStateException("smtp down"))).isEqualTo(Kind.TRANSIENT);
        assertThat(kind(new IllegalStateException())).isEqualTo(Kind.TRANSIENT);
        assertThat(MailFailures.classify(new IllegalStateException("version 2.0 of 250 ok")).code())
                .as("a 2xx or a plain number is no reply code")
                .isZero();
    }

    @Test
    void SRS_17_12_recipientRepliesAndRefusedAddressesAreBounces() throws Exception {
        for (String r :
                new String[] {
                    "550 5.1.1 User unknown",
                    "551 User not local",
                    "553 5.1.3 Bad address syntax",
                    "554 5.7.1 Recipient rejected"
                }) {
            assertThat(kind(reply(r))).as(r).isEqualTo(Kind.RECIPIENT);
        }
        Address[] invalid = {new InternetAddress("x@fci-demo.test")};
        SendFailedException refused = new SendFailedException("refused", null, null, null, invalid);
        assertThat(MailFailures.classify(new MailSendException("x", refused)))
                .isEqualTo(new MailFailures.Failure(Kind.RECIPIENT, 550));
        SendFailedException coded = new SendFailedException("553 bad", null, null, null, invalid);
        assertThat(MailFailures.classify(coded).code()).isEqualTo(553);
        SendFailedException later =
                new SendFailedException("450 try later", null, null, null, invalid);
        assertThat(kind(later))
                .as("a 4xx with an address is still transient")
                .isEqualTo(Kind.TRANSIENT);
        assertThat(kind(new SendFailedException("no list", null, null, null, new Address[0])))
                .isEqualTo(Kind.TRANSIENT);
        assertThat(kind(new RecipientRejectedException("r", null))).isEqualTo(Kind.RECIPIENT);
        assertThat(
                        kind(
                                new MailSendException(
                                        Map.of(
                                                new Object(),
                                                new MessagingException("550 no such user")))))
                .as("the failed message's own reply")
                .isEqualTo(Kind.RECIPIENT);
        assertThat(kind(new MailSendException(Map.of(new Object(), new MessagingException("odd")))))
                .isEqualTo(Kind.TRANSIENT);
    }

    @Test
    void SRS_17_12_sizeAuthAndOtherPermanentReplies() {
        assertThat(kind(reply("552 5.3.4 Message size exceeds fixed limit"))).isEqualTo(Kind.SIZE);
        assertThat(kind(new MailAuthenticationException("bad credentials"))).isEqualTo(Kind.AUTH);
        assertThat(kind(new MailSendException("x", new AuthenticationFailedException("535"))))
                .isEqualTo(Kind.AUTH);
        assertThat(kind(reply("535 5.7.8 Authentication credentials invalid")))
                .isEqualTo(Kind.AUTH);
        assertThat(kind(reply("530 5.7.0 Authentication required"))).isEqualTo(Kind.AUTH);
        assertThat(kind(reply("534 5.7.9 Mechanism too weak"))).isEqualTo(Kind.AUTH);
        assertThat(kind(reply("500 Syntax error"))).isEqualTo(Kind.PERMANENT);
        assertThat(kind(reply("503 Bad sequence"))).isEqualTo(Kind.PERMANENT);
        assertThat(MailFailures.ofCode(0).kind()).isEqualTo(Kind.TRANSIENT);
        assertThat(MailFailures.ofCode(499).kind()).isEqualTo(Kind.TRANSIENT);
        assertThat(MailFailures.ofCode(500).kind()).isEqualTo(Kind.PERMANENT);
        assertThat(MailFailures.code(new IllegalStateException("x1234 and 5500"))).isZero();
    }
}
