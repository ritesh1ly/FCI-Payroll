package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.payroll.api.NetPayFloorChecked;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * SRS-15-13 with the Rule source of the field-source taxonomy (SRS l.2028): "If no version is
 * effective for a period, the record is held in Exceptions & Holds (C-08). It is released when the
 * Rule Administrator adds the version and the Rule Approver activates it." An Actual run of a cycle
 * with no M-14 net-pay floor version in force for its period holds the cycle's records as one
 * cycle-level BLOCKING entry on the X-05 worklist holding gate G6 (once per cycle: a re-run does
 * not duplicate it); a later Actual run that finds a version in force releases it, naming the run.
 * No floor is ever assumed (R-RULE, CF-49).
 */
@Component
public class NetPayFloorHolds {

    static final String CODE = "NET_PAY_FLOOR_NOT_CONFIGURED";
    static final String ACTOR = "system.payroll";

    private final ExceptionWorklist worklist;

    public NetPayFloorHolds(ExceptionWorklist worklist) {
        this.worklist = worklist;
    }

    @EventListener
    public void on(NetPayFloorChecked checked) {
        if (checked.inForce()) {
            worklist.clear(
                    ACTOR,
                    CODE,
                    checked.cycleInstanceId(),
                    null,
                    "floor_value",
                    "Net-pay floor version in force for Actual run " + checked.runId());
            return;
        }
        worklist.raise(
                ACTOR,
                new ExceptionWorklist.NewEntry(
                        checked.cycleInstanceId(),
                        null,
                        "C-02",
                        CODE,
                        "BLOCKING",
                        "G6",
                        null,
                        "net_pay_floor_rule",
                        "floor_value",
                        "RULE",
                        "M-14",
                        "No net-pay floor version of "
                                + checked.payrollType()
                                + " is in force on "
                                + checked.periodDate()
                                + ": held until the Rule Administrator adds the version and the"
                                + " Rule Approver activates it."));
    }
}
