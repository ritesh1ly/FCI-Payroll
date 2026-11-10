package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The CPF zone a session works for: the zone of its office ({@code org_location.zone_id}; the
 * Headquarters office is in zone HQ). SRS-6-45: the Zonal or Headquarters CPF Division runs the
 * interest of its own zone's members, whose ledgers carry that zone ({@code
 * employee_pf_header.zone_id}). Read from the cached organisation hierarchy ({@link OrgDirectory}:
 * Caffeine, reloaded on every approved M-11 change and periodically), not the database on every CPF
 * request (deep profile 2026-10-27).
 */
@Component
public class CpfZones {

    /** A zone: its id and code. */
    public record Zone(Long id, String code) {}

    private final JdbcTemplate jdbc;
    private final OrgDirectory org;

    public CpfZones(JdbcTemplate jdbc, OrgDirectory org) {
        this.jdbc = jdbc;
        this.org = org;
    }

    /** The zone of {@code locationId}; an office without one cannot run CPF interest. */
    public Zone of(Long locationId) {
        return org.location(locationId)
                .map(OrgLocation::getZoneId)
                .flatMap(org::zone)
                .map(z -> new Zone(z.getId(), z.getCode()))
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    /**
     * Locks the zone's row for the rest of the transaction: the runs and the freeze of a zone
     * serialise on it, so run numbers never collide and no run slips in beside a freeze.
     */
    public void lock(Long zoneId) {
        jdbc.queryForList("SELECT zone_id FROM org_zone WHERE zone_id = ? FOR UPDATE", zoneId);
    }
}
