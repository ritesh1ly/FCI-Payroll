package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.general.api.ExceptionWorklist;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raises a CPF condition on the X-05 worklist in its own transaction, so the entry stays when the
 * posting that found it is refused and rolled back (SRS-6-59: "raise a missing or duplicate code on
 * the Exceptions &amp; Holds Worklist"). The worklist itself does not duplicate an unsettled entry.
 */
@Component
public class CpfExceptions {

    private final ExceptionWorklist worklist;

    public CpfExceptions(ExceptionWorklist worklist) {
        this.worklist = worklist;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long raise(String actor, ExceptionWorklist.NewEntry entry) {
        return worklist.raise(actor, entry);
    }
}
