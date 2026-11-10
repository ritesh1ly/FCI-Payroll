package in.gov.fci.payroll.payroll.api;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SRS-15-35 / FRS BR-SEC-007 (SRS label BR-SEC-006, CLR-43) (plan 05 phase 05h, D-16): the check
 * every payment release calls before it releases anything. An open suspension (DDL {@code
 * disb_release_suspension}, {@code resumed_at} null) whose scope covers the unit (ALL_UNITS, or the
 * unit itself) and whose channel covers the release (ALL, or that channel) blocks it. Bank-file
 * generation, EBS AP invoice release and APPS remittance transmission (modules 15 / 23) call it in
 * their own transaction; the O-05 release of a settlement calls {@link #requireNoPaymentSuspended}
 * at initiation and at approval.
 */
@Component
public class ReleaseSuspensions {

    /** DDL {@code disb_release_suspension.release_channel}, without ALL. */
    public enum Channel {
        BANK_FILE,
        EBS_AP_POSTING,
        APPS_REMITTANCE
    }

    private final JdbcTemplate jdbc;

    public ReleaseSuspensions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The open suspensions blocking {@code channel} at {@code unitId}, newest first. */
    public List<Long> blocking(long unitId, Channel channel) {
        return jdbc.queryForList(
                "SELECT suspension_id FROM disb_release_suspension WHERE resumed_at IS NULL AND"
                        + " (scope_type = 'ALL_UNITS' OR org_unit_id = ?) AND release_channel IN"
                        + " ('ALL', ?) ORDER BY suspension_id DESC",
                Long.class,
                unitId,
                channel.name());
    }

    /**
     * Refuses a release while a suspension covers it.
     *
     * @throws FciException SUS-409-SUSPENDED naming the channel and the suspension
     */
    public void requireNotSuspended(long unitId, Channel channel) {
        List<Long> open = blocking(unitId, channel);
        if (!open.isEmpty()) {
            throw new FciException(
                    ApiErrorCode.RELEASE_SUSPENDED,
                    new Object[] {channel.name(), open.get(0)},
                    List.of(),
                    null);
        }
    }

    /**
     * SRS-15-35 ("Block every release while suspended"): a payment the sources give no single
     * channel for, an F&amp;F settlement paid by neither a bank-advice run nor a remittance, is
     * stopped by a suspension of either payment channel. APPS remittance is not a payment to the
     * member, so it does not stop this release. OPEN-QUESTION: FCI to confirm (PENDING 17-release).
     */
    public void requireNoPaymentSuspended(long unitId) {
        requireNotSuspended(unitId, Channel.BANK_FILE);
        requireNotSuspended(unitId, Channel.EBS_AP_POSTING);
    }
}
