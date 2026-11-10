package in.gov.fci.payroll.jobs.batch.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.ExecutionContext;

class LocationPartitionerTest {

    @Test
    void balancesLocationsLargestFirstAcrossPartitions() {
        LocationPartitioner partitioner =
                new LocationPartitioner(
                        List.of(1L, 2L, 3L, 4L, 5L),
                        ids -> Map.of(1L, 100L, 2L, 60L, 3L, 50L, 4L, 10L));

        Map<String, ExecutionContext> partitions = partitioner.partition(2);

        assertThat(partitions).hasSize(2);
        long total =
                partitions.values().stream().mapToLong(c -> c.getLong(BatchKeys.PLANNED)).sum();
        assertThat(total).isEqualTo(220);
        // Greedy: {1} = 100 and {2, 3, 4} = 120 -> no partition carries everything.
        assertThat(partitions.values())
                .allSatisfy(c -> assertThat(c.getLong(BatchKeys.PLANNED)).isBetween(100L, 120L));
        // Location 5 has no employees: in no partition.
        assertThat(partitions.values())
                .noneSatisfy(
                        c ->
                                assertThat(
                                                LocationPartitioner.parse(
                                                        c.getString(BatchKeys.LOCATIONS)))
                                        .contains(5L));
    }

    @Test
    void neverMorePartitionsThanPopulatedLocations() {
        Map<String, ExecutionContext> partitions =
                new LocationPartitioner(List.of(1L, 2L), ids -> Map.of(1L, 5L, 2L, 7L))
                        .partition(8);
        assertThat(partitions).hasSize(2);
    }

    @Test
    void anEmptyUnitGivesOneEmptyPartitionThatReadsNothing() {
        Map<String, ExecutionContext> partitions =
                new LocationPartitioner(List.of(1L), ids -> Map.of()).partition(4);
        assertThat(partitions).hasSize(1);
        ExecutionContext only = partitions.values().iterator().next();
        assertThat(only.getLong(BatchKeys.PLANNED)).isZero();
        assertThat(LocationPartitioner.parse(only.getString(BatchKeys.LOCATIONS)))
                .containsExactly(-1L);
    }

    @Test
    void ignoresCountsForLocationsOutsideTheUnit() {
        Map<String, ExecutionContext> partitions =
                new LocationPartitioner(List.of(1L), ids -> Map.of(1L, 3L, 99L, 500L)).partition(4);
        assertThat(partitions.values().iterator().next().getLong(BatchKeys.PLANNED)).isEqualTo(3);
    }
}
