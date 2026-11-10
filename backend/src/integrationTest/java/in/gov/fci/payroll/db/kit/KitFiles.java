package in.gov.fci.payroll.db.kit;

import in.gov.fci.payroll.db.kit.KitBuild.Profile;
import java.util.ArrayList;
import java.util.List;

/**
 * The kit's file names and the one order they run in (owner 2026-11-09, docs/db/INSTALL_KIT.md
 * section 2): 1 DROP, 2 DDL, 3 DML (reference, then demo, then edge or screens), 4 COMPILE INVALID.
 * The data scripts form a tree ({@link Profile#parent()}): 05 (edge) and 05b (screen demo data,
 * OD-141 D-screens) each follow 04 (demo), never each other. Every runner (the wrappers, {@link
 * KitProof}, {@link OracleKitTool}, db/tools/oracle/ build_setup_script.py) takes the order from
 * here or from the generated wrappers.
 */
final class KitFiles {

    /** Step 1: drops every kit object that exists, children before parents. */
    static final String DROP = "01_drop.sql";

    /** Step 2: sequences, tables, keys, checks, indexes, foreign keys, views, code, triggers. */
    static final String DDL = "02_ddl.sql";

    /** Step 3a: reference rows every environment needs, and the Flyway history. */
    static final String DML_REFERENCE = "03_dml_reference.sql";

    /** Step 3b: demo rows (never in production). */
    static final String DML_DEMO = "04_dml_demo.sql";

    /** Step 3c: edge-case rows, after the demo rows. */
    static final String DML_EDGE = "05_dml_edge.sql";

    /**
     * Step 3d: screen demo rows (profile screens, OD-141 D-screens), after the demo rows; an
     * alternative to 05, never after it.
     */
    static final String DML_SCREENS = "05b_dml_screens.sql";

    /** Step 4: recompiles invalid objects (Oracle) and checks the object counts, after the data. */
    static final String COMPILE = "06_compile_invalid.sql";

    private KitFiles() {}

    /** The data script that ends in {@code p}. */
    static String dml(Profile p) {
        return switch (p) {
            case REFERENCE -> DML_REFERENCE;
            case DEMO -> DML_DEMO;
            case EDGE -> DML_EDGE;
            case SCREENS -> DML_SCREENS;
        };
    }

    /**
     * The data scripts up to and including the one of {@code p}, in run order: those of its parents
     * ({@link Profile#parent()}), then its own (screens: 03, 04, 05b).
     */
    static List<String> dmlUpTo(Profile p) {
        List<String> out = new ArrayList<>();
        for (Profile q = p; q != null; q = q.parent()) {
            out.addFirst(dml(q));
        }
        return out;
    }

    /** The full run of a data set: drop, DDL, its data scripts, compile. */
    static String[] run(Profile p) {
        List<String> out = new ArrayList<>(List.of(DROP, DDL));
        out.addAll(dmlUpTo(p));
        out.add(COMPILE);
        return out.toArray(String[]::new);
    }
}
