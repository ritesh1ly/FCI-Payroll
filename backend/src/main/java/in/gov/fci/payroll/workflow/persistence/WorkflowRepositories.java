package in.gov.fci.payroll.workflow.persistence;

import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.workflow.business.model.ApprovalDecision;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.business.model.ScreenAction;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction;
import in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem;
import in.gov.fci.payroll.workflow.business.model.WorkflowLevel;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Target DDL v4.0 S05: approval workflow master (actions, levels), screen action inventory,
 * approval queue, decision trail and inbox.
 */
public final class WorkflowRepositories {

    private WorkflowRepositories() {}

    public interface Actions extends Repository<WorkflowAction, Long> {
        Optional<WorkflowAction> findById(Long id);

        List<WorkflowAction> findByActionCodeOrderByVersionNoDesc(String actionCode);

        /** Every version of one action at one office level, newest first (A-08). */
        List<WorkflowAction> findByActionCodeAndOfficeLevelOrderByVersionNoDesc(
                String actionCode, WorkflowAction.OfficeLevel officeLevel);

        List<WorkflowAction> findAll();

        WorkflowAction save(WorkflowAction action);

        void flush();
    }

    public interface Levels extends Repository<WorkflowLevel, Long> {
        List<WorkflowLevel> findByWorkflowActionIdOrderByLevelNo(Long workflowActionId);

        List<WorkflowLevel> findByWorkflowActionIdIn(java.util.Collection<Long> workflowActionIds);

        WorkflowLevel save(WorkflowLevel level);
    }

    public interface Decisions extends Repository<ApprovalDecision, Long> {
        List<ApprovalDecision> findByApprovalIdOrderByDecidedAt(Long approvalId);

        ApprovalDecision save(ApprovalDecision decision);
    }

    public interface ScreenActions extends Repository<ScreenAction, Long> {
        List<ScreenAction> findAllByOrderByScreenCodeAscActionCodeAsc();

        Optional<ScreenAction> findByScreenCodeAndActionCode(String screen, String action);
    }

    public interface Approvals extends Repository<ApprovalItem, Long> {
        Optional<ApprovalItem> findById(Long id);

        List<ApprovalItem> findAllById(Iterable<Long> ids);

        /**
         * One page of a maker's submissions, newest first (W-02): at Headquarters ({@code
         * national}) all of them; elsewhere those whose task is at an office of the scope, and
         * national items (no task) always.
         */
        @Query(
                value =
                        "select a from ApprovalItem a where a.submittedBy = :login and (:national ="
                            + " true or not exists (select i from WorkflowInboxItem i where"
                            + " i.approvalId = a.id) or exists (select i from WorkflowInboxItem i"
                            + " where i.approvalId = a.id and i.orgScopeId in :scope)) order by"
                            + " a.submittedAt desc, a.id desc",
                countQuery =
                        "select count(a) from ApprovalItem a where a.submittedBy = :login and"
                            + " (:national = true or not exists (select i from WorkflowInboxItem i"
                            + " where i.approvalId = a.id) or exists (select i from"
                            + " WorkflowInboxItem i where i.approvalId = a.id and i.orgScopeId in"
                            + " :scope))")
        Page<ApprovalItem> findMine(
                @Param("login") String login,
                @Param("national") boolean national,
                @Param("scope") Collection<Long> scope,
                Pageable page);

        boolean existsByEntityNameAndEntityKeyAndStatus(
                String entity, String key, ApprovalStatus status);

        /** SRS-15-29: the pending change of a record, to name who submitted it and when. */
        Optional<ApprovalItem> findFirstByEntityNameAndEntityKeyAndStatusOrderBySubmittedAtDesc(
                String entity, String key, ApprovalStatus status);

        List<ApprovalItem> findByEntityNameAndStatusOrderBySubmittedAtDesc(
                String entity, ApprovalStatus status);

        /** The items of one record in a status, newest first (the id breaks a tie). */
        List<ApprovalItem> findByEntityNameAndEntityKeyAndStatusOrderBySubmittedAtDescIdDesc(
                String entity, String key, ApprovalStatus status);

        /**
         * W-01 tiles "Approved This Cycle" / "Returned": the items decided in a status since the
         * start of the cycle, in the scope the inbox shows (an item's office is its task's;
         * national items always; everything at Headquarters).
         */
        @Query(
                "select count(a) from ApprovalItem a where a.status = :status and a.decidedAt >="
                    + " :since and (:national = true or not exists (select i from WorkflowInboxItem"
                    + " i where i.approvalId = a.id) or exists (select i from WorkflowInboxItem i"
                    + " where i.approvalId = a.id and i.orgScopeId in :scope))")
        long countDecidedSince(
                @Param("status") ApprovalStatus status,
                @Param("since") Instant since,
                @Param("national") boolean national,
                @Param("scope") Collection<Long> scope);

        ApprovalItem save(ApprovalItem item);
    }

    public interface Inbox extends Repository<WorkflowInboxItem, Long> {
        Optional<WorkflowInboxItem> findByApprovalId(Long approvalId);

        /** One page of the open tasks of the roles at the offices, the soonest due first (W-01). */
        @Query(
                value =
                        "select i from WorkflowInboxItem i where i.status = :status"
                                + " and i.assignedRoleCode in :roles and i.orgScopeId in :locations"
                                + " order by i.slaDueAt asc nulls last, i.approvalId asc",
                countQuery =
                        "select count(i) from WorkflowInboxItem i where i.status = :status and"
                                + " i.assignedRoleCode in :roles and i.orgScopeId in :locations")
        Page<WorkflowInboxItem> findOpen(
                @Param("status") WorkflowInboxItem.Status status,
                @Param("roles") Collection<String> roles,
                @Param("locations") Collection<Long> locations,
                Pageable page);

        @Query("select i from WorkflowInboxItem i where i.status = :status")
        List<WorkflowInboxItem> findByStatus(@Param("status") WorkflowInboxItem.Status status);

        /** W-01 tile "Pending Approval": the open tasks of the roles at the offices. */
        @Query(
                "select count(i) from WorkflowInboxItem i where i.status = :status and"
                        + " i.assignedRoleCode in :roles and i.orgScopeId in :locations")
        long countOpen(
                @Param("status") WorkflowInboxItem.Status status,
                @Param("roles") Collection<String> roles,
                @Param("locations") Collection<Long> locations);

        /** W-01 tile "Past target turnaround": the open tasks past their target (SRS-22-4). */
        @Query(
                "select count(i) from WorkflowInboxItem i where i.status = :status and"
                        + " i.assignedRoleCode in :roles and i.orgScopeId in :locations and"
                        + " i.slaBreached = true")
        long countOpenPastTarget(
                @Param("status") WorkflowInboxItem.Status status,
                @Param("roles") Collection<String> roles,
                @Param("locations") Collection<Long> locations);

        /** W-01 toolbar: the modules of the open tasks (the "All modules" filter). */
        @Query(
                "select distinct a.moduleCode from ApprovalItem a, WorkflowInboxItem i where"
                        + " i.approvalId = a.id and i.status = :status and i.assignedRoleCode in"
                        + " :roles and i.orgScopeId in :locations and a.moduleCode is not null"
                        + " order by a.moduleCode")
        List<String> openModules(
                @Param("status") WorkflowInboxItem.Status status,
                @Param("roles") Collection<String> roles,
                @Param("locations") Collection<Long> locations);

        WorkflowInboxItem save(WorkflowInboxItem item);
    }
}
