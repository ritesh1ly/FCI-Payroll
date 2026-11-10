package in.gov.fci.payroll.audit.business;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * X-04 "All objects" filter (Wireframes v6.1, SRS l.1226): which audit entities each drawn object
 * group covers. The groups the screen offers are the M-16 list AUDIT_OBJECT_GROUP (OD-123); this
 * class only says which recorded {@code audit_event.entity_name} values fall in a group, which is
 * structure of the schema, not a business value. An unknown code is no group at all (null), so a
 * tampered filter is refused by the caller rather than read as "every object".
 */
public final class AuditObjectGroups {

    private static final Map<String, List<String>> GROUPS =
            Map.of(
                    "PAY_EMP_MAST",
                    List.of(
                            "employee",
                            "employee_control_flag",
                            "employee_eligibility_flag",
                            "employee_lifecycle_event",
                            "employee_onboarding",
                            "employee_transfer",
                            "hrms_change_task",
                            "hrms_change_task_order"),
                    "LABOUR_MASTERS",
                    List.of(
                            "labour_profile",
                            "labour_gang",
                            "labour_min_wage",
                            "labour_norm",
                            "labour_transfer",
                            "labour_temp_attachment",
                            "nwnp_rate_set"),
                    "RULES_NORMS",
                    List.of(
                            "rule_version",
                            "rule_version_end",
                            "rule_simulation",
                            "rate_set",
                            "other_parameter",
                            "pay_component",
                            "allowance_ceiling_norm",
                            "net_pay_floor_rule",
                            "pf_interest_rate",
                            "reference_value",
                            "leave_pay_parameter"),
                    "WORK_SLIPS",
                    List.of(
                            "work_slip",
                            "labour_work_slip_batch",
                            "labour_attendance",
                            "labour_attendance_batch"),
                    "PAYROLL_CYCLE",
                    List.of(
                            "payroll_cycle_instance",
                            "payroll_calendar",
                            "cycle_run_order",
                            "cycle_lease",
                            "cycle_return",
                            "cycle_unfreeze",
                            "cycle_gate_evaluation",
                            "cycle_prepay_check_result",
                            "payroll_quarantine",
                            "payroll_period_lock",
                            "undo_reversal_register",
                            "disb_release_suspension"));

    private AuditObjectGroups() {}

    /** The group codes this build knows. */
    public static Set<String> codes() {
        return GROUPS.keySet();
    }

    /**
     * @return the audit entities of the group, or null for a code that names no group
     */
    public static List<String> entities(String code) {
        return GROUPS.get(code);
    }
}
