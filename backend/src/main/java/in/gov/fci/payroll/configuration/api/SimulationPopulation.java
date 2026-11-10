package in.gov.fci.payroll.configuration.api;

import java.time.LocalDate;
import java.util.List;

/**
 * The sample population of a rule simulation (plan 06 phase 06e; SRS-19-4): the members a payroll
 * run would read, by payroll type and office. A port: the payroll module, which owns the member
 * eligibility of a run, implements it; the configuration module never reads the employee tables.
 */
public interface SimulationPopulation {

    /** One member: the context a rule is resolved in. */
    record Member(long employeeId, String payrollType, long locationId) {}

    /**
     * The population and the first {@code limit} members of it, by employee id.
     *
     * @param total every member who matches
     */
    record Sample(long total, List<Member> members) {

        public Sample {
            members = List.copyOf(members);
        }
    }

    /**
     * The members paid on {@code day}.
     *
     * @param payrollType one of the four payroll types, or null for every type
     * @param officeId an office with the offices below it, or null for every office
     * @param limit how many members to return at most
     */
    Sample sample(LocalDate day, String payrollType, Long officeId, int limit);
}
