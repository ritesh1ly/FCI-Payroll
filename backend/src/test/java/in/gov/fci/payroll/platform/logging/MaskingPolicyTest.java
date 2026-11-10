package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.logging.MaskingProperties.FieldRule;
import in.gov.fci.payroll.platform.logging.MaskingProperties.PatternRule;
import in.gov.fci.payroll.platform.logging.MaskingProperties.Strategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * R-PII: the configuration-driven masking policy. Strategies, JSON walk, text rules, query strings,
 * construction checks, the input limit and thread safety. Data are fake but in real formats.
 */
class MaskingPolicyTest {

    private static final String PAN = "ABCPS1234K";
    private static final String MOBILE = "9876543210";
    private static final String MOBILE_RULE = "(?<![0-9])[6-9][0-9]{9}(?![0-9])";
    private static final String PAN_RULE = "[A-Z]{5}[0-9]{4}[A-Z]";

    private static FieldRule f(String name, Strategy strategy, Integer keep) {
        return new FieldRule(name, strategy, keep);
    }

    private static PatternRule p(String regex, Strategy strategy, Integer keep) {
        return new PatternRule(regex, strategy, keep);
    }

    private static MaskingPolicy policy(List<FieldRule> fields, List<PatternRule> patterns) {
        return new MaskingPolicy(new MaskingProperties(fields, patterns));
    }

    private static MaskingPolicy fields(FieldRule... rules) {
        return policy(List.of(rules), List.of());
    }

    private static MaskingPolicy patterns(PatternRule... rules) {
        return policy(List.of(), List.of(rules));
    }

    // ------------------------------------------------------------------ properties

    @Test
    void R_PII_propertiesNeverReturnNullLists() {
        MaskingProperties none = new MaskingProperties(null, null);
        assertThat(none.fieldRules()).isEmpty();
        assertThat(none.patternRules()).isEmpty();
        FieldRule fr = f("a", Strategy.FULL, null);
        PatternRule pr = p("b", Strategy.FULL, null);
        MaskingProperties some = new MaskingProperties(List.of(fr), List.of(pr));
        assertThat(some.fieldRules()).containsExactly(fr);
        assertThat(some.patternRules()).containsExactly(pr);
        assertThat(Strategy.values())
                .containsExactly(
                        Strategy.FULL,
                        Strategy.KEEP_LAST,
                        Strategy.KEEP_FIRST,
                        Strategy.EMAIL,
                        Strategy.INITIALS,
                        Strategy.HASH);
    }

    // ------------------------------------------------------------------ strategies

    @Test
    void R_PII_fullHidesEverythingWhateverTheLength() {
        assertThat(MaskingPolicy.apply(Strategy.FULL, 0, "x")).isEqualTo("***");
        assertThat(MaskingPolicy.apply(Strategy.FULL, 4, "ABCDEFGHIJ")).isEqualTo("***");
    }

    @Test
    void R_PII_everyStrategyReturnsNullAndEmptyAsTheyAre() {
        for (Strategy s : Strategy.values()) {
            assertThat(MaskingPolicy.apply(s, 2, null)).as(s.name()).isNull();
            assertThat(MaskingPolicy.apply(s, 2, "")).as(s.name()).isEmpty();
        }
    }

    @Test
    void R_PII_keepLastKeepsTheRequestedTail() {
        assertThat(MaskingPolicy.apply(Strategy.KEEP_LAST, 4, PAN)).isEqualTo("******234K");
        assertThat(MaskingPolicy.apply(Strategy.KEEP_LAST, 3, MOBILE)).isEqualTo("*******210");
    }

    @Test
    void R_PII_keepLastWithKeepZeroHidesAll() {
        assertThat(MaskingPolicy.apply(Strategy.KEEP_LAST, 0, "ABCDEFGH")).isEqualTo("********");
    }

    @Test
    void R_PII_keepLastNeverLeavesMoreThanHalfOfTheValue() {
        // keep >= length: only floor(length / 2) characters stay
        assertThat(MaskingPolicy.apply(Strategy.KEEP_LAST, 4, "ABCDE")).isEqualTo("***DE");
        assertThat(MaskingPolicy.apply(Strategy.KEEP_LAST, 4, "ABC")).isEqualTo("**C");
        assertThat(MaskingPolicy.apply(Strategy.KEEP_LAST, 4, "AB")).isEqualTo("*B");
        assertThat(MaskingPolicy.apply(Strategy.KEEP_LAST, 4, "A")).isEqualTo("*");
        assertThat(MaskingPolicy.apply(Strategy.KEEP_LAST, 99, "ABCDEFGH")).isEqualTo("****EFGH");
    }

    @Test
    void R_PII_keepLastAroundExactlyHalf() {
        assertThat(MaskingPolicy.apply(Strategy.KEEP_LAST, 3, "ABCDEFGH")).isEqualTo("*****FGH");
        assertThat(MaskingPolicy.apply(Strategy.KEEP_LAST, 4, "ABCDEFGH")).isEqualTo("****EFGH");
        assertThat(MaskingPolicy.apply(Strategy.KEEP_LAST, 5, "ABCDEFGH")).isEqualTo("****EFGH");
    }

    @Test
    void R_PII_keepFirstKeepsTheRequestedHead() {
        assertThat(MaskingPolicy.apply(Strategy.KEEP_FIRST, 3, "ABCDEFGH")).isEqualTo("ABC*****");
        assertThat(MaskingPolicy.apply(Strategy.KEEP_FIRST, 0, "ABCDEFGH")).isEqualTo("********");
    }

    @Test
    void R_PII_keepFirstNeverLeavesMoreThanHalfOfTheValue() {
        assertThat(MaskingPolicy.apply(Strategy.KEEP_FIRST, 4, "ABCDEFGH")).isEqualTo("ABCD****");
        assertThat(MaskingPolicy.apply(Strategy.KEEP_FIRST, 5, "ABCDEFGH")).isEqualTo("ABCD****");
        assertThat(MaskingPolicy.apply(Strategy.KEEP_FIRST, 9, "ABCDEFGH")).isEqualTo("ABCD****");
        assertThat(MaskingPolicy.apply(Strategy.KEEP_FIRST, 4, "ABCDE")).isEqualTo("AB***");
        assertThat(MaskingPolicy.apply(Strategy.KEEP_FIRST, 4, "ABC")).isEqualTo("A**");
        assertThat(MaskingPolicy.apply(Strategy.KEEP_FIRST, 4, "A")).isEqualTo("*");
    }

    @Test
    void R_PII_emailKeepsFirstLetterAndDomain() {
        assertThat(MaskingPolicy.apply(Strategy.EMAIL, 0, "rakesh.sharma@fci-demo.test"))
                .isEqualTo("r***@fci-demo.test");
        assertThat(MaskingPolicy.apply(Strategy.EMAIL, 0, "a@x.test")).isEqualTo("a***@x.test");
    }

    @Test
    void R_PII_emailWithoutAtOrWithAtFirstIsHiddenCompletely() {
        assertThat(MaskingPolicy.apply(Strategy.EMAIL, 0, "not-an-address")).isEqualTo("***");
        assertThat(MaskingPolicy.apply(Strategy.EMAIL, 0, "@fci-demo.test")).isEqualTo("***");
    }

    @Test
    void R_PII_initialsKeepTheFirstLetterOfEachWord() {
        assertThat(MaskingPolicy.apply(Strategy.INITIALS, 0, "Rakesh")).isEqualTo("R***");
        assertThat(MaskingPolicy.apply(Strategy.INITIALS, 0, "Rakesh Kumar Sharma"))
                .isEqualTo("R*** K*** S***");
    }

    @Test
    void R_PII_initialsIgnoreExtraSpacesAndHideABlankValue() {
        assertThat(MaskingPolicy.apply(Strategy.INITIALS, 0, "  Rakesh   Sharma  "))
                .isEqualTo("R*** S***");
        assertThat(MaskingPolicy.apply(Strategy.INITIALS, 0, "   ")).isEqualTo("***");
        assertThat(MaskingPolicy.apply(Strategy.INITIALS, 0, "\t \n")).isEqualTo("***");
    }

    @Test
    void R_PII_hashIsAShortDeterministicFingerprint() {
        // SHA-256("abc") starts with ba7816bf
        assertThat(MaskingPolicy.apply(Strategy.HASH, 0, "abc")).isEqualTo("#ba7816bf");
        assertThat(MaskingPolicy.apply(Strategy.HASH, 0, PAN))
                .matches("#[0-9a-f]{8}")
                .isEqualTo(MaskingPolicy.apply(Strategy.HASH, 7, PAN));
        assertThat(MaskingPolicy.apply(Strategy.HASH, 0, "abc"))
                .isNotEqualTo(MaskingPolicy.apply(Strategy.HASH, 0, "abd"));
    }

    // ------------------------------------------------------------------ construction

    @Test
    void R_PII_noRuleMeansNotConfigured() {
        assertThat(policy(null, null).configured()).isFalse();
        assertThat(policy(List.of(), List.of()).configured()).isFalse();
    }

    @Test
    void R_PII_oneRuleOfEitherKindMeansConfigured() {
        assertThat(fields(f("pan", Strategy.FULL, null)).configured()).isTrue();
        assertThat(patterns(p("x", Strategy.FULL, null)).configured()).isTrue();
    }

    @Test
    void R_PII_aFieldRuleThatDoesNotCompileStopsTheStartAndIsNamed() {
        assertThatThrownBy(() -> fields(f("(", Strategy.FULL, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("masking rule name '(' does not compile")
                .hasCauseInstanceOf(java.util.regex.PatternSyntaxException.class);
    }

    @Test
    void R_PII_aPatternRuleThatDoesNotCompileStopsTheStartAndIsNamed() {
        assertThatThrownBy(() -> patterns(p("[A-", Strategy.FULL, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("masking rule regex '[A-' does not compile");
    }

    @Test
    void R_PII_aBlankOrNullNameOrRegexStopsTheStart() {
        assertThatThrownBy(() -> fields(f(null, Strategy.FULL, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a masking rule has no name");
        assertThatThrownBy(() -> fields(f("   ", Strategy.FULL, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a masking rule has no name");
        assertThatThrownBy(() -> fields(f("", Strategy.FULL, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a masking rule has no name");
        assertThatThrownBy(() -> patterns(p(null, Strategy.FULL, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a masking rule has no regex");
        assertThatThrownBy(() -> patterns(p(" ", Strategy.FULL, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a masking rule has no regex");
    }

    @Test
    void R_PII_aRuleWithoutAStrategyStopsTheStart() {
        assertThatThrownBy(() -> fields(f("pan", null, 4)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a masking rule has no strategy");
        assertThatThrownBy(() -> patterns(p("x", null, 4)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a masking rule has no strategy");
    }

    @Test
    void R_PII_moreThanTwoHundredRulesStopTheStartButTwoHundredAreAccepted() {
        List<FieldRule> two = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            two.add(f("k" + i, Strategy.FULL, null));
        }
        assertThatCode(() -> policy(two, List.of())).doesNotThrowAnyException();
        List<FieldRule> over = new ArrayList<>(two);
        over.add(f("k200", Strategy.FULL, null));
        assertThatThrownBy(() -> policy(over, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("more than 200 masking rules");
        // the limit counts both kinds together
        List<FieldRule> half = over.subList(0, 100);
        List<PatternRule> rest = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            rest.add(p("r" + i, Strategy.FULL, null));
        }
        assertThatThrownBy(() -> policy(half, rest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("more than 200 masking rules");
        assertThatCode(() -> policy(half, rest.subList(0, 100))).doesNotThrowAnyException();
    }

    @Test
    void R_PII_aNegativeOrMissingKeepIsZero() {
        assertThat(fields(f("k", Strategy.KEEP_LAST, -3)).body("{\"k\":\"ABCDEFGH\"}"))
                .isEqualTo("{\"k\":\"********\"}");
        assertThat(fields(f("k", Strategy.KEEP_LAST, null)).body("{\"k\":\"ABCDEFGH\"}"))
                .isEqualTo("{\"k\":\"********\"}");
        assertThat(fields(f("k", Strategy.KEEP_LAST, 0)).body("{\"k\":\"ABCDEFGH\"}"))
                .isEqualTo("{\"k\":\"********\"}");
        assertThat(fields(f("k", Strategy.KEEP_LAST, 1)).body("{\"k\":\"ABCDEFGH\"}"))
                .isEqualTo("{\"k\":\"*******H\"}");
    }

    @Test
    void R_PII_aKeepAboveThirtyTwoIsCappedAtThirtyTwo() {
        String value = "0123456789".repeat(10);
        String cap32 = "*".repeat(68) + value.substring(68);
        String cap31 = "*".repeat(69) + value.substring(69);
        assertThat(fields(f("k", Strategy.KEEP_LAST, 40)).body("{\"k\":\"" + value + "\"}"))
                .isEqualTo("{\"k\":\"" + cap32 + "\"}");
        assertThat(fields(f("k", Strategy.KEEP_LAST, 33)).body("{\"k\":\"" + value + "\"}"))
                .isEqualTo("{\"k\":\"" + cap32 + "\"}");
        assertThat(fields(f("k", Strategy.KEEP_LAST, 32)).body("{\"k\":\"" + value + "\"}"))
                .isEqualTo("{\"k\":\"" + cap32 + "\"}");
        assertThat(fields(f("k", Strategy.KEEP_LAST, 31)).body("{\"k\":\"" + value + "\"}"))
                .isEqualTo("{\"k\":\"" + cap31 + "\"}");
        assertThat(patterns(p("[0-9]{100}", Strategy.KEEP_FIRST, 50)).body(value))
                .isEqualTo(value.substring(0, 32) + "*".repeat(68));
    }

    // ------------------------------------------------------------------ JSON body

    @Test
    void R_PII_nullAndEmptyBodiesAreReturnedAsTheyAre() {
        MaskingPolicy policy = fields(f("pan", Strategy.FULL, null));
        assertThat(policy.body(null)).isNull();
        assertThat(policy.body("")).isEmpty();
    }

    @Test
    void R_PII_aMatchingKeyIsMaskedAndTheRestIsKeptInOrder() {
        MaskingPolicy policy = fields(f("pan", Strategy.KEEP_LAST, 4));
        assertThat(policy.body("{\"id\":1,\"pan\":\"" + PAN + "\",\"ok\":true}"))
                .isEqualTo("{\"id\":1,\"pan\":\"******234K\",\"ok\":true}");
    }

    @Test
    void R_PII_nestedObjectsAndArraysOfObjectsAreWalked() {
        MaskingPolicy policy = fields(f("pan", Strategy.KEEP_LAST, 4));
        String in =
                "{\"emp\":[{\"pan\":\"ABCPS1234K\"},{\"pan\":\"ZZZZZ9999Z\",\"x\":{\"pan\":\"ABCPS1234K\"}}],"
                    + "\"deep\":{\"a\":{\"b\":{\"pan\":\"ABCPS1234K\"}}}}";
        assertThat(policy.body(in))
                .isEqualTo(
                        "{\"emp\":[{\"pan\":\"******234K\"},{\"pan\":\"******999Z\",\"x\":{\"pan\":\"******234K\"}}],"
                            + "\"deep\":{\"a\":{\"b\":{\"pan\":\"******234K\"}}}}");
    }

    @Test
    void R_PII_aRootArrayIsWalked() {
        MaskingPolicy policy = fields(f("pan", Strategy.KEEP_LAST, 4));
        assertThat(policy.body("[{\"pan\":\"ABCPS1234K\"},\"a\",1,null,[true]]"))
                .isEqualTo("[{\"pan\":\"******234K\"},\"a\",1,null,[true]]");
    }

    @Test
    void R_PII_aNumberOrBooleanUnderAMatchingKeyIsMaskedAsAString() {
        MaskingPolicy policy =
                fields(f("mobile", Strategy.KEEP_LAST, 3), f("flag", Strategy.KEEP_LAST, 3));
        assertThat(policy.body("{\"mobile\":9876543210}")).isEqualTo("{\"mobile\":\"*******210\"}");
        // "true" (4 characters) keeps at most 2
        assertThat(policy.body("{\"flag\":true}")).isEqualTo("{\"flag\":\"**ue\"}");
        assertThat(fields(f("flag", Strategy.FULL, null)).body("{\"flag\":false}"))
                .isEqualTo("{\"flag\":\"***\"}");
    }

    @Test
    void R_PII_aMatchingKeyWithAnObjectOrArrayValueIsWalkedNotMasked() {
        MaskingPolicy policy =
                fields(f("pan", Strategy.FULL, null), f("inner", Strategy.FULL, null));
        assertThat(policy.body("{\"pan\":{\"x\":\"y\",\"inner\":\"z\"}}"))
                .isEqualTo("{\"pan\":{\"x\":\"y\",\"inner\":\"***\"}}");
        assertThat(policy.body("{\"pan\":[\"a\",{\"inner\":1}]}"))
                .isEqualTo("{\"pan\":[\"a\",{\"inner\":\"***\"}]}");
    }

    @Test
    void R_PII_nullValuesAreLeftAlone() {
        MaskingPolicy policy = fields(f("pan", Strategy.FULL, null));
        assertThat(policy.body("{\"pan\":null,\"other\":null}"))
                .isEqualTo("{\"pan\":null,\"other\":null}");
    }

    @Test
    void R_PII_theFirstMatchingRuleWins() {
        MaskingPolicy ab =
                fields(f(".*a.*", Strategy.FULL, null), f("abc", Strategy.KEEP_FIRST, 1));
        assertThat(ab.body("{\"abc\":\"ABCDEFGH\"}")).isEqualTo("{\"abc\":\"***\"}");
        MaskingPolicy ba =
                fields(f("abc", Strategy.KEEP_FIRST, 1), f(".*a.*", Strategy.FULL, null));
        assertThat(ba.body("{\"abc\":\"ABCDEFGH\",\"bar\":\"x\"}"))
                .isEqualTo("{\"abc\":\"A*******\",\"bar\":\"***\"}");
    }

    @Test
    void R_PII_aKeyIsMatchedCaseInsensitively() {
        MaskingPolicy policy = fields(f("pan", Strategy.FULL, null));
        assertThat(policy.body("{\"PAN\":\"x\",\"Pan\":\"y\",\"pan\":\"z\"}"))
                .isEqualTo("{\"PAN\":\"***\",\"Pan\":\"***\",\"pan\":\"***\"}");
    }

    @Test
    void R_PII_theNameExpressionMustMatchTheWholeKey() {
        MaskingPolicy policy = fields(f("pan", Strategy.FULL, null));
        assertThat(policy.body("{\"pan2\":\"a\",\"xpan\":\"b\",\"span\":\"c\",\"pa\":\"d\"}"))
                .isEqualTo("{\"pan2\":\"a\",\"xpan\":\"b\",\"span\":\"c\",\"pa\":\"d\"}");
        MaskingPolicy wild = fields(f(".*pan.*", Strategy.FULL, null));
        assertThat(wild.body("{\"xpan2\":\"a\"}")).isEqualTo("{\"xpan2\":\"***\"}");
    }

    @Test
    void R_PII_aNonMatchingKeyIsUntouchedEvenIfItsNumberLooksSensitive() {
        MaskingPolicy policy =
                policy(
                        List.of(f("pan", Strategy.FULL, null)),
                        List.of(p(MOBILE_RULE, Strategy.KEEP_LAST, 3)));
        assertThat(policy.body("{\"n\":9876543210,\"b\":false,\"d\":1}"))
                .isEqualTo("{\"n\":9876543210,\"b\":false,\"d\":1}");
    }

    @Test
    void R_PII_stringsUnderOtherKeysGoThroughThePatternRules() {
        MaskingPolicy policy =
                policy(
                        List.of(f("pan", Strategy.FULL, null)),
                        List.of(
                                p(MOBILE_RULE, Strategy.KEEP_LAST, 3),
                                p(PAN_RULE, Strategy.KEEP_LAST, 4)));
        assertThat(
                        policy.body(
                                "{\"remarks\":\"call 9876543210 re ABCPS1234K"
                                        + " now\",\"list\":[\"9876543210\"]}"))
                .isEqualTo(
                        "{\"remarks\":\"call *******210 re ******234K"
                                + " now\",\"list\":[\"*******210\"]}");
    }

    @Test
    void R_PII_aMatchingKeyIsNotAlsoRunThroughThePatterns() {
        // the field rule decides; the value is not masked twice
        MaskingPolicy policy =
                policy(
                        List.of(f("remarks", Strategy.FULL, null)),
                        List.of(p(MOBILE_RULE, Strategy.KEEP_LAST, 3)));
        assertThat(policy.body("{\"remarks\":\"call 9876543210\"}"))
                .isEqualTo("{\"remarks\":\"***\"}");
    }

    @Test
    void R_PII_patternRulesApplyInOrderEachOnTheOutputOfThePrevious() {
        MaskingPolicy ordered =
                patterns(
                        p("[0-9]{4}", Strategy.FULL, null),
                        p("\\*\\*\\*", Strategy.INITIALS, null));
        assertThat(ordered.body("id 1234 x")).isEqualTo("id **** x");
        MaskingPolicy reversed =
                patterns(
                        p("\\*\\*\\*", Strategy.INITIALS, null),
                        p("[0-9]{4}", Strategy.FULL, null));
        assertThat(reversed.body("id 1234 x")).isEqualTo("id *** x");
    }

    @Test
    void R_PII_aReplacementWithDollarOrBackslashIsTakenLiterally() {
        MaskingPolicy dollar = patterns(p("\\$[0-9]+", Strategy.KEEP_FIRST, 1));
        assertThat(dollar.body("cost $123 total")).isEqualTo("cost $*** total");
        MaskingPolicy slash = patterns(p("[\\\\][0-9]+", Strategy.KEEP_FIRST, 1));
        assertThat(slash.body("x\\12y")).isEqualTo("x\\**y");
    }

    @Test
    void R_PII_aRootScalarJsonIsTreatedAsText() {
        MaskingPolicy policy = patterns(p(MOBILE_RULE, Strategy.FULL, null));
        assertThat(policy.body("12")).isEqualTo("12");
        assertThat(policy.body(MOBILE)).isEqualTo("***");
        // a JSON string at the root keeps its quotes, the content is masked as text
        assertThat(policy.body("\"" + MOBILE + "\"")).isEqualTo("\"***\"");
        assertThat(policy.body("null")).isEqualTo("null");
        assertThat(policy.body("true")).isEqualTo("true");
    }

    @Test
    void R_PII_truncatedJsonAndPlainTextUseThePatternRulesOnly() {
        MaskingPolicy policy =
                policy(
                        List.of(f("pan", Strategy.FULL, null)),
                        List.of(p(PAN_RULE, Strategy.KEEP_LAST, 4)));
        assertThat(policy.body("{\"pan\":\"ABCPS1234K")).isEqualTo("{\"pan\":\"******234K");
        // the field rule is not applied to text: only the pattern masks it
        assertThat(policy.body("pan=ABCPS1234K&x=1")).isEqualTo("pan=******234K&x=1");
        assertThat(policy.body("nothing to hide")).isEqualTo("nothing to hide");
        assertThat(policy.body("   ")).isEqualTo("   ");
    }

    @Test
    void R_PII_aJsonBodyIsWrittenBackCompactly() {
        MaskingPolicy policy = fields(f("pan", Strategy.FULL, null));
        assertThat(policy.body("{ \"a\" : [ 1 , 2 , { \"b\" : true } ] }"))
                .isEqualTo("{\"a\":[1,2,{\"b\":true}]}");
    }

    @Test
    void R_PII_withoutRulesAJsonBodyIsOnlyNormalised() {
        assertThat(policy(null, null).body("{ \"a\" : \"x\" }")).isEqualTo("{\"a\":\"x\"}");
    }

    // ------------------------------------------------------------------ limit and speed

    @Test
    void R_PII_inputLongerThanTheLimitIsCutBeforeMasking() {
        assertThat(MaskingPolicy.LIMIT).isEqualTo(65_536);
        MaskingPolicy policy = patterns(p(PAN_RULE, Strategy.KEEP_LAST, 4));
        // exactly LIMIT characters: the PAN at the end is whole and is masked
        String exact = "a".repeat(MaskingPolicy.LIMIT - 10) + PAN;
        assertThat(exact).hasSize(MaskingPolicy.LIMIT);
        assertThat(policy.body(exact))
                .isEqualTo("a".repeat(MaskingPolicy.LIMIT - 10) + "******234K");
        // one more character: the last one is dropped first, so the PAN is no longer whole
        String over = "a".repeat(MaskingPolicy.LIMIT - 9) + PAN;
        assertThat(over).hasSize(MaskingPolicy.LIMIT + 1);
        assertThat(policy.body(over))
                .isEqualTo("a".repeat(MaskingPolicy.LIMIT - 9) + "ABCPS1234")
                .hasSize(MaskingPolicy.LIMIT);
    }

    @Test
    void R_PII_aVeryLongBodyIsProcessedQuicklyWhateverItsShape() {
        MaskingPolicy policy =
                policy(
                        List.of(f("pan", Strategy.FULL, null)),
                        List.of(
                                p(PAN_RULE, Strategy.KEEP_LAST, 4),
                                p(MOBILE_RULE, Strategy.KEEP_LAST, 3)));
        int mb = 1 << 20;
        List<String> bodies =
                List.of(
                        "A".repeat(mb),
                        "[".repeat(mb),
                        "{\"a\":\"" + "x".repeat(mb),
                        "{\"pan\":\"" + "9".repeat(mb),
                        "ABCPS".repeat(mb / 5));
        for (String body : bodies) {
            long start = System.nanoTime();
            String out = policy.body(body);
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertThat(out).isNotNull();
            assertThat(out.length()).isLessThanOrEqualTo(MaskingPolicy.LIMIT + 1024);
            assertThat(millis).as("elapsed ms").isLessThan(2_000);
        }
    }

    // ------------------------------------------------------------------ query

    private static final MaskingPolicy QUERY_POLICY =
            policy(
                    List.of(f("pan", Strategy.KEEP_LAST, 4), f("token", Strategy.FULL, null)),
                    List.of(p(MOBILE_RULE, Strategy.KEEP_LAST, 3)));

    @Test
    void R_PII_queryNullAndEmptyAreReturnedAsTheyAre() {
        assertThat(QUERY_POLICY.query(null)).isNull();
        assertThat(QUERY_POLICY.query("")).isEmpty();
    }

    @Test
    void R_PII_queryMasksAValueByItsParameterName() {
        assertThat(QUERY_POLICY.query("pan=ABCPS1234K&n=1&token=abc"))
                .isEqualTo("pan=******234K&n=1&token=***");
        assertThat(QUERY_POLICY.query("PAN=ABCPS1234K")).isEqualTo("PAN=******234K");
    }

    @Test
    void R_PII_queryRepeatedKeysAreAllMasked() {
        assertThat(QUERY_POLICY.query("pan=ABCPS1234K&pan=ZZZZZ9999Z"))
                .isEqualTo("pan=******234K&pan=******999Z");
    }

    @Test
    void R_PII_queryAnEmptyValueStaysEmpty() {
        assertThat(QUERY_POLICY.query("pan=")).isEqualTo("pan=");
        assertThat(QUERY_POLICY.query("pan=&x=1")).isEqualTo("pan=&x=1");
    }

    @Test
    void R_PII_queryAPairWithoutEqualsGoesThroughThePatterns() {
        assertThat(QUERY_POLICY.query("flag")).isEqualTo("flag");
        assertThat(QUERY_POLICY.query(MOBILE)).isEqualTo("*******210");
        assertThat(QUERY_POLICY.query("flag&" + MOBILE + "&x=2")).isEqualTo("flag&*******210&x=2");
    }

    @Test
    void R_PII_queryAValueUnderAnotherNameGoesThroughThePatterns() {
        assertThat(QUERY_POLICY.query("q=" + MOBILE + "&r=12")).isEqualTo("q=*******210&r=12");
    }

    @Test
    void R_PII_queryOnlyTheFirstEqualsSplitsNameFromValue() {
        assertThat(QUERY_POLICY.query("a=b=c")).isEqualTo("a=b=c");
        assertThat(QUERY_POLICY.query("token=a=b")).isEqualTo("token=***");
        assertThat(QUERY_POLICY.query("=" + MOBILE)).isEqualTo("=*******210");
    }

    @Test
    void R_PII_queryEmptyPairsKeepTheirSeparatorOnceTheOutputHasStarted() {
        assertThat(QUERY_POLICY.query("a=1&&b=2")).isEqualTo("a=1&&b=2");
    }

    @Test
    void R_PII_queryLongerThanTheLimitIsCut() {
        String out = QUERY_POLICY.query("q=" + "b".repeat(MaskingPolicy.LIMIT * 2));
        assertThat(out).hasSize(MaskingPolicy.LIMIT).startsWith("q=bbb");
        String exact = "q=" + "b".repeat(MaskingPolicy.LIMIT - 2);
        assertThat(QUERY_POLICY.query(exact)).isEqualTo(exact);
        // the part beyond the limit cannot leak: a token past the limit is gone, not shown
        String hidden = "x=" + "b".repeat(MaskingPolicy.LIMIT) + "&token=secret";
        assertThat(QUERY_POLICY.query(hidden)).doesNotContain("secret");
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void R_PII_maskingTheSameBodyInParallelGivesTheSameResult() {
        MaskingPolicy policy =
                policy(
                        List.of(
                                f("pan", Strategy.KEEP_LAST, 4),
                                f("name", Strategy.INITIALS, null)),
                        List.of(p(MOBILE_RULE, Strategy.KEEP_LAST, 3)));
        String body =
                "{\"pan\":\"ABCPS1234K\",\"name\":\"Rakesh Sharma\",\"r\":\"call 9876543210\"}";
        Set<String> results =
                IntStream.range(0, 400)
                        .parallel()
                        .mapToObj(i -> policy.body(body))
                        .collect(Collectors.toSet());
        assertThat(results)
                .containsExactly(
                        "{\"pan\":\"******234K\",\"name\":\"R*** S***\",\"r\":\"call"
                                + " *******210\"}");
    }
}
