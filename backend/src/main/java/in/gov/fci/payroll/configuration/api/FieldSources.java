package in.gov.fci.payroll.configuration.api;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The field-source model (target DDL v4.0 {@code field_source_registry}; SRS-G-12, SRS-19-2): the
 * fields a statutory output or report may bind to, with their source (ENT, CALC, HRMS, RULE, SYS,
 * ESS, INTF). Read-only for other modules: an M-17 field binding must resolve here (BR-TAX-013).
 */
@Component
public class FieldSources {

    /** One registered field. */
    public record Field(
            Long fieldSourceId, String entityName, String fieldName, String sourceCode) {}

    private final JdbcTemplate jdbc;

    public FieldSources(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The registered field {@code entity.field}, if any. */
    @Transactional(readOnly = true)
    public Optional<Field> field(String entityName, String fieldName) {
        if (entityName == null || fieldName == null) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        "SELECT field_source_id, entity_name, field_name, source_code FROM"
                                + " field_source_registry WHERE entity_name = ? AND field_name = ?",
                        (rs, i) ->
                                new Field(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4)),
                        entityName,
                        fieldName)
                .stream()
                .findFirst();
    }

    /** The registered field with this id, if any. */
    @Transactional(readOnly = true)
    public Optional<Field> byId(Long fieldSourceId) {
        if (fieldSourceId == null) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        "SELECT field_source_id, entity_name, field_name, source_code FROM"
                                + " field_source_registry WHERE field_source_id = ?",
                        (rs, i) ->
                                new Field(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4)),
                        fieldSourceId)
                .stream()
                .findFirst();
    }
}
