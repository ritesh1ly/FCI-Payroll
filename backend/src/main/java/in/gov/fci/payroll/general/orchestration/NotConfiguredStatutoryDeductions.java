package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.general.business.StatutoryDeductions;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Decision D-5: the statutory deduction rules (modules 08 income tax / PT, 07 CPF / EPS) need the
 * payroll engines' year (module 16), so every payment's deductions are "not configured".
 * TODO(module 16 with 07 / 08): replace with the real rules.
 */
@Component
public class NotConfiguredStatutoryDeductions implements StatutoryDeductions {

    @Override
    public Optional<BigDecimal> due(Payment payment) {
        return Optional.empty();
    }
}
