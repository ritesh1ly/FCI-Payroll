package in.gov.fci.payroll.iam.web;

import java.util.List;
import java.util.Optional;

/**
 * The six personas of the live FCILekha login page, in its order, mapped to the {@code
 * login_persona} codes. The browser only ever sees the live ids. Every persona goes straight to the
 * workspace ({@link #WORKSPACE}), which opens on the persona's landing screen if the RBAC allows
 * it.
 */
public final class Personas {

    /** The route after sign-in for every persona. */
    public static final String WORKSPACE = "/payroll";

    /** One persona: live id, API code, icon (lucide name), route after sign-in. */
    public record Persona(String id, String apiCode, String icon, String route) {}

    public static final List<Persona> ALL =
            List.of(
                    new Persona("employee", "EMPLOYEE", "user-round", WORKSPACE),
                    new Persona("payroll", "PAYROLL", "wallet", WORKSPACE),
                    new Persona("manager", "DDO_MANAGER", "users", WORKSPACE),
                    new Persona("agm", "AGM", "building-2", WORKSPACE),
                    new Persona("superuser", "SUPER_USER", "shield-check", WORKSPACE),
                    new Persona("sysadmin", "SYSTEM_ADMIN", "settings-2", WORKSPACE),
                    new Persona("auditor", "AUDITOR", "search", WORKSPACE));

    private Personas() {}

    public static Optional<Persona> byId(String id) {
        return ALL.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public static Optional<Persona> byApiCode(String code) {
        return ALL.stream().filter(p -> p.apiCode().equals(code)).findFirst();
    }

    /**
     * {@code {type} — {name} ({code})}, e.g. "DO — Delhi HQ (DH001)". Data values are never
     * translated.
     */
    public static String locationLabel(String type, String name, String code) {
        return type + " — " + name + " (" + code + ")";
    }
}
