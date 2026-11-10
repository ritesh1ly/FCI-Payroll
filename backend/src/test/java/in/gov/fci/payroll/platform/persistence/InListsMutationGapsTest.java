package in.gov.fci.payroll.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;

/** Oracle review F1 (ORA-01795): boundaries of {@link InLists} found by mutation testing. */
class InListsMutationGapsTest {

    @Test
    void anOversizedListInAStatementStartingWithTheNameIsStillRefused() {
        var ids = LongStream.rangeClosed(1, 1_001).boxed().collect(Collectors.toList());
        assertThatThrownBy(() -> InLists.expand(":ids", Map.of("ids", ids)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining(":ids");
    }

    @Test
    void exactlyTwoThousandValuesAreSplitIntoTwoChunksOfAThousand() {
        var ids = LongStream.rangeClosed(1, 2_000).boxed().collect(Collectors.toList());
        InLists.ExpandedMap expanded =
                InLists.expand("SELECT 1 WHERE e.location_id IN (:ids)", Map.of("ids", ids));
        assertThat(expanded.sql())
                .isEqualTo(
                        "SELECT 1 WHERE (e.location_id IN (:ids__0) OR e.location_id IN"
                                + " (:ids__1))");
        assertThat(expanded.params()).containsOnlyKeys("ids", "ids__0", "ids__1");
        assertThat((java.util.List<?>) expanded.params().get("ids__1")).hasSize(1_000);
    }
}
