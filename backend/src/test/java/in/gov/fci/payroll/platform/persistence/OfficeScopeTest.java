package in.gov.fci.payroll.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * SRS-G-2: the office scope of a register - all at Headquarters, the session's offices, or none.
 */
class OfficeScopeTest {

    private static AccessContext context(Long location, Set<Long> scope) {
        return new AccessContext(
                1L, "u@x", "U", "s", "P", location, null, List.of(), Map.of(), Set.of(), Set.of(),
                scope);
    }

    @Test
    void SRS_G_2_headquartersSeesEveryOfficeAUnitItsOwnAndASessionWithoutAnOfficeNothing() {
        MapSqlParameterSource hq = OfficeScope.of(context(1L, Set.of()), true);
        assertThat(hq.getValue("all")).isEqualTo("Y");
        assertThat(hq.getValue("locations")).isEqualTo(List.of(-1L));
        MapSqlParameterSource unit = OfficeScope.of(context(10L, Set.of(10L, 11L)), false);
        assertThat(unit.getValue("all")).isEqualTo("N");
        assertThat(unit.getValue("locations")).isEqualTo(Set.of(10L, 11L));
        assertThatThrownBy(() -> OfficeScope.of(context(null, Set.of(10L)), false))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.ACCESS_DENIED);
    }

    // P1: the guard is resolved before the statement is prepared, so the office index is used.
    @Test
    void SRS_G_2_theGuardBecomesTheOfficeListOrNothingAtHeadquarters() {
        String sql =
                "SELECT 1 FROM employee e JOIN org_location l ON 1 = 1 WHERE (:all = 'Y' OR"
                        + " e.location_id IN (:locations)) AND (:all = 'Y' OR location_id IN"
                        + " (:locations)) AND (:all = 'Y' OR EXISTS (SELECT 1 FROM x))";
        MapSqlParameterSource unit = OfficeScope.of(context(10L, Set.of(10L)), false);
        assertThat(OfficeScope.indexable(sql, unit))
                .isEqualTo(
                        "SELECT 1 FROM employee e JOIN org_location l ON 1 = 1 WHERE"
                                + " e.location_id IN (:locations) AND location_id IN (:locations)"
                                + " AND (:all = 'Y' OR EXISTS (SELECT 1 FROM x))");
        MapSqlParameterSource hq = OfficeScope.of(context(1L, Set.of()), true);
        assertThat(OfficeScope.indexable(sql, hq))
                .isEqualTo(
                        "SELECT 1 FROM employee e JOIN org_location l ON 1 = 1 WHERE 1 = 1 AND 1 ="
                                + " 1 AND (:all = 'Y' OR EXISTS (SELECT 1 FROM x))");
        // Unchanged: no parameters, no :all among them, no guard in the statement.
        assertThat(OfficeScope.indexable(sql, null)).isEqualTo(sql);
        assertThat(OfficeScope.indexable(sql, new MapSqlParameterSource("x", 1))).isEqualTo(sql);
        assertThat(OfficeScope.indexable("SELECT 1", unit)).isEqualTo("SELECT 1");
    }
}
