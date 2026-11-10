/**
 * Integration layer (KB Module 23; SRS v0.5 "Integrations" SRS-INT-1 … 41; FRS §11, BR-INT-001 …
 * 015; plan {@code docs/plan/23-integrations.md}): the contract-shaped layer of DDL v4.0 Section 22
 * - the interface registry, per-interface resilience settings, the breaker state, the exchange
 * envelope with its received / accepted / rejected counts, the generic idempotent staging, the
 * staging events and the exception queue - and screen X-03 Integration Monitor.
 *
 * <p>Counterparty adapters (HRMS, Oracle EBS / APPS, CPF SSP, Anna Darpan, biometric, bank, EPFO,
 * tax utility) plug in through {@code api.InterfaceAdapter} once each specification is held
 * (SRS-INT-1); none is built yet (owner 2026-10-28; PENDING API-23-1 … API-23-6).
 *
 * <p>Layers as everywhere: {@code web} &rarr; {@code orchestration} &rarr; {@code business}.
 */
@ApplicationModule(
        displayName = "Integrations (Module 23)",
        allowedDependencies = {
            "platform",
            "audit::api",
            "general::api",
            "employee::api",
            "jobs::api"
        })
package in.gov.fci.payroll.integration;

import org.springframework.modulith.ApplicationModule;
