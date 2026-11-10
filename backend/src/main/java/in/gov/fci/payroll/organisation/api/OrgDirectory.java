package in.gov.fci.payroll.organisation.api;

import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgDepartment;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.organisation.business.model.OrgZone;
import in.gov.fci.payroll.organisation.persistence.OrgRepositories;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.cache.VersionedCache;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The office hierarchy ({@code org_location.parent_location_id}) in memory: subtree, zone and
 * ancestry queries for scope resolution. One snapshot per instance (cache region ORGANISATION):
 * reloaded when any instance marks the region changed ({@link #evict()} after an office import or
 * an approved change) and at the latest after {@code fci.cache.max-age}.
 */
@Component
public class OrgDirectory {

    /** One consistent view of the organisation. */
    public record Snapshot(
            Map<Long, OrgLocation> locations,
            Map<String, OrgLocation> byCode,
            Map<Long, List<Long>> children,
            Map<Long, OrgZone> zones,
            Map<Long, OrgDepartment> departments) {}

    public static final String REGION = "ORGANISATION";

    private final VersionedCache<String, Snapshot> cache;
    private final OrgRepositories.Locations locations;
    private final OrgRepositories.Zones zones;
    private final OrgRepositories.Departments departments;

    public OrgDirectory(
            OrgRepositories.Locations locations,
            OrgRepositories.Zones zones,
            OrgRepositories.Departments departments,
            CacheRegions regions) {
        this.locations = locations;
        this.zones = zones;
        this.departments = departments;
        this.cache = VersionedCache.of(REGION, regions, 1);
    }

    private static Snapshot load(
            OrgRepositories.Locations locations,
            OrgRepositories.Zones zones,
            OrgRepositories.Departments departments) {
        Map<Long, OrgLocation> byId = new HashMap<>();
        Map<String, OrgLocation> byCode = new HashMap<>();
        Map<Long, List<Long>> children = new HashMap<>();
        for (OrgLocation location : locations.findAllByOrderByCode()) {
            byId.put(location.getId(), location);
            byCode.put(location.getCode(), location);
        }
        for (OrgLocation location : byId.values()) {
            if (location.getParentId() != null) {
                children.computeIfAbsent(location.getParentId(), k -> new java.util.ArrayList<>())
                        .add(location.getId());
            }
        }
        Map<Long, OrgZone> zoneMap =
                zones.findAllByOrderByCode().stream()
                        .collect(Collectors.toMap(OrgZone::getId, z -> z));
        Map<Long, OrgDepartment> departmentMap =
                departments.findAllByOrderByCode().stream()
                        .collect(Collectors.toMap(OrgDepartment::getId, d -> d));
        return new Snapshot(
                Map.copyOf(byId), Map.copyOf(byCode), Map.copyOf(children), zoneMap, departmentMap);
    }

    public Snapshot snapshot() {
        return cache.get("org", key -> load(locations, zones, departments));
    }

    /** The region's current version: read before {@link #snapshot} for an HTTP entity tag. */
    public long version() {
        return cache.version();
    }

    /**
     * After a change of offices, zones or departments: every instance reloads (in the caller's
     * transaction).
     */
    public void evict() {
        cache.changed("organisation");
    }

    public Optional<OrgLocation> location(Long id) {
        return id == null ? Optional.empty() : Optional.ofNullable(snapshot().locations().get(id));
    }

    public Optional<OrgLocation> byCode(String code) {
        return Optional.ofNullable(snapshot().byCode().get(code));
    }

    public Optional<OrgZone> zone(Long id) {
        return Optional.ofNullable(snapshot().zones().get(id));
    }

    /** The location and all its descendants (cycle-safe). */
    public Set<Long> subtree(Long root) {
        Snapshot s = snapshot();
        Set<Long> out = new LinkedHashSet<>();
        if (root == null || !s.locations().containsKey(root)) {
            return out;
        }
        Deque<Long> queue = new ArrayDeque<>(List.of(root));
        while (!queue.isEmpty()) {
            Long next = queue.removeFirst();
            if (out.add(next)) {
                queue.addAll(s.children().getOrDefault(next, List.of()));
            }
        }
        return out;
    }

    /** Every location of a zone. */
    public Set<Long> inZone(Long zoneId) {
        return snapshot().locations().values().stream()
                .filter(l -> l.getZoneId().equals(zoneId))
                .map(OrgLocation::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Set<Long> all() {
        return Set.copyOf(snapshot().locations().keySet());
    }

    /** The Headquarters location (the root of the hierarchy). */
    public Optional<OrgLocation> headquarters() {
        return snapshot().locations().values().stream()
                .filter(l -> l.getType() == LocationType.HQ && l.getParentId() == null)
                .findFirst();
    }

    /** The location itself, or its nearest ancestor, of {@code type} (cycle-safe). */
    public Optional<OrgLocation> ancestorOrSelf(Long locationId, LocationType type) {
        return nearest(locationId, l -> l.getType() == type);
    }

    /**
     * The payroll cycle unit a location's employees are paid in: the location itself or its nearest
     * ancestor marked {@code is_cycle_unit} (a depot, or a DO without its own Maker and Checker,
     * runs within its parent; FRS V0.5 §6.10.6).
     */
    public Optional<OrgLocation> cycleUnitOf(Long locationId) {
        return nearest(locationId, OrgLocation::isCycleUnit);
    }

    /**
     * The location itself or its nearest ancestor that is {@code wanted}; an unknown location, the
     * root, or a cycle in the parent links ends the walk with nothing.
     */
    private Optional<OrgLocation> nearest(Long locationId, Predicate<OrgLocation> wanted) {
        Snapshot s = snapshot();
        Set<Long> seen = new LinkedHashSet<>();
        OrgLocation current = locationId == null ? null : s.locations().get(locationId);
        while (current != null && seen.add(current.getId())) {
            if (wanted.test(current)) {
                return Optional.of(current);
            }
            // The snapshot's map is immutable: get(null) would throw, so the root stops here.
            current =
                    current.getParentId() == null ? null : s.locations().get(current.getParentId());
        }
        return Optional.empty();
    }

    /** The zonal office (ZO) of a zone, if one is recorded. */
    public Optional<OrgLocation> zonalOffice(Long zoneId) {
        return snapshot().locations().values().stream()
                .filter(l -> l.getType() == LocationType.ZO && l.getZoneId().equals(zoneId))
                .findFirst();
    }

    public List<OrgLocation> locations(Collection<Long> ids) {
        Snapshot s = snapshot();
        return ids.stream().map(s.locations()::get).filter(java.util.Objects::nonNull).toList();
    }
}
