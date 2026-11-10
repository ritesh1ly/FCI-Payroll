package in.gov.fci.payroll.payroll.business;

import java.util.Objects;
import java.util.Optional;

/**
 * SRS-15-26 / BR-CYC-013 (KB CYC-9): a computation run may continue from where it stopped only if
 * nothing it depends on changed after it started. Both identities are compared: the hash of the run
 * parameters ({@code computation_run.parameter_hash}) and the hash of the rate, slab and rule
 * versions resolved for its period ({@code computation_run.rule_version_set_hash}). A resume after
 * either change would compute the remaining members with other inputs than the committed ones, so
 * only a full restart (a new run, every member from the start) is offered.
 */
public final class ResumeRule {

    /** What changed since the run started. */
    public enum Change {
        /** The run parameters (period, unit, ...) differ from those it started with. */
        PARAMETERS,
        /** A rate, slab or rule version in force for the period was activated after the start. */
        RULE_VERSIONS
    }

    private ResumeRule() {}

    /**
     * The change that forbids the resume, parameters first, or empty when the run may continue.
     *
     * @param startedParameters {@code parameter_hash} recorded when the run started
     * @param startedVersions {@code rule_version_set_hash} recorded when the run started
     * @param parametersNow the request's parameter hash now
     * @param versionsNow the version set resolved for the run's period now
     */
    public static Optional<Change> change(
            String startedParameters,
            String startedVersions,
            String parametersNow,
            String versionsNow) {
        if (!Objects.equals(startedParameters, parametersNow)) {
            return Optional.of(Change.PARAMETERS);
        }
        if (!Objects.equals(startedVersions, versionsNow)) {
            return Optional.of(Change.RULE_VERSIONS);
        }
        return Optional.empty();
    }
}
