package in.gov.fci.payroll.workflow.orchestration;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.audit.api.RecordHistorySource.Version;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction;
import in.gov.fci.payroll.workflow.business.model.WorkflowLevel;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A-08 approval routes as X-04 / History-button records (SRS-20-3, SRS-20-29): one route is an
 * action code at an office level, key {@code ACTION_CODE@OFFICE_LEVEL}, kept as effective-dated
 * versions; its approved (ACTIVE) versions are its history. The image is what the A-08 form enters.
 */
@Configuration(proxyBeanMethods = false)
public class WorkflowHistory {

    /** The record key of a route. */
    static String key(WorkflowAction action) {
        return action.getActionCode() + "@" + action.getOfficeLevel().name();
    }

    /** The fields of one route version and its levels, as A-08 enters them (SRS-20-2). */
    static Map<String, String> image(WorkflowAction a, List<WorkflowLevel> levels) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("action_name", a.getActionName());
        image.put("module_code", a.getModuleCode());
        image.put("screen_code", a.getScreenCode());
        image.put("level_count", String.valueOf(a.getLevelCount()));
        image.put("otp_required", a.isOtpRequired() ? "Y" : "N");
        image.put("otp_action_code", a.getOtpAction() == null ? null : a.getOtpAction().name());
        image.put("attachment_required", a.isAttachmentRequired() ? "Y" : "N");
        image.put(
                "target_tat_hours",
                a.getTargetTatHours() == null ? null : String.valueOf(a.getTargetTatHours()));
        levels.stream()
                .sorted(Comparator.comparingInt(WorkflowLevel::getLevelNo))
                .forEach(
                        l ->
                                image.put(
                                        "level_" + l.getLevelNo(),
                                        l.getName()
                                                + " "
                                                + l.getRoleCode()
                                                + "@"
                                                + (l.getOfficeLevel() == null
                                                        ? ""
                                                        : l.getOfficeLevel().name())
                                                + (l.isOtpAtLevel() ? " OTP" : "")));
        return image;
    }

    @Bean
    RecordHistorySource workflowRouteHistory(
            WorkflowRepositories.Actions actions, WorkflowRepositories.Levels levels) {
        return RecordHistorySource.of(
                WorkflowMasterService.ENTITY,
                key -> {
                    int at = key.lastIndexOf('@');
                    if (at <= 0) {
                        return Optional.empty();
                    }
                    WorkflowAction.OfficeLevel level;
                    try {
                        level = WorkflowAction.OfficeLevel.valueOf(key.substring(at + 1));
                    } catch (IllegalArgumentException unknownLevel) {
                        return Optional.empty();
                    }
                    List<WorkflowAction> rows =
                            actions.findByActionCodeAndOfficeLevelOrderByVersionNoDesc(
                                    key.substring(0, at), level);
                    if (rows.isEmpty()) {
                        return Optional.empty();
                    }
                    Map<Long, List<WorkflowLevel>> levelsOf =
                            levels
                                    .findByWorkflowActionIdIn(
                                            rows.stream().map(WorkflowAction::getId).toList())
                                    .stream()
                                    .collect(
                                            Collectors.groupingBy(
                                                    WorkflowLevel::getWorkflowActionId));
                    // Rows of the proposals were written under the version id; applied changes
                    // under the route key.
                    List<String> auditKeys = new ArrayList<>();
                    auditKeys.add(key);
                    rows.forEach(r -> auditKeys.add(String.valueOf(r.getId())));
                    return Optional.of(
                            HistoryRecord.ofVersions(
                                    WorkflowMasterService.ENTITY,
                                    key,
                                    WorkflowMasterService.SCREEN,
                                    null,
                                    auditKeys,
                                    rows.stream()
                                            .filter(
                                                    r ->
                                                            r.getStatus()
                                                                    == WorkflowAction.Status.ACTIVE)
                                            .map(
                                                    r ->
                                                            new Version(
                                                                    r.getVersionNo(),
                                                                    r.getEffectiveFrom(),
                                                                    r.getEffectiveTo(),
                                                                    r.getApprovalId(),
                                                                    image(
                                                                            r,
                                                                            levelsOf.getOrDefault(
                                                                                    r.getId(),
                                                                                    List.of()))))
                                            .toList()));
                });
    }
}
