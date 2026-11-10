package in.gov.fci.payroll.configuration.business;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The bounded formula layer of the rule engine (SRS-19-15, KB RE-13 / §1): a formula is parsed into
 * a tree and evaluated, never executed as code. Only the operators and functions the grammar marks
 * permitted ({@code rule_expression_grammar.is_permitted}) and the registered variables may appear;
 * anything else is refused before evaluation, naming the token.
 *
 * <p>Syntax: numbers ({@code 12}, {@code 0.0825}), variable names ({@code BASIC}, {@code
 * DA.PERCENT}), {@code + - * /}, comparisons {@code < <= > >= = <>}, {@code AND OR NOT},
 * parentheses and function calls {@code MIN(a, b, …)}, {@code MAX(…)}, {@code ROUND(x, places)},
 * {@code IF(condition, then, else)}.
 *
 * <p><b>One registry.</b> What the engine can evaluate is declared once: {@link BinaryOp}, {@link
 * UnaryOp} and {@link Fn} carry each operator's symbol, precedence and result kind and each
 * function's arguments, and evaluate them. The tokenizer, the parser and the evaluator all read the
 * registry, so a new operator or function is one entry (and a grammar row to permit it). A token
 * the grammar permits but the engine does not implement is refused when the formula is submitted,
 * never in a payroll run.
 *
 * <p>Arithmetic is exact ({@code BigDecimal}, {@link MathContext#DECIMAL128}): the evaluator never
 * rounds a result; rounding happens once, at the point the computation defines (KB rounding-money
 * R-01 … R-03), or through an explicit {@code ROUND}, which rounds half-up as the component
 * rounding of R-02.
 */
public final class RuleFormula {

    /** Formula text limit: {@code rule_version.formula_expression} is VARCHAR2(4000). */
    public static final int MAX_LENGTH = 4000;

    /**
     * Nesting limit: no permitted formula needs deeper parentheses or calls; it bounds the parser's
     * stack.
     */
    public static final int MAX_DEPTH = 32;

    /** ROUND rounds to at most this many decimal places. */
    static final int MAX_ROUND_PLACES = 10;

    /**
     * What a formula may use: the permitted operators, the permitted functions with their arity
     * (null = any number of arguments, at least one) and the registered variable names.
     */
    public record Grammar(
            Set<String> operators, Map<String, Integer> functions, Set<String> variables) {}

    private final Node root;

    private RuleFormula(Node root) {
        this.root = root;
    }

    /**
     * Parses and checks {@code text} against {@code grammar}; refused (RULE-422-FORMULA) naming the
     * fault.
     */
    public static RuleFormula parse(String text, Grammar grammar) {
        if (text == null || text.isBlank()) {
            throw invalid("empty");
        }
        if (text.length() > MAX_LENGTH) {
            throw invalid("longer than " + MAX_LENGTH + " characters");
        }
        Parser parser = new Parser(tokenize(text), grammar);
        Node root = parser.expression(0);
        if (parser.pos != parser.tokens.size()) {
            throw invalid("unexpected '" + parser.tokens.get(parser.pos).text + "'");
        }
        return new RuleFormula(root);
    }

    /** The variables the formula reads (each must be given a value to evaluate it). */
    public Set<String> variables() {
        Set<String> out = new TreeSet<>();
        root.collect(out);
        return out;
    }

    /**
     * Whether the formula yields a condition (an Eligibility or Applicability rule) rather than a
     * number, read from its outermost operation; checked when a version is submitted, so a formula
     * of the wrong kind never reaches a computation. An IF yields what its first branch yields.
     */
    public boolean yieldsCondition() {
        return isCondition(root);
    }

    private static boolean isCondition(Node node) {
        return switch (node) {
            case Literal l -> false;
            case Variable v -> false;
            case Unary u -> u.op() == UnaryOp.NOT;
            case Binary b -> b.op().condition;
            case Call c -> c.fn() == Fn.IF && isCondition(c.args().get(1));
        };
    }

    /**
     * Evaluates to a number; a missing variable, a division by zero or a type mismatch is refused.
     */
    public BigDecimal number(Map<String, BigDecimal> values) {
        Object v = root.eval(values);
        if (!(v instanceof BigDecimal n)) {
            throw evaluation("the formula yields a condition, not a number");
        }
        return n;
    }

    /** Evaluates to a condition (an Eligibility or Applicability formula). */
    public boolean condition(Map<String, BigDecimal> values) {
        Object v = root.eval(values);
        if (!(v instanceof Boolean b)) {
            throw evaluation("the formula yields a number, not a condition");
        }
        return b;
    }

    // ---------------------------------------------------------------- the registry

    /** The binary operators the engine evaluates; precedence lowest first, all left-associative. */
    private enum BinaryOp {
        OR("OR", 1, true) {
            @Override
            Object apply(Node left, Node right, Map<String, BigDecimal> values) {
                // Short-circuit: the right side is read only when the left one does not decide.
                return bool(left.eval(values)) || bool(right.eval(values));
            }
        },
        AND("AND", 2, true) {
            @Override
            Object apply(Node left, Node right, Map<String, BigDecimal> values) {
                return bool(left.eval(values)) && bool(right.eval(values));
            }
        },
        LT("<", 3, true) {
            @Override
            Object apply(Node left, Node right, Map<String, BigDecimal> values) {
                return compare(left, right, values) < 0;
            }
        },
        LE("<=", 3, true) {
            @Override
            Object apply(Node left, Node right, Map<String, BigDecimal> values) {
                return compare(left, right, values) <= 0;
            }
        },
        GT(">", 3, true) {
            @Override
            Object apply(Node left, Node right, Map<String, BigDecimal> values) {
                return compare(left, right, values) > 0;
            }
        },
        GE(">=", 3, true) {
            @Override
            Object apply(Node left, Node right, Map<String, BigDecimal> values) {
                return compare(left, right, values) >= 0;
            }
        },
        EQ("=", 3, true) {
            @Override
            Object apply(Node left, Node right, Map<String, BigDecimal> values) {
                return compare(left, right, values) == 0;
            }
        },
        NE("<>", 3, true) {
            @Override
            Object apply(Node left, Node right, Map<String, BigDecimal> values) {
                return compare(left, right, values) != 0;
            }
        },
        ADD("+", 4, false) {
            @Override
            Object apply(Node left, Node right, Map<String, BigDecimal> values) {
                return num(left.eval(values)).add(num(right.eval(values)), MathContext.DECIMAL128);
            }
        },
        SUBTRACT("-", 4, false) {
            @Override
            Object apply(Node left, Node right, Map<String, BigDecimal> values) {
                return num(left.eval(values))
                        .subtract(num(right.eval(values)), MathContext.DECIMAL128);
            }
        },
        MULTIPLY("*", 5, false) {
            @Override
            Object apply(Node left, Node right, Map<String, BigDecimal> values) {
                return num(left.eval(values))
                        .multiply(num(right.eval(values)), MathContext.DECIMAL128);
            }
        },
        DIVIDE("/", 5, false) {
            @Override
            Object apply(Node left, Node right, Map<String, BigDecimal> values) {
                BigDecimal dividend = num(left.eval(values));
                BigDecimal divisor = num(right.eval(values));
                if (divisor.signum() == 0) {
                    throw evaluation("division by zero");
                }
                return dividend.divide(divisor, MathContext.DECIMAL128);
            }
        };

        private static final Map<String, BinaryOp> BY_SYMBOL = index(values(), op -> op.symbol);

        final String symbol;
        final int precedence;

        /** The operator yields a condition (comparisons, AND, OR), not a number. */
        final boolean condition;

        BinaryOp(String symbol, int precedence, boolean condition) {
            this.symbol = symbol;
            this.precedence = precedence;
            this.condition = condition;
        }

        abstract Object apply(Node left, Node right, Map<String, BigDecimal> values);

        /** The operator written {@code symbol}, or null. */
        static BinaryOp of(String symbol) {
            return BY_SYMBOL.get(symbol);
        }
    }

    /**
     * The prefix operators; they bind tighter than any binary operator, so a negated comparison is
     * written {@code NOT (a < b)}.
     */
    private enum UnaryOp {
        NEGATE("-") {
            @Override
            Object apply(Object operand) {
                return num(operand).negate();
            }
        },
        NOT("NOT") {
            @Override
            Object apply(Object operand) {
                return !bool(operand);
            }
        };

        private static final Map<String, UnaryOp> BY_SYMBOL = index(values(), op -> op.symbol);

        final String symbol;

        UnaryOp(String symbol) {
            this.symbol = symbol;
        }

        abstract Object apply(Object operand);

        /** The prefix operator written {@code symbol}, or null. */
        static UnaryOp of(String symbol) {
            return BY_SYMBOL.get(symbol);
        }
    }

    /**
     * The functions the engine evaluates, with the arguments each needs (the grammar's arity may
     * narrow, never widen, them).
     */
    private enum Fn {
        MIN(1, Integer.MAX_VALUE) {
            @Override
            Object apply(List<Node> args, Map<String, BigDecimal> values) {
                return extreme(args, values, BigDecimal::min);
            }
        },
        MAX(1, Integer.MAX_VALUE) {
            @Override
            Object apply(List<Node> args, Map<String, BigDecimal> values) {
                return extreme(args, values, BigDecimal::max);
            }
        },
        ROUND(2, 2) {
            @Override
            Object apply(List<Node> args, Map<String, BigDecimal> values) {
                BigDecimal places = num(args.get(1).eval(values));
                if (places.stripTrailingZeros().scale() > 0
                        || places.signum() < 0
                        || places.compareTo(BigDecimal.valueOf(MAX_ROUND_PLACES)) > 0) {
                    throw evaluation("ROUND takes 0 to " + MAX_ROUND_PLACES + " decimal places");
                }
                // Half-up, as the component rounding of KB rounding-money R-02.
                return num(args.get(0).eval(values))
                        .setScale(places.intValueExact(), RoundingMode.HALF_UP);
            }
        },
        IF(3, 3) {
            @Override
            Object apply(List<Node> args, Map<String, BigDecimal> values) {
                // Only the branch taken is evaluated.
                return bool(args.get(0).eval(values))
                        ? args.get(1).eval(values)
                        : args.get(2).eval(values);
            }
        };

        private static final Map<String, Fn> BY_NAME = index(values(), Enum::name);

        final int minArgs;
        final int maxArgs;

        Fn(int minArgs, int maxArgs) {
            this.minArgs = minArgs;
            this.maxArgs = maxArgs;
        }

        abstract Object apply(List<Node> args, Map<String, BigDecimal> values);

        /** The function named {@code name}, or null. */
        static Fn of(String name) {
            return BY_NAME.get(name);
        }
    }

    private static <E> Map<String, E> index(E[] entries, Function<E, String> key) {
        return Arrays.stream(entries).collect(Collectors.toUnmodifiableMap(key, e -> e));
    }

    /**
     * Folds every argument, in order, with {@code BigDecimal::min} or {@code BigDecimal::max}: on a
     * tie the earlier value is kept (their contract), so MIN(4.0, 4) is 4.0.
     */
    private static BigDecimal extreme(
            List<Node> args, Map<String, BigDecimal> values, BinaryOperator<BigDecimal> keep) {
        BigDecimal best = num(args.get(0).eval(values));
        for (Node arg : args.subList(1, args.size())) {
            best = keep.apply(best, num(arg.eval(values)));
        }
        return best;
    }

    private static int compare(Node left, Node right, Map<String, BigDecimal> values) {
        return num(left.eval(values)).compareTo(num(right.eval(values)));
    }

    // ---------------------------------------------------------------- tokens

    private enum Kind {
        NUMBER,
        NAME,
        OPERATOR,
        OPEN,
        CLOSE,
        COMMA
    }

    private record Token(Kind kind, String text) {}

    private static boolean isOperator(String symbol) {
        return BinaryOp.of(symbol) != null || UnaryOp.of(symbol) != null;
    }

    private static List<Token> tokenize(String text) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c)
                    || (c == '.'
                            && i + 1 < text.length()
                            && Character.isDigit(text.charAt(i + 1)))) {
                int start = i;
                while (i < text.length()
                        && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.')) {
                    i++;
                }
                String number = text.substring(start, i);
                if (number.indexOf('.') != number.lastIndexOf('.')) {
                    throw invalid("malformed number '" + number + "'");
                }
                out.add(new Token(Kind.NUMBER, number));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < text.length()
                        && (Character.isLetterOrDigit(text.charAt(i))
                                || text.charAt(i) == '_'
                                || text.charAt(i) == '.')) {
                    i++;
                }
                String name = text.substring(start, i);
                // AND, OR, NOT are operators; any other word is a variable or a function.
                out.add(new Token(isOperator(name) ? Kind.OPERATOR : Kind.NAME, name));
            } else if (c == '(') {
                out.add(new Token(Kind.OPEN, "("));
                i++;
            } else if (c == ')') {
                out.add(new Token(Kind.CLOSE, ")"));
                i++;
            } else if (c == ',') {
                out.add(new Token(Kind.COMMA, ","));
                i++;
            } else {
                String two = i + 1 < text.length() ? text.substring(i, i + 2) : "";
                String one = String.valueOf(c);
                // The longest symbol first: "<=" is one operator, not "<" then "=".
                if (isOperator(two)) {
                    out.add(new Token(Kind.OPERATOR, two));
                    i += 2;
                } else if (isOperator(one)) {
                    out.add(new Token(Kind.OPERATOR, one));
                    i++;
                } else {
                    throw invalid("character '" + c + "' is not permitted");
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- parser (precedence climbing)

    private static final class Parser {
        private final List<Token> tokens;
        private final Grammar grammar;
        private int pos;
        private int depth;

        Parser(List<Token> tokens, Grammar grammar) {
            this.tokens = tokens;
            this.grammar = grammar;
        }

        private Token peek() {
            return pos < tokens.size() ? tokens.get(pos) : null;
        }

        private Token next() {
            if (pos >= tokens.size()) {
                throw invalid("ends too early");
            }
            return tokens.get(pos++);
        }

        private void permitted(String symbol) {
            if (!grammar.operators().contains(symbol)) {
                throw invalid("operator '" + symbol + "' is not permitted");
            }
        }

        private void enter() {
            if (++depth > MAX_DEPTH) {
                throw invalid("nested deeper than " + MAX_DEPTH);
            }
        }

        Node expression(int minPrecedence) {
            enter();
            Node left = unary();
            while (true) {
                Token t = peek();
                BinaryOp op = t != null && t.kind == Kind.OPERATOR ? BinaryOp.of(t.text) : null;
                // Not a binary operator (end, NOT), or one that binds more loosely than this level.
                if (op == null || op.precedence < minPrecedence) {
                    break;
                }
                pos++;
                permitted(op.symbol);
                left = new Binary(op, left, expression(op.precedence + 1));
            }
            depth--;
            return left;
        }

        private Node unary() {
            Token t = next();
            UnaryOp op = t.kind == Kind.OPERATOR ? UnaryOp.of(t.text) : null;
            if (op != null) {
                permitted(op.symbol);
                enter();
                Node operand = unary();
                depth--;
                return new Unary(op, operand);
            }
            return primary(t);
        }

        private Node primary(Token t) {
            switch (t.kind) {
                case NUMBER -> {
                    return new Literal(new BigDecimal(t.text));
                }
                case OPEN -> {
                    Node inner = expression(0);
                    expect(Kind.CLOSE, ")");
                    return inner;
                }
                case NAME -> {
                    Token after = peek();
                    if (after != null && after.kind == Kind.OPEN) {
                        return call(t.text);
                    }
                    if (!grammar.variables().contains(t.text)) {
                        throw invalid("'" + t.text + "' is not a registered variable");
                    }
                    return new Variable(t.text);
                }
                default -> throw invalid("unexpected '" + t.text + "'");
            }
        }

        private Node call(String name) {
            if (!grammar.functions().containsKey(name)) {
                throw invalid("function '" + name + "' is not permitted");
            }
            Fn fn = Fn.of(name);
            if (fn == null) {
                // Permitted by the grammar's data, unknown to the engine: refused now, not in a
                // run.
                throw invalid("function '" + name + "' is not supported by the rule engine");
            }
            expect(Kind.OPEN, "(");
            if (accept(Kind.CLOSE)) {
                throw invalid(name + " needs arguments");
            }
            List<Node> args = new ArrayList<>();
            do {
                args.add(expression(0));
            } while (accept(Kind.COMMA));
            expect(Kind.CLOSE, ")");
            Integer arity = grammar.functions().get(name);
            if (arity != null && args.size() != arity) {
                throw invalid(name + " takes " + arity + " arguments, not " + args.size());
            }
            if (args.size() < fn.minArgs || args.size() > fn.maxArgs) {
                throw invalid(name + " cannot take " + args.size() + " arguments");
            }
            return new Call(fn, args);
        }

        private boolean accept(Kind kind) {
            Token t = peek();
            if (t != null && t.kind == kind) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(Kind kind, String text) {
            if (!accept(kind)) {
                throw invalid("'" + text + "' expected");
            }
        }
    }

    // ---------------------------------------------------------------- tree

    private sealed interface Node permits Literal, Variable, Unary, Binary, Call {
        Object eval(Map<String, BigDecimal> values);

        void collect(Set<String> variables);
    }

    private record Literal(BigDecimal value) implements Node {
        public Object eval(Map<String, BigDecimal> values) {
            return value;
        }

        public void collect(Set<String> variables) {
            // A literal reads no variable.
        }
    }

    private record Variable(String name) implements Node {
        public Object eval(Map<String, BigDecimal> values) {
            BigDecimal v = values.get(name);
            if (v == null) {
                throw evaluation("no value for '" + name + "'");
            }
            return v;
        }

        public void collect(Set<String> variables) {
            variables.add(name);
        }
    }

    private record Unary(UnaryOp op, Node operand) implements Node {
        public Object eval(Map<String, BigDecimal> values) {
            return op.apply(operand.eval(values));
        }

        public void collect(Set<String> variables) {
            operand.collect(variables);
        }
    }

    private record Binary(BinaryOp op, Node left, Node right) implements Node {
        public Object eval(Map<String, BigDecimal> values) {
            return op.apply(left, right, values);
        }

        public void collect(Set<String> variables) {
            left.collect(variables);
            right.collect(variables);
        }
    }

    private record Call(Fn fn, List<Node> args) implements Node {
        public Object eval(Map<String, BigDecimal> values) {
            return fn.apply(args, values);
        }

        public void collect(Set<String> variables) {
            args.forEach(a -> a.collect(variables));
        }
    }

    private static BigDecimal num(Object v) {
        if (!(v instanceof BigDecimal n)) {
            throw evaluation("a number was expected, a condition was given");
        }
        return n;
    }

    private static boolean bool(Object v) {
        if (!(v instanceof Boolean b)) {
            throw evaluation("a condition was expected, a number was given");
        }
        return b;
    }

    private static FciException invalid(String why) {
        return new FciException(
                ApiErrorCode.RULE_FORMULA_INVALID, new Object[] {why}, List.of(), null);
    }

    private static FciException evaluation(String why) {
        return new FciException(
                ApiErrorCode.RULE_FORMULA_EVALUATION, new Object[] {why}, List.of(), null);
    }
}
