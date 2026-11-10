package in.gov.fci.payroll.configuration.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.type.YesNoConverter;

/**
 * {@code rule_expression_grammar}: one token of the bounded formula layer (target DDL v4.0 S06;
 * SRS-19-15): an operator, a function, a variable (bound to a component or field) or a literal
 * type. Only rows with {@code is_permitted = 'Y'} may appear in a formula.
 */
@Entity
@Table(name = "rule_expression_grammar")
public class RuleExpressionGrammar {

    /** {@code token_type}. */
    public enum TokenType {
        OPERATOR,
        FUNCTION,
        VARIABLE,
        LITERAL_TYPE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "grammar_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "token_type", nullable = false, length = 20)
    private TokenType tokenType;

    @Column(name = "token_value", nullable = false, length = 60)
    private String tokenValue;

    @Column(name = "arity")
    private Integer arity;

    @Column(name = "return_type", length = 20)
    private String returnType;

    @Column(name = "binds_to_entity", length = 100)
    private String bindsToEntity;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_permitted", nullable = false, length = 1)
    private Boolean permitted;

    protected RuleExpressionGrammar() {}

    public TokenType getTokenType() {
        return tokenType;
    }

    public String getTokenValue() {
        return tokenValue;
    }

    /** null = any number of arguments (MIN, MAX). */
    public Integer getArity() {
        return arity;
    }

    public String getBindsToEntity() {
        return bindsToEntity;
    }

    public boolean isPermitted() {
        return Boolean.TRUE.equals(permitted);
    }
}
