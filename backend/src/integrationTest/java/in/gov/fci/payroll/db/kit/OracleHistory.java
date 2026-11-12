package in.gov.fci.payroll.db.kit;

import in.gov.fci.payroll.db.kit.Catalog.Vendor;
import in.gov.fci.payroll.db.kit.KitBuild.Profile;
import in.gov.fci.payroll.db.kit.KitData.Row;
import in.gov.fci.payroll.db.kit.KitData.TableRows;
import in.gov.fci.payroll.platform.persistence.V022__Align_pay_pattern_with_target_ddl;
import in.gov.fci.payroll.platform.persistence.V029_1__Spring_batch_6_job_instance_sequence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The Flyway history of the Oracle kit, as Flyway itself writes it when it migrates an Oracle
 * schema (owner 2026-11-11: DEV starts with Flyway enabled, OD-151, and validated the kit's history
 * against db/migration/oracle).
 *
 * <p>The fallback kit's rows come from the H2 Flyway build, whose history names the H2 scripts:
 * their checksums differ from the Oracle scripts of the same version (84 of 87 vendor-specific
 * ones), V005_1 is H2-only and V045_4 Oracle-only. Here the history of each data set is rebuilt
 * from Flyway's own resolution of the ORACLE locations (db/migration/oracle, db/migration/common
 * and the data set's seed folders with their Oracle variants; the Java migrations V009 (data sets
 * with the first administrator), V022 and V029_1): per migration, in Flyway's version order, the
 * installed rank, version, description, type (SQL / JDBC), script and checksum Flyway computes (its
 * ChecksumCalculator over the Oracle resource; JDBC migrations carry none). Who / when / how long
 * are taken from the H2 row of the same version (or the row before it for an Oracle-only
 * migration); the "history table created" marker row is kept. INSTALL_KIT.md "Flyway history".
 */
final class OracleHistory {

    static final String TABLE = "flyway_schema_history";

    private OracleHistory() {}

    /** The migrations Flyway resolves for {@code profile} on Oracle, in its apply order. */
    static List<MigrationInfo> resolved(Path db, Profile profile) {
        List<String> locations = new ArrayList<>();
        for (String folder : profile.folders(Vendor.ORACLE)) {
            Path p = db.resolve(folder);
            if (!Files.isDirectory(p) && Profile.optional(folder)) {
                continue;
            }
            locations.add("filesystem:" + p.toAbsolutePath());
        }
        List<JavaMigration> java = new ArrayList<>();
        java.add(new V022__Align_pay_pattern_with_target_ddl());
        java.add(new V029_1__Spring_batch_6_job_instance_sequence());
        if (profile.bootstrap) {
            java.add(KitBuild.bootstrapMigration());
        }
        // An empty in-memory database: info() needs a connection, the resolution reads only the
        // locations (the scripts are never executed).
        DriverManagerDataSource ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:oracle_history_" + System.nanoTime(), "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        List<MigrationInfo> out = new ArrayList<>();
        for (MigrationInfo i :
                Flyway.configure()
                        .dataSource(ds)
                        .locations(locations.toArray(String[]::new))
                        .javaMigrations(java.toArray(JavaMigration[]::new))
                        .placeholders(profile.placeholders())
                        .outOfOrder(true)
                        .load()
                        .info()
                        .all()) {
            if (i.getVersion() != null) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * The history rows of the Oracle kit for {@code profile}: Flyway's Oracle resolution, with the
     * H2 build's rows as templates for the columns Flyway fills at run time.
     */
    static TableRows rewrite(TableRows h2, Path db, Profile profile) {
        int rank = h2.index("installed_rank");
        int version = h2.index("version");
        int description = h2.index("description");
        int type = h2.index("type");
        int script = h2.index("script");
        int checksum = h2.index("checksum");
        Map<String, Row> byVersion = new LinkedHashMap<>();
        List<Row> markers = new ArrayList<>();
        for (Row r : h2.rows().values()) {
            if (r.values()[version] == null) {
                markers.add(r);
            } else {
                byVersion.put(r.values()[version], r);
            }
        }
        LinkedHashMap<String, Row> rows = new LinkedHashMap<>();
        for (Row m : markers) {
            rows.put(m.key(), m);
        }
        Row previous = null;
        int n = 0;
        for (MigrationInfo i : resolved(db, profile)) {
            String v = i.getVersion().getVersion();
            Row template = byVersion.getOrDefault(v, previous);
            if (template == null) {
                throw new IllegalStateException("no history row to copy for " + v);
            }
            String[] values = template.values().clone();
            values[rank] = String.valueOf(++n);
            values[version] = v;
            values[description] = i.getDescription();
            values[type] = i.getType().name();
            values[script] = i.getScript();
            values[checksum] = i.getChecksum() == null ? null : String.valueOf(i.getChecksum());
            String key = KitData.key(values, h2.pk());
            rows.put(key, new Row(key, values, (BitSet) template.relative().clone()));
            previous = template;
        }
        return new TableRows(h2.table(), h2.columns(), h2.kinds(), h2.pk(), rows, h2.generated());
    }

    /** The rows of a build with its history replaced by the Oracle one. */
    static Map<String, TableRows> withOracleHistory(
            Map<String, TableRows> rows, Path db, Profile profile) {
        Map<String, TableRows> out = new LinkedHashMap<>(rows);
        out.put(TABLE, rewrite(rows.get(TABLE), db, profile));
        return out;
    }
}
