package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.audit.api.RecordHistorySource.Version;
import in.gov.fci.payroll.cpf.business.model.PfInterestRate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * M-19 PF interest rates as X-04 / History-button records (SRS-20-29), key = the series {@code
 * year|fund|source} (e.g. {@code 2026-27|CPF|FCI_TRUST}): each approved rate of the series is a
 * version, oldest first. A national record: the rates apply corporation-wide.
 */
@Component
public class InterestRateHistory implements RecordHistorySource {

    private final InterestRateService service;

    public InterestRateHistory(InterestRateService service) {
        this.service = service;
    }

    @Override
    public String entity() {
        return InterestRateService.ENTITY;
    }

    @Override
    public Optional<HistoryRecord> find(String key) {
        List<PfInterestRate> series = service.seriesOf(key);
        if (series.isEmpty()) {
            return Optional.empty();
        }
        List<Version> versions = new ArrayList<>();
        for (int i = 0; i < series.size(); i++) {
            PfInterestRate r = series.get(i);
            versions.add(
                    new Version(
                            i + 1,
                            r.getAppliesFrom(),
                            r.getAppliesTo(),
                            r.getApprovalId(),
                            InterestRateService.image(r)));
        }
        return Optional.of(
                HistoryRecord.ofVersions(
                        InterestRateService.ENTITY,
                        key,
                        InterestRateService.SCREEN,
                        null,
                        List.of(key),
                        versions));
    }
}
