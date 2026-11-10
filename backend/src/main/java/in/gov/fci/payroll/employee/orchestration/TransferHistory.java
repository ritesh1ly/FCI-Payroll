package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.employee.business.model.EmployeeTransfer;
import in.gov.fci.payroll.employee.persistence.EventRepositories;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * M-11 staff transfers as X-04 / History-button records (SRS-20-29; SRS-16-18), key = the transfer
 * id, at the releasing office (scope). A transfer is one row recorded once and decided through its
 * approval item: its history is the proposal and the decision audited under the transfer id; the
 * employee's change of office is audited on the employee's own record (M-01). Its particulars are
 * never changed after recording, so every as-of date shows them as recorded.
 */
@Component
public class TransferHistory implements RecordHistorySource {

    static final String SCREEN = "M-11";

    private final EventRepositories.Transfers transfers;

    public TransferHistory(EventRepositories.Transfers transfers) {
        this.transfers = transfers;
    }

    @Override
    public String entity() {
        return EmployeeEventService.TRANSFER_ENTITY;
    }

    @Override
    public Optional<HistoryRecord> find(String key) {
        long id;
        try {
            id = Long.parseLong(key);
        } catch (NumberFormatException notAnId) {
            return Optional.empty();
        }
        return transfers
                .findById(id)
                .map(
                        t ->
                                HistoryRecord.ofCurrent(
                                        EmployeeEventService.TRANSFER_ENTITY,
                                        key,
                                        SCREEN,
                                        t.getFromLocationId(),
                                        image(t)));
    }

    private static Map<String, String> image(EmployeeTransfer t) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("from_location_id", Objects.toString(t.getFromLocationId(), null));
        image.put("to_location_id", Objects.toString(t.getToLocationId(), null));
        image.put("transfer_date", day(t.getTransferDate()));
        image.put("relieving_date", day(t.getRelievingDate()));
        image.put("joining_date", day(t.getJoiningDate()));
        image.put("order_reference", t.getOrderReference());
        image.put("lpc_reference", t.getLpcReference());
        image.put("split_wage_flag", t.isSplitWageFlag() ? "Y" : "N");
        return image;
    }

    private static String day(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
