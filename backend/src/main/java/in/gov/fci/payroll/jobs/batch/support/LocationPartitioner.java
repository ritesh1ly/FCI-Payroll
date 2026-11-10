package in.gov.fci.payroll.jobs.batch.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * Splits a unit's ACTIVE employees into partitions by location (the unit and every location below
 * it). Locations are dealt largest-first to the partition with the fewest employees, so the
 * partitions stay balanced although a DO can be ten times the size of a depot. Each partition gets
 * its location ids ({@link BatchKeys#LOCATIONS}) and the number of employees it will read ({@link
 * BatchKeys#PLANNED}, for the progress bar). At most {@code gridSize} partitions; an empty unit
 * gives one empty partition, so the step still completes.
 *
 * <p>On restart Spring Batch does not call the partitioner again: it reuses the partitions of the
 * failed execution and re-runs only those that did not complete.
 */
public class LocationPartitioner implements Partitioner {

    private final Collection<Long> locations;
    private final Function<Collection<Long>, Map<Long, Long>> employeeCounts;

    /**
     * @param locations the unit's location subtree
     * @param employeeCounts ACTIVE employees per location (locations without employees may be
     *     absent)
     */
    public LocationPartitioner(
            Collection<Long> locations,
            Function<Collection<Long>, Map<Long, Long>> employeeCounts) {
        this.locations = List.copyOf(locations);
        this.employeeCounts = employeeCounts;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Map<Long, Long> counts = employeeCounts.apply(locations);
        List<Map.Entry<Long, Long>> populated =
                counts.entrySet().stream()
                        .filter(
                                e ->
                                        e.getValue() != null
                                                && e.getValue() > 0
                                                && locations.contains(e.getKey()))
                        .sorted(
                                Map.Entry.<Long, Long>comparingByValue()
                                        .reversed()
                                        .thenComparing(Map.Entry.comparingByKey()))
                        .toList();
        int partitions = Math.max(1, Math.min(gridSize, populated.size()));
        List<List<Long>> ids = new ArrayList<>();
        long[] sizes = new long[partitions];
        for (int i = 0; i < partitions; i++) {
            ids.add(new ArrayList<>());
        }
        for (Map.Entry<Long, Long> location : populated) {
            int smallest = 0;
            for (int i = 1; i < partitions; i++) {
                if (sizes[i] < sizes[smallest]) {
                    smallest = i;
                }
            }
            ids.get(smallest).add(location.getKey());
            sizes[smallest] += location.getValue();
        }
        Map<String, ExecutionContext> result = new LinkedHashMap<>();
        for (int i = 0; i < partitions; i++) {
            ExecutionContext context = new ExecutionContext();
            List<Long> mine = ids.get(i).stream().sorted(Comparator.naturalOrder()).toList();
            context.putString(
                    BatchKeys.LOCATIONS,
                    mine.stream().map(String::valueOf).collect(Collectors.joining(",")));
            context.putLong(BatchKeys.PLANNED, sizes[i]);
            result.put("partition" + i, context);
        }
        return result;
    }

    /** Parses {@link BatchKeys#LOCATIONS}; an empty partition reads location -1 (nothing). */
    public static List<Long> parse(String locations) {
        if (locations == null || locations.isBlank()) {
            return List.of(-1L);
        }
        List<Long> result = new ArrayList<>();
        for (String id : locations.split(",")) {
            result.add(Long.valueOf(id.strip()));
        }
        return result;
    }

    /** Helper for callers counting with SQL: rows of (location_id, count). */
    public static Map<Long, Long> toMap(List<Map<String, Object>> rows) {
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object location = row.values().stream().findFirst().orElse(null);
            Object count = row.values().stream().skip(1).findFirst().orElse(null);
            if (location instanceof Number l && count instanceof Number c) {
                counts.put(l.longValue(), c.longValue());
            }
        }
        return counts;
    }
}
