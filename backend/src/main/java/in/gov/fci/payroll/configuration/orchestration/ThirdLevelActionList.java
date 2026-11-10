package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.workflow.api.ThirdLevelActions;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * SRS-22-12: the approval actions the Maker-Checker-Approver matrix names a third level for, held
 * on M-16 as the reference list {@code THIRD_LEVEL_ACTION} (value code = approval action code),
 * effective-dated and changed under maker-checker like every other list.
 */
@Component
public class ThirdLevelActionList implements ThirdLevelActions {

    private final ReferenceData reference;

    public ThirdLevelActionList(ReferenceData reference) {
        this.reference = reference;
    }

    @Override
    public boolean named(String actionCode, LocalDate day) {
        return reference.contains(LIST, actionCode, day);
    }
}
