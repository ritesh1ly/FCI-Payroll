package in.gov.fci.payroll.platform.approval;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a maker submits for checking.
 *
 * @param workflowAction the approval action whose levels route it ({@code
 *     approval_workflow_action.action_code}); by default {@code <screen>_<action>}, e.g. M01_EDIT
 * @param moduleCode the screen code whose permission the maker needs (also {@code
 *     approval_queue.module_code})
 * @param actionCode the permission action on that screen (CREATE, EDIT, LOCK …)
 * @param entityName the table the change belongs to; its module applies the decision
 * @param entityKey the key of the changed row, as text
 * @param locationId the location the change belongs to (routing and scope)
 * @param payload the proposed change (JSON), or null
 * @param impactValue the value or impact the change carries, shown to the checker in the inbox
 *     (SRS-22-4, {@code approval_queue.impact_value}); never used for routing (BR-APPR-003); null
 *     if the change has no amount
 */
public record Submission(
        String workflowAction,
        String moduleCode,
        String actionCode,
        String entityName,
        String entityKey,
        ActionType actionType,
        Long locationId,
        String payload,
        LocalDate effectiveDate,
        String reason,
        BigDecimal impactValue) {

    /** A submission without an amount (most master and access changes). */
    public Submission(
            String workflowAction,
            String moduleCode,
            String actionCode,
            String entityName,
            String entityKey,
            ActionType actionType,
            Long locationId,
            String payload,
            LocalDate effectiveDate,
            String reason) {
        this(
                workflowAction,
                moduleCode,
                actionCode,
                entityName,
                entityKey,
                actionType,
                locationId,
                payload,
                effectiveDate,
                reason,
                null);
    }

    /** The same submission carrying the value or impact the checker sees (SRS-22-4). */
    public Submission withImpact(BigDecimal value) {
        return new Submission(
                workflowAction,
                moduleCode,
                actionCode,
                entityName,
                entityKey,
                actionType,
                locationId,
                payload,
                effectiveDate,
                reason,
                value);
    }

    /** A submission routed by the workflow action of its screen and action. */
    public Submission(
            String moduleCode,
            String actionCode,
            String entityName,
            String entityKey,
            ActionType actionType,
            Long locationId,
            String payload,
            LocalDate effectiveDate,
            String reason) {
        this(
                workflowActionOf(moduleCode, actionCode),
                moduleCode,
                actionCode,
                entityName,
                entityKey,
                actionType,
                locationId,
                payload,
                effectiveDate,
                reason);
    }

    /**
     * The default workflow action code of a screen action: {@code M-01} + {@code EDIT} = {@code
     * M01_EDIT}.
     */
    public static String workflowActionOf(String screenCode, String actionCode) {
        return screenCode.replace("-", "") + "_" + actionCode;
    }
}
