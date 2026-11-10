package in.gov.fci.payroll.db.kit;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dictionary snapshot of one schema, read from a database that Flyway (or the install kit) built:
 * every object of the install kit with its final state and the DDL that recreates it
 * (docs/db/INSTALL_KIT.md). Ordering is derived from this model (foreign keys, view and trigger
 * references), never written by hand.
 *
 * @param vendor the database the snapshot was read from
 * @param tables tables by name, with columns and the CREATE TABLE text
 * @param constraints primary, unique, check and foreign keys
 * @param indexes indexes that are not created by a constraint
 * @param views views by name
 * @param triggers triggers
 * @param sequences sequences that are not the internal sequence of an identity column
 * @param code Oracle packages, procedures, functions, types and synonyms (none on H2)
 */
record Catalog(
        Vendor vendor,
        Map<String, Table> tables,
        List<Constraint> constraints,
        List<Index> indexes,
        Map<String, View> views,
        List<Trigger> triggers,
        List<Sequence> sequences,
        List<CodeObject> code) {

    /** The two databases of the project (ADR-0019): H2 for tests, Oracle 19c as target. */
    enum Vendor {
        H2,
        ORACLE
    }

    /**
     * A table.
     *
     * @param name the table name as the dictionary holds it
     * @param columns columns in their ordinal order
     * @param ddl the CREATE TABLE statement (identity columns start at their first value)
     * @param comments COMMENT ON statements of the table and its columns (Oracle)
     */
    record Table(String name, List<Column> columns, String ddl, List<String> comments) {
        Column column(String columnName) {
            return columns.stream()
                    .filter(c -> c.name().equals(columnName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(name + "." + columnName));
        }
    }

    /**
     * A column.
     *
     * @param name column name
     * @param type the type as the dictionary renders it, e.g. {@code CHARACTER VARYING(20)} or
     *     {@code VARCHAR2(20 BYTE)}
     * @param kind how a value of the column is written as a literal
     * @param nullable whether NULL is allowed
     * @param defaultExpr the default expression, or null
     * @param identity the identity definition, or null when the column is not an identity
     * @param generated a computed (virtual) column: never written by the data scripts
     */
    record Column(
            String name,
            String type,
            Kind kind,
            boolean nullable,
            String defaultExpr,
            Identity identity,
            boolean generated) {}

    /** Literal kinds: the value classes of {@link KitData}. */
    enum Kind {
        STRING,
        CLOB,
        NUMBER,
        DATE,
        TIMESTAMP,
        BLOB,
        RAW,
        BOOLEAN
    }

    /**
     * An identity column's generator.
     *
     * @param generation {@code ALWAYS} or {@code BY DEFAULT}
     * @param onNull Oracle {@code BY DEFAULT ON NULL}
     * @param start the first value
     * @param increment the increment
     * @param next the value the identity gives next in the snapshot
     */
    record Identity(String generation, boolean onNull, long start, long increment, long next) {}

    /**
     * A constraint.
     *
     * @param name constraint name
     * @param type P primary key, U unique, C check, R foreign key
     * @param table the table the constraint is on
     * @param columns the constrained columns in key order (empty for a check)
     * @param refTable the referenced table (R only)
     * @param refColumns the referenced columns in key order (R only)
     * @param systemName whether the database named it (then the kit lets the database name it)
     * @param ddl the ALTER TABLE ... ADD statement
     */
    record Constraint(
            String name,
            char type,
            String table,
            List<String> columns,
            String refTable,
            List<String> refColumns,
            boolean systemName,
            String ddl) {}

    /**
     * An index created by a CREATE INDEX statement (not the index a key creates for itself).
     *
     * @param name index name
     * @param table the indexed table
     * @param columns indexed columns or expressions in order, each with its ordering
     * @param unique whether it is unique
     * @param ddl the CREATE INDEX statement
     */
    record Index(String name, String table, List<String> columns, boolean unique, String ddl) {}

    /**
     * A view.
     *
     * @param name view name
     * @param ddl the CREATE statement
     * @param definition the query, as the dictionary holds it (compared, normalised)
     * @param uses the other views it reads (ordering)
     */
    record View(String name, String ddl, String definition, Set<String> uses) {}

    /**
     * A trigger.
     *
     * @param name trigger name
     * @param table the table it fires on
     * @param timing BEFORE / AFTER / INSTEAD OF (as the dictionary says)
     * @param events INSERT, UPDATE, DELETE (as the dictionary says)
     * @param body H2: the Java class; Oracle: the PL/SQL body
     * @param enabled whether it is enabled
     * @param ddl the CREATE statement
     * @param uses sequences and code objects it calls (ordering)
     */
    record Trigger(
            String name,
            String table,
            String timing,
            String events,
            String body,
            boolean enabled,
            String ddl,
            Set<String> uses) {}

    /**
     * A sequence.
     *
     * @param name sequence name
     * @param increment increment
     * @param next the value it gives next in the snapshot
     * @param ddl the CREATE SEQUENCE statement at its first value
     */
    record Sequence(String name, long increment, long next, String ddl) {}

    /**
     * An Oracle code object or synonym.
     *
     * @param type the USER_OBJECTS type (PACKAGE, PACKAGE BODY, PROCEDURE, FUNCTION, TYPE, TYPE
     *     BODY, SYNONYM)
     * @param name object name
     * @param ddl the CREATE statement, ending with its terminator
     * @param uses the code objects it depends on (ordering)
     */
    record CodeObject(String type, String name, String ddl, Set<String> uses) {}

    /** Primary key of a table (empty when it has none). */
    List<String> primaryKey(String table) {
        return constraints.stream()
                .filter(c -> c.type() == 'P' && c.table().equals(table))
                .map(Constraint::columns)
                .findFirst()
                .orElse(List.of());
    }

    /** Foreign keys whose child is {@code table}. */
    List<Constraint> foreignKeys(String table) {
        return constraints.stream()
                .filter(c -> c.type() == 'R' && c.table().equals(table))
                .toList();
    }

    /** Every foreign key. */
    List<Constraint> foreignKeys() {
        return constraints.stream().filter(c -> c.type() == 'R').toList();
    }
}
