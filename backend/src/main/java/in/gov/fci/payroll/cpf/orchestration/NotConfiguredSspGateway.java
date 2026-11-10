package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.cpf.business.SspGateway;
import org.springframework.stereotype.Component;

/**
 * Plan 07 D-3: the CPF SSP interface belongs to module 23 (mechanism open, KB DB-7), so the portal
 * is "not configured" and every change is logged SKIPPED. TODO(module 23): replace with the real
 * interface adapter (tracker API-07-5).
 */
@Component
public class NotConfiguredSspGateway implements SspGateway {

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public void send(Change change, String idempotencyKey) {
        throw new IllegalStateException("The CPF SSP interface is not configured");
    }
}
