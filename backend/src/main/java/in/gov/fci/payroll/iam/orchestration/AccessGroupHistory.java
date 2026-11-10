package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.audit.api.RecordHistorySource.Version;
import in.gov.fci.payroll.iam.business.model.AccessGroup;
import in.gov.fci.payroll.iam.business.model.AccessGroupScope;
import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * A-03 access groups as X-04 / History-button records (SRS-20-29; access group changes are audited,
 * SRS-20-27), key = group code. A group is defined once (approved) and end-dated, never deleted
 * (SRS-G-37): its one approved definition is its one version, from its first day to its last.
 */
@Component
public class AccessGroupHistory implements RecordHistorySource {

    static final String ENTITY = "access_group";
    static final String SCREEN = "A-03";

    private final AccessRepositories.Groups groups;
    private final AccessRepositories.GroupScopes scopes;
    private final AccessRepositories.Roles roles;
    private final ApprovalStatuses statuses;

    public AccessGroupHistory(
            AccessRepositories.Groups groups,
            AccessRepositories.GroupScopes scopes,
            AccessRepositories.Roles roles,
            ApprovalStatuses statuses) {
        this.groups = groups;
        this.scopes = scopes;
        this.roles = roles;
        this.statuses = statuses;
    }

    @Override
    public String entity() {
        return ENTITY;
    }

    @Override
    public Optional<HistoryRecord> find(String key) {
        return groups.findByCode(key)
                .map(
                        g -> {
                            // A group seeded with the system has no approval item: in force as
                            // seeded.
                            boolean approved =
                                    g.getApprovalId() == null
                                            || statuses.of(List.of(g.getApprovalId()))
                                                            .get(g.getApprovalId())
                                                    == ApprovalStatus.APPROVED;
                            return HistoryRecord.ofVersions(
                                    ENTITY,
                                    g.getCode(),
                                    SCREEN,
                                    null,
                                    List.of(g.getCode()),
                                    approved
                                            ? List.of(
                                                    new Version(
                                                            1,
                                                            g.getEffectiveFrom(),
                                                            g.getEffectiveTo(),
                                                            g.getApprovalId(),
                                                            image(g)))
                                            : List.of());
                        });
    }

    /** What A-03 enters for a group: name, role, scope and its offices / zones, access mode. */
    private Map<String, String> image(AccessGroup g) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("group_name", g.getName());
        image.put(
                "role",
                roles.findById(g.getRoleId())
                        .map(AppRole::getCode)
                        .orElse(String.valueOf(g.getRoleId())));
        image.put("scope_type", g.getScopeType() == null ? null : g.getScopeType().name());
        image.put("consolidated_view", g.isConsolidatedView() ? "Y" : "N");
        image.put("access_mode", g.getAccessMode() == null ? null : g.getAccessMode().name());
        List<AccessGroupScope> rows = scopes.findByAccessGroupIdIn(List.of(g.getId()));
        image.put(
                "scope_locations",
                rows.stream()
                        .map(AccessGroupScope::getLocationId)
                        .filter(Objects::nonNull)
                        .sorted()
                        .map(String::valueOf)
                        .collect(Collectors.joining(",")));
        image.put(
                "scope_zones",
                rows.stream()
                        .map(AccessGroupScope::getZoneId)
                        .filter(Objects::nonNull)
                        .sorted()
                        .map(String::valueOf)
                        .collect(Collectors.joining(",")));
        return image;
    }
}
