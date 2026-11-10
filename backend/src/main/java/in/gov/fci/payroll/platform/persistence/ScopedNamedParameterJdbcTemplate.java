package in.gov.fci.payroll.platform.persistence;

import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementCreatorFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * The application's named-parameter template: every statement passes through {@link
 * OfficeScope#indexable} before it is prepared, so the office guard of a register uses the office
 * index (P1, PERFORMANCE_GUIDE), and then through {@link InLists#expand}, so no IN list reaches
 * Oracle with more than 1,000 values (ORA-01795; Oracle review F1). Every query and update of the
 * parent class goes through this one method; batch updates carry no office guard.
 */
public class ScopedNamedParameterJdbcTemplate extends NamedParameterJdbcTemplate {

    public ScopedNamedParameterJdbcTemplate(JdbcOperations operations) {
        super(operations);
    }

    @Override
    protected PreparedStatementCreator getPreparedStatementCreator(
            String sql,
            SqlParameterSource params,
            Consumer<PreparedStatementCreatorFactory> customizer) {
        InLists.Expanded statement = InLists.expand(OfficeScope.indexable(sql, params), params);
        return super.getPreparedStatementCreator(statement.sql(), statement.params(), customizer);
    }
}
