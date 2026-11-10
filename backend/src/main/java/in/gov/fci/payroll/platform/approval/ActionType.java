package in.gov.fci.payroll.platform.approval;

/** {@code approval_queue.action_type}: what a maker's submission does to its entity. */
public enum ActionType {
    CREATE,
    UPDATE,
    DEACTIVATE,
    END_DATE,
    APPROVE,
    ACTIVATE,
    UNDO,
    RELEASE
}
