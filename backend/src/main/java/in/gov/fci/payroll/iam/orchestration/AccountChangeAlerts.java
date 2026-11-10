package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.platform.notification.NotificationSender;
import in.gov.fci.payroll.platform.notification.SmsSender;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tells the owner of an account that an administrator changed how it signs in (A-01: the registered
 * mobile, a temporary password). Security review 2026-10-31: an administrator who sets another
 * user's mobile and password could sign in as that user unseen; the alert goes to the account's own
 * e-mail and to the mobile it had BEFORE the change, so the owner learns of it. Sent after the
 * change commits (a refused change alerts nobody); a channel failure is logged, never thrown.
 */
@Component
public class AccountChangeAlerts {

    private final NotificationSender notifications;
    private final SmsSender sms;

    public AccountChangeAlerts(NotificationSender notifications, SmsSender sms) {
        this.notifications = notifications;
        this.sms = sms;
    }

    /**
     * @param previousMobile the mobile registered before the change (null: the current one)
     * @param what what changed, as the message names it
     */
    public void changed(AppUser user, String previousMobile, String what) {
        String email = user.getRegisteredEmail();
        String name = user.getDisplayName();
        String mobile = previousMobile != null ? previousMobile : user.getRegisteredMobile();
        Runnable send =
                () -> {
                    try {
                        if (email != null && !email.isBlank()) {
                            notifications.sendAccountChanged(email, name, what);
                        }
                        if (mobile != null && !mobile.isBlank()) {
                            sms.send(
                                    mobile,
                                    "FCILekha: an administrator changed the "
                                            + what
                                            + " of your account. Not you? Contact your"
                                            + " administrator at once.");
                        }
                    } catch (RuntimeException failure) {
                        LoggerFactory.getLogger(AccountChangeAlerts.class)
                                .warn(
                                        "event=NOTIFICATION_FAILED type=ACCOUNT_CHANGED"
                                                + " exception={}",
                                        failure.getClass().getName());
                    }
                };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            send.run();
                        }
                    });
        } else {
            send.run();
        }
    }
}
