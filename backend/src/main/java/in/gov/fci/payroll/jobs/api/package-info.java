/**
 * The jobs module's published interface: what another module may plug into the job lifecycle (a
 * veto on resuming a request, {@link in.gov.fci.payroll.jobs.api.ResumeCheck}; what to close when a
 * request is abandoned, {@link in.gov.fci.payroll.jobs.api.AbandonListener}).
 */
@NamedInterface("api")
package in.gov.fci.payroll.jobs.api;

import org.springframework.modulith.NamedInterface;
