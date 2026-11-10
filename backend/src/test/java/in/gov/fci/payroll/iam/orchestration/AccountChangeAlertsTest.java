package in.gov.fci.payroll.iam.orchestration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.platform.notification.NotificationSender;
import in.gov.fci.payroll.platform.notification.SmsSender;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Security review 2026-10-31: the owner of an account hears of an administrator's change of its
 * sign-in (registered mobile, temporary password) at its own e-mail and at the mobile it had
 * before; only after the change commits; a channel failure never undoes the change.
 */
class AccountChangeAlertsTest {

    private final NotificationSender mail = mock(NotificationSender.class);
    private final SmsSender sms = mock(SmsSender.class);
    private final AccountChangeAlerts alerts = new AccountChangeAlerts(mail, sms);
    private final AppUser user = mock(AppUser.class);

    AccountChangeAlertsTest() {
        when(user.getRegisteredEmail()).thenReturn("owner@fci-demo.test");
        when(user.getDisplayName()).thenReturn("Owner");
        when(user.getRegisteredMobile()).thenReturn("9822222233");
    }

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void theOwnerIsToldAtTheEmailAndTheOldMobileOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        alerts.changed(user, "9811111122", "registered mobile");
        verify(mail, never()).sendAccountChanged(any(), any(), any());
        for (TransactionSynchronization s :
                TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        verify(mail).sendAccountChanged("owner@fci-demo.test", "Owner", "registered mobile");
        verify(sms).send(org.mockito.ArgumentMatchers.eq("9811111122"), anyString());
        verify(sms, never()).send(org.mockito.ArgumentMatchers.eq("9822222233"), anyString());
    }

    @Test
    void withoutATransactionTheCurrentMobileIsToldAtOnceAndAFailureIsSwallowed() {
        alerts.changed(user, null, "password");
        verify(sms).send(org.mockito.ArgumentMatchers.eq("9822222233"), anyString());
        doThrow(new IllegalStateException("smtp down"))
                .when(mail)
                .sendAccountChanged(any(), any(), any());
        Assertions.assertThatCode(() -> alerts.changed(user, null, "password"))
                .doesNotThrowAnyException();
    }

    @Test
    void anAccountWithoutContactsIsToldNothing() {
        AppUser bare = mock(AppUser.class);
        when(bare.getRegisteredEmail()).thenReturn(" ");
        when(bare.getRegisteredMobile()).thenReturn(null);
        alerts.changed(bare, null, "password");
        verify(mail, never()).sendAccountChanged(any(), any(), any());
        verify(sms, never()).send(any(), any());
    }

    @Test
    void anAccountWithoutAnEmailOrWithABlankMobileIsToldNothingThere() {
        AppUser bare = mock(AppUser.class);
        when(bare.getRegisteredEmail()).thenReturn(null);
        when(bare.getRegisteredMobile()).thenReturn("   ");
        alerts.changed(bare, null, "password");
        verify(mail, never()).sendAccountChanged(any(), any(), any());
        verify(sms, never()).send(any(), any());
    }
}
