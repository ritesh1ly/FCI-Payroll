package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.employee.business.model.EmployeeTransfer;
import in.gov.fci.payroll.employee.persistence.EventRepositories;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * M-11 staff transfers on X-04 and the M-11 History button (SRS-20-29; SRS-16-18): a transfer is
 * one row at its releasing office, its particulars as recorded (a date not given shown as none); a
 * key that is no transfer id, or an unknown one, is no record.
 */
class TransferHistoryTest {

    private final EventRepositories.Transfers transfers = mock(EventRepositories.Transfers.class);
    private final TransferHistory history = new TransferHistory(transfers);

    @Test
    void SRS_20_29_aTransferIsOneRowAtItsReleasingOffice() {
        EmployeeTransfer t =
                EmployeeTransfer.record(
                        5L,
                        11L,
                        12L,
                        1L,
                        2L,
                        LocalDate.of(2026, 10, 1),
                        LocalDate.of(2026, 9, 30),
                        null,
                        null,
                        "Administrative",
                        null,
                        "ORD/12",
                        true,
                        10,
                        20);
        when(transfers.findById(40L)).thenReturn(Optional.of(t));
        assertThat(history.entity()).isEqualTo("employee_transfer");
        HistoryRecord record = history.find("40").orElseThrow();
        assertThat(record.isVersioned()).isFalse();
        assertThat(record.screen()).isEqualTo("M-11");
        assertThat(record.locationId()).isEqualTo(11L);
        assertThat(record.auditKeys()).containsExactly("40");
        assertThat(record.current())
                .containsEntry("from_location_id", "11")
                .containsEntry("to_location_id", "12")
                .containsEntry("transfer_date", "2026-10-01")
                .containsEntry("relieving_date", "2026-09-30")
                .containsEntry("joining_date", null)
                .containsEntry("order_reference", "ORD/12")
                .containsEntry("split_wage_flag", "Y");
    }

    @Test
    void SRS_20_29_aKeyThatIsNoTransferIdOrAnUnknownOneIsNoRecord() {
        when(transfers.findById(41L)).thenReturn(Optional.empty());
        assertThat(history.find("T-41")).isEmpty();
        assertThat(history.find("41")).isEmpty();
    }

    /** SRS-20-29: a transfer without a split wage shows the flag as N. */
    @Test
    void SRS_20_29_aTransferWithoutASplitWageShowsN() {
        EmployeeTransfer t =
                EmployeeTransfer.record(
                        5L,
                        11L,
                        12L,
                        1L,
                        2L,
                        LocalDate.of(2026, 10, 1),
                        LocalDate.of(2026, 9, 30),
                        LocalDate.of(2026, 10, 2),
                        null,
                        "Administrative",
                        null,
                        "ORD/13",
                        false,
                        null,
                        null);
        when(transfers.findById(42L)).thenReturn(Optional.of(t));
        assertThat(history.find("42").orElseThrow().current())
                .containsEntry("split_wage_flag", "N")
                .containsEntry("joining_date", "2026-10-02");
    }
}
