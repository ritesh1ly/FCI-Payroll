package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.cache.VersionedCache;
import in.gov.fci.payroll.tax.business.model.PtState;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The PT state master (M-18, {@code pt_state_master}; SRS-7-4, BR-TAX-006) as the PT computation
 * reads it once per member: about 36 rows that change only through an approved M-18 decision, held
 * in memory (HLD §3.12, region {@value #REGION}) as immutable views, never the JPA entities. The
 * approval marks the region changed in its transaction ({@link #changed}).
 */
@Component
public class PtStates {

    /** The cache region of the tax masters (sections and PT states of M-18). */
    public static final String REGION = "TAX_MASTER";

    /** One state as the computation needs it. */
    public record View(
            String stateCode,
            boolean ptApplicable,
            String slabRuleKey,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {

        /** Whether the state's PT setting applies on {@code day} (both ends inclusive). */
        public boolean inForceOn(LocalDate day) {
            return !effectiveFrom.isAfter(day)
                    && (effectiveTo == null || !effectiveTo.isBefore(day));
        }

        static View of(PtState s) {
            return new View(
                    s.getStateCode(),
                    s.isPtApplicable(),
                    s.getSlabRuleKey(),
                    s.getEffectiveFrom(),
                    s.getEffectiveTo());
        }
    }

    private final TaxRepositories.PtStates states;
    private final VersionedCache<Boolean, Map<String, View>> cache;

    public PtStates(TaxRepositories.PtStates states, CacheRegions regions) {
        this.states = states;
        this.cache = VersionedCache.of(REGION, regions, 1);
    }

    /** The state with this code; empty when it is not in the master. */
    @Transactional(readOnly = true)
    public Optional<View> state(String stateCode) {
        return Optional.ofNullable(
                cache.get(
                                Boolean.TRUE,
                                k ->
                                        states.findAllByOrderByStateCodeAsc().stream()
                                                .map(View::of)
                                                .collect(
                                                        Collectors.toUnmodifiableMap(
                                                                View::stateCode,
                                                                Function.identity())))
                        .get(stateCode));
    }

    /** After an approved M-18 change: every instance reloads (in the caller's transaction). */
    public void changed(String changedBy) {
        cache.changed(changedBy);
    }
}
