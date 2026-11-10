package in.gov.fci.payroll.workflow.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.type.YesNoConverter;

/**
 * {@code approval_workflow_level} (target DDL v4.0 S05): the RBAC role and office level of one
 * level of an approval action. Level 1 is the MAKER, 2 the CHECKER, 3 the APPROVER (BR-APPR-002); a
 * user acts at a level only through an access group covering that role and office.
 */
@Entity
@Table(name = "approval_workflow_level")
public class WorkflowLevel {

    /** {@code level_name}: fixed by the level number (chk_appr_wf_level_nm). */
    public enum Name {
        MAKER,
        CHECKER,
        APPROVER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "workflow_level_id")
    private Long id;

    @Column(name = "workflow_action_id", nullable = false)
    private Long workflowActionId;

    @Column(name = "level_no", nullable = false)
    private Integer levelNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "level_name", nullable = false, length = 20)
    private Name name;

    @Column(name = "role_code", nullable = false, length = 30)
    private String roleCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "office_level", nullable = false, length = 20)
    private WorkflowAction.OfficeLevel officeLevel;

    @Column(name = "condition_code", length = 40)
    private String conditionCode;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "otp_at_level", nullable = false, length = 1)
    private Boolean otpAtLevel;

    protected WorkflowLevel() {}

    /**
     * One level of a proposed version: 1 MAKER, 2 CHECKER, 3 APPROVER (DDL chk_appr_wf_level_nm).
     */
    public static WorkflowLevel of(
            Long workflowActionId,
            int levelNo,
            String roleCode,
            WorkflowAction.OfficeLevel officeLevel,
            boolean otpAtLevel) {
        WorkflowLevel l = new WorkflowLevel();
        l.workflowActionId = workflowActionId;
        l.levelNo = levelNo;
        l.name = levelNo == 1 ? Name.MAKER : levelNo == 2 ? Name.CHECKER : Name.APPROVER;
        l.roleCode = roleCode;
        l.officeLevel = officeLevel;
        l.otpAtLevel = otpAtLevel;
        return l;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkflowActionId() {
        return workflowActionId;
    }

    public int getLevelNo() {
        return levelNo;
    }

    public Name getName() {
        return name;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public WorkflowAction.OfficeLevel getOfficeLevel() {
        return officeLevel;
    }

    public String getConditionCode() {
        return conditionCode;
    }

    /** The decider at this level needs a fresh OTP. */
    public boolean isOtpAtLevel() {
        return Boolean.TRUE.equals(otpAtLevel);
    }
}
