package in.gov.fci.payroll.db.kit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits Oracle scripts into the units SQL*Plus / SQLcl execute: a SQL statement ends with ";" at
 * the end of a line, a PL/SQL unit (DECLARE, BEGIN, CREATE TRIGGER / PROCEDURE / FUNCTION / PACKAGE
 * / TYPE) with a line holding only "/"; SQL*Plus commands (SET, WHENEVER, SPOOL, PROMPT, EXIT, @@
 * ...) are skipped. The rule scripts/oracle_19c_check.py applies too. Comments and string literals
 * are respected, so a ";" inside a literal or comment never ends a statement.
 */
final class SqlFiles {

    private static final Pattern PLSQL =
            Pattern.compile(
                    "^(DECLARE|BEGIN|CREATE\\s+(OR\\s+REPLACE\\s+)?((NON)?EDITIONABLE\\s+)?"
                            + "(TRIGGER|PROCEDURE|FUNCTION|PACKAGE|TYPE))\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern SQLPLUS =
            Pattern.compile(
                    "^(SET\\s|WHENEVER\\s|SPOOL(\\s|$)|PROMPT(\\s|$)|EXIT(\\s|$)|REM(\\s|$)|COLUMN\\s"
                        + "|DEFINE\\s|SHOW\\s|@)",
                    Pattern.CASE_INSENSITIVE);

    private SqlFiles() {}

    /**
     * The executable units of a script: SQL without its ";", PL/SQL with its last "END;".
     *
     * @param text the script
     */
    static List<String> oracleUnits(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder unit = new StringBuilder();
        boolean plsql = false;
        boolean inString = false;
        boolean inComment = false;
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            String trimmed = line.strip();
            if (unit.isEmpty() && !inComment) {
                if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                    continue;
                }
                if (SQLPLUS.matcher(trimmed).find()) {
                    continue;
                }
                plsql = PLSQL.matcher(trimmed).find();
            }
            if (plsql && !inString && !inComment && trimmed.equals("/")) {
                out.add(unit.toString().strip());
                unit.setLength(0);
                plsql = false;
                continue;
            }
            unit.append(line).append('\n');
            // Track literals and block comments to the end of the line.
            boolean lineComment = false;
            for (int i = 0; i < line.length() && !lineComment; i++) {
                char c = line.charAt(i);
                if (inComment) {
                    if (line.startsWith("*/", i)) {
                        inComment = false;
                        i++;
                    }
                } else if (inString) {
                    if (c == '\'') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '\'') {
                            i++;
                        } else {
                            inString = false;
                        }
                    }
                } else if (line.startsWith("--", i)) {
                    lineComment = true;
                } else if (line.startsWith("/*", i)) {
                    inComment = true;
                    i++;
                } else if (c == '\'') {
                    inString = true;
                }
            }
            if (!plsql && !inString && !inComment && endsWithSemicolon(line)) {
                String sql = unit.toString().strip();
                out.add(sql.substring(0, sql.lastIndexOf(';')).strip());
                unit.setLength(0);
            }
        }
        if (!unit.toString().isBlank()) {
            out.add(unit.toString().strip());
        }
        return out;
    }

    /** Whether the code of a line (its trailing "--" comment removed) ends with ";". */
    private static boolean endsWithSemicolon(String line) {
        String code = line;
        int dash = indexOutsideString(line, "--");
        if (dash >= 0) {
            code = line.substring(0, dash);
        }
        return code.stripTrailing().endsWith(";");
    }

    private static int indexOutsideString(String line, String token) {
        boolean in = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'') {
                in = !in;
            } else if (!in && line.startsWith(token, i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Runs an Oracle kit file unit by unit; a failure names the file, the unit and its first line.
     */
    static int runOracle(Connection c, Path file) throws IOException, SQLException {
        List<String> units = oracleUnits(Files.readString(file, StandardCharsets.US_ASCII));
        int n = 0;
        try (Statement s = c.createStatement()) {
            for (String u : units) {
                n++;
                try {
                    s.execute(u);
                } catch (SQLException e) {
                    throw new SQLException(
                            file.getFileName()
                                    + " unit "
                                    + n
                                    + " ("
                                    + KitSteps.first(u)
                                    + "): "
                                    + e.getMessage(),
                            e.getSQLState(),
                            e.getErrorCode(),
                            e);
                }
            }
        }
        return n;
    }
}
