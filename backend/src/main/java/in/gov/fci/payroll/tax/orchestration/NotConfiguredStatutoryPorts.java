package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.tax.business.PostingLedger;
import in.gov.fci.payroll.tax.business.ReturnValidator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The posting interface (module 23) and the prescribed return validation utility (external) are not
 * available, so no posted TDS, no remittance and no utility verdict exist: certificates and returns
 * are generated and checked, but never released on assumed figures (plan 08 §0). TODO(module 23 /
 * FCI): replace with the posting interface and the installed utility.
 */
public final class NotConfiguredStatutoryPorts {

    private NotConfiguredStatutoryPorts() {}

    @Component
    public static class Ledger implements PostingLedger {

        @Override
        public Optional<BigDecimal> postedTds(Long orgUnitId, Collection<String> months) {
            return Optional.empty();
        }

        @Override
        public Optional<Boolean> remitted(String cinNumber, String bsrCode, LocalDate challanDate) {
            return Optional.empty();
        }
    }

    @Component
    public static class Validator implements ReturnValidator {

        @Override
        public Optional<Verdict> validate(Long returnId) {
            return Optional.empty();
        }
    }
}
