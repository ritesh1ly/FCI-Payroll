package in.gov.fci.payroll.integration.config;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.integration.config.HrmsApiProperties.ApiUrl;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Call;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Limit;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Pool;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.PoolSpec;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Resolved;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * SRS-INT-6, SRS-INT-16, R-INT, R-RULE: the per-API call policy of the HRMS client - own call
 * settings over the defaults over the fallbacks (the interface time limit and the shared pool),
 * flags that accept Y/N, errors that stop the start and warnings that only report.
 */
class HrmsApiPropertiesPolicyTest {

    private static final HrmsClientProperties CLIENT =
            new HrmsClientProperties(null, null, null, null, null, null, null, null, null, null);
    private static final String OTA = "request_ota";

    private static Call call(
            Integer connect,
            Integer read,
            Integer timeout,
            Boolean mask,
            Boolean req,
            Boolean res,
            Pool pool) {
        return new Call(connect, read, timeout, mask, req, res, pool);
    }

    private static Call timeouts(Integer connect, Integer read, Integer timeout) {
        return call(connect, read, timeout, null, null, null, null);
    }

    private static Call flags(Boolean mask, Boolean req, Boolean res) {
        return call(null, null, null, mask, req, res, null);
    }

    private static Call pooled(Pool pool) {
        return call(null, null, null, null, null, null, pool);
    }

    private static Pool pool(
            Integer max,
            Integer pending,
            Integer pendingMs,
            Integer idle,
            Integer life,
            Integer evict) {
        return new Pool(max, pending, pendingMs, idle, life, evict, null);
    }

    private static Pool leasing(String leasing) {
        return new Pool(null, null, null, null, null, null, leasing);
    }

    private static HrmsApiProperties with(Call own, Call defaults) {
        Map<String, ApiUrl> api = new HashMap<>();
        api.put(OTA, new ApiUrl("p", null, own));
        return new HrmsApiProperties(null, api, null, null, defaults);
    }

    private static Resolved resolve(Call own, Call defaults) {
        return with(own, defaults).resolve(OTA, 10, CLIENT);
    }

    private static HrmsApiProperties nullEntryProps(Call defaults) {
        Map<String, ApiUrl> api = new HashMap<>();
        api.put(OTA, null);
        return new HrmsApiProperties(null, api, null, null, defaults);
    }

    private static HrmsApiProperties apiWith(String code, Call call) {
        Map<String, ApiUrl> api = new HashMap<>();
        api.put(code, new ApiUrl("p", null, call));
        return new HrmsApiProperties(null, api, null, null, null);
    }

    // ---- resolve: timeouts ----

    @Test
    void SRS_INT_6_withNothingSetTheTimeoutsAreTheInterfaceTimeLimit() {
        Resolved r = new HrmsApiProperties(null, null).resolve("any_code", 7, CLIENT);
        assertThat(r.connectTimeoutMs()).isEqualTo(7000);
        assertThat(r.readTimeoutMs()).isEqualTo(7000);
        assertThat(r.timeoutMs()).isEqualTo(7000);
    }

    @Test
    void SRS_INT_6_theFallbackTimeoutIsAtLeastOneSecond() {
        assertThat(new HrmsApiProperties(null, null).resolve("x", 0, CLIENT).timeoutMs())
                .isEqualTo(1000);
        assertThat(new HrmsApiProperties(null, null).resolve("x", -5, CLIENT).connectTimeoutMs())
                .isEqualTo(1000);
    }

    @Test
    void SRS_INT_6_ownTimeoutBeatsDefaultsBeatsFallback() {
        Resolved own = resolve(timeouts(100, 200, 300), timeouts(400, 500, 600));
        assertThat(own.connectTimeoutMs()).isEqualTo(100);
        assertThat(own.readTimeoutMs()).isEqualTo(200);
        assertThat(own.timeoutMs()).isEqualTo(300);
        Resolved common = resolve(null, timeouts(400, 500, 600));
        assertThat(common.connectTimeoutMs()).isEqualTo(400);
        assertThat(common.readTimeoutMs()).isEqualTo(500);
        assertThat(common.timeoutMs()).isEqualTo(600);
        Resolved none = resolve(timeouts(null, null, null), timeouts(null, null, null));
        assertThat(none.connectTimeoutMs()).isEqualTo(10_000);
        assertThat(none.readTimeoutMs()).isEqualTo(10_000);
        assertThat(none.timeoutMs()).isEqualTo(10_000);
    }

    @Test
    void SRS_INT_6_aTimeoutThatIsNotPositiveCountsAsUnset() {
        Resolved r = resolve(timeouts(0, -1, 0), timeouts(0, 450, -3));
        assertThat(r.connectTimeoutMs()).as("own 0, default 0").isEqualTo(10_000);
        assertThat(r.readTimeoutMs()).as("own -1, default 450").isEqualTo(450);
        assertThat(r.timeoutMs()).as("own 0, default -3").isEqualTo(10_000);
    }

    @Test
    void SRS_INT_6_aTimeoutIsTakenFromTheDefaultsWhenTheOwnIsMissingOrNotPositive() {
        assertThat(resolve(timeouts(null, null, null), timeouts(11, 12, 13)).timeoutMs())
                .isEqualTo(13);
        assertThat(resolve(timeouts(0, 0, 0), timeouts(11, 12, 13)).readTimeoutMs()).isEqualTo(12);
    }

    @Test
    void SRS_INT_6_anUnknownCodeAndANullApiMapUseTheDefaultsOrTheFallbacks() {
        HrmsApiProperties props =
                new HrmsApiProperties(null, Map.of(), null, null, timeouts(21, 22, 23));
        Resolved r = props.resolve("no_such_api", 10, CLIENT);
        assertThat(r.connectTimeoutMs()).isEqualTo(21);
        assertThat(r.readTimeoutMs()).isEqualTo(22);
        assertThat(r.timeoutMs()).isEqualTo(23);
        Resolved nullMap =
                new HrmsApiProperties(null, null, null, null, timeouts(21, 22, 23))
                        .resolve(OTA, 10, CLIENT);
        assertThat(nullMap.timeoutMs()).isEqualTo(23);
        Resolved nullEntry = nullEntryProps(null).resolve(OTA, 10, CLIENT);
        assertThat(nullEntry.timeoutMs()).isEqualTo(10_000);
    }

    @Test
    void SRS_INT_6_anApiEntryWithoutACallPolicyUsesTheDefaults() {
        Map<String, ApiUrl> api = Map.of(OTA, new ApiUrl("path"));
        HrmsApiProperties props = new HrmsApiProperties(null, api, null, null, timeouts(5, 6, 7));
        assertThat(props.resolve(OTA, 10, CLIENT).timeoutMs()).isEqualTo(7);
    }

    // ---- resolve: flags ----

    @Test
    void SRS_INT_6_theThreeFlagsAreOnWhenNothingStatesThem() {
        for (Resolved r :
                List.of(
                        resolve(null, null),
                        resolve(flags(null, null, null), flags(null, null, null)))) {
            assertThat(r.maskData()).isTrue();
            assertThat(r.logRequest()).isTrue();
            assertThat(r.logResponse()).isTrue();
        }
    }

    @Test
    void SRS_INT_6_defaultsFlagsApplyWhenTheApiHasNone() {
        Resolved off = resolve(null, flags(false, false, false));
        assertThat(off.maskData()).isFalse();
        assertThat(off.logRequest()).isFalse();
        assertThat(off.logResponse()).isFalse();
        Resolved on = resolve(flags(null, null, null), flags(true, true, true));
        assertThat(on.maskData()).isTrue();
        assertThat(on.logRequest()).isTrue();
        assertThat(on.logResponse()).isTrue();
    }

    @Test
    void SRS_INT_6_anApiFlagOverridesTheDefaultsBothWays() {
        Resolved ownOff = resolve(flags(false, false, false), flags(true, true, true));
        assertThat(ownOff.maskData()).isFalse();
        assertThat(ownOff.logRequest()).isFalse();
        assertThat(ownOff.logResponse()).isFalse();
        Resolved ownOn = resolve(flags(true, true, true), flags(false, false, false));
        assertThat(ownOn.maskData()).isTrue();
        assertThat(ownOn.logRequest()).isTrue();
        assertThat(ownOn.logResponse()).isTrue();
    }

    @Test
    void SRS_INT_6_theFlagsResolveIndependentlyOfEachOther() {
        Resolved r = resolve(flags(false, null, true), flags(null, false, false));
        assertThat(r.maskData()).isFalse();
        assertThat(r.logRequest()).isFalse();
        assertThat(r.logResponse()).isTrue();
    }

    // ---- resolve: pool ----

    @Test
    void SRS_INT_16_withNoPoolSectionTheSharedPoolOfTheClientIsUsed() {
        assertThat(resolve(null, null).pool())
                .isEqualTo(new PoolSpec("shared", 50, 100, 5000L, 30L, 300L, 30L, false));
    }

    @Test
    void SRS_INT_16_theSharedPoolFollowsTheClientSettings() {
        HrmsClientProperties client =
                new HrmsClientProperties(7, 8, 9, 10, 11, 12, null, null, null, null);
        PoolSpec p = new HrmsApiProperties(null, null).resolve(OTA, 10, client).pool();
        assertThat(p).isEqualTo(new PoolSpec("shared", 7, 8, 9000L, 10L, 11L, 12L, false));
    }

    @Test
    void SRS_INT_16_aDefaultsPoolIsNamedDefaultsAndBeatsTheShared() {
        PoolSpec p = resolve(null, pooled(new Pool(20, 21, 22, 23, 24, 25, "lifo"))).pool();
        assertThat(p).isEqualTo(new PoolSpec("defaults", 20, 21, 22L, 23L, 24L, 25L, true));
    }

    @Test
    void SRS_INT_16_anApiPoolIsNamedAfterTheApiAndBeatsTheDefaults() {
        PoolSpec p =
                resolve(
                                pooled(new Pool(1, 2, 3, 4, 5, 6, "LIFO")),
                                pooled(new Pool(20, 21, 22, 23, 24, 25, "FIFO")))
                        .pool();
        assertThat(p).isEqualTo(new PoolSpec("api-request_ota", 1, 2, 3L, 4L, 5L, 6L, true));
    }

    @Test
    void SRS_INT_16_eachPoolFieldFallsThroughOwnThenDefaultsThenClient() {
        Pool own = pool(1, null, 0, -1, null, 6);
        Pool common = pool(20, 21, 22, 23, null, null);
        PoolSpec p = resolve(pooled(own), pooled(common)).pool();
        assertThat(p.key()).isEqualTo("api-request_ota");
        assertThat(p.maxConnections()).as("own").isEqualTo(1);
        assertThat(p.pendingAcquireMaxCount()).as("defaults").isEqualTo(21);
        assertThat(p.pendingAcquireTimeoutMs()).as("own 0 -> defaults").isEqualTo(22L);
        assertThat(p.maxIdleSeconds()).as("own -1 -> defaults").isEqualTo(23L);
        assertThat(p.maxLifeSeconds()).as("client").isEqualTo(300L);
        assertThat(p.evictIntervalSeconds()).as("own").isEqualTo(6L);
    }

    @Test
    void SRS_INT_16_poolFieldsThatAreNotPositiveInBothFallToTheClient() {
        Pool bad = pool(0, -1, 0, -1, 0, -1);
        PoolSpec p = resolve(pooled(bad), pooled(bad)).pool();
        assertThat(p)
                .isEqualTo(new PoolSpec("api-request_ota", 50, 100, 5000L, 30L, 300L, 30L, false));
    }

    @Test
    void SRS_INT_16_leasingIsLifoIgnoringCaseElseFifo() {
        assertThat(resolve(pooled(leasing("LIFO")), null).pool().lifo()).isTrue();
        assertThat(resolve(pooled(leasing("lifo")), null).pool().lifo()).isTrue();
        assertThat(resolve(pooled(leasing("FIFO")), null).pool().lifo()).isFalse();
        assertThat(resolve(pooled(leasing("other")), null).pool().lifo()).isFalse();
        assertThat(resolve(pooled(leasing(null)), null).pool().lifo()).isFalse();
    }

    @Test
    void SRS_INT_16_leasingOwnBeatsDefaultsAndAnUnsetOwnTakesTheDefaults() {
        assertThat(resolve(pooled(leasing("FIFO")), pooled(leasing("LIFO"))).pool().lifo())
                .isFalse();
        assertThat(resolve(pooled(leasing("LIFO")), pooled(leasing("FIFO"))).pool().lifo())
                .isTrue();
        assertThat(resolve(pooled(leasing(null)), pooled(leasing("LIFO"))).pool().lifo()).isTrue();
        assertThat(resolve(null, pooled(leasing("LIFO"))).pool().lifo()).isTrue();
        assertThat(resolve(null, pooled(leasing(null))).pool().lifo()).isFalse();
    }

    // ---- Spring binding of Y/N ----

    @Test
    void R_INT_springBindsYAndNAsBooleansInTheCallPolicy() {
        Map<String, Object> source = new HashMap<>();
        String p = "fci.integration.hrms.";
        source.put(p + "api.request_ota.path", "request-ota");
        source.put(p + "api.request_ota.call.mask-data", "N");
        source.put(p + "api.request_ota.call.log-request", "Y");
        source.put(p + "api.request_ota.call.timeout-ms", "9000");
        source.put(p + "api.request_ota.call.pool.max-connections", "12");
        source.put(p + "api.request_ota.call.pool.leasing", "LIFO");
        source.put(p + "defaults.log-response", "n");
        source.put(p + "defaults.mask-data", "y");
        HrmsApiProperties bound =
                new Binder(
                                java.util.List.of(new MapConfigurationPropertySource(source)),
                                null,
                                flagConversion())
                        .bind("fci.integration.hrms", HrmsApiProperties.class)
                        .get();
        Call own = bound.api().get(OTA).call();
        assertThat(own.maskData()).isFalse();
        assertThat(own.logRequest()).isTrue();
        assertThat(own.logResponse()).isNull();
        assertThat(own.timeoutMs()).isEqualTo(9000);
        assertThat(own.pool().maxConnections()).isEqualTo(12);
        assertThat(bound.defaults().logResponse()).isFalse();
        assertThat(bound.defaults().maskData()).isTrue();
        Resolved r = bound.resolve(OTA, 10, CLIENT);
        assertThat(r.maskData()).isFalse();
        assertThat(r.logRequest()).isTrue();
        assertThat(r.logResponse()).isFalse();
        assertThat(r.pool().key()).isEqualTo("api-request_ota");
        assertThat(r.pool().lifo()).isTrue();
        assertThat(bound.api().get(OTA).path()).isEqualTo("request-ota");
    }

    // ---- constructors ----

    @Test
    void SRS_INT_16_theShortConstructorsLeaveTheCallPolicyUnset() {
        assertThat(new ApiUrl("p").call()).isNull();
        assertThat(new ApiUrl("p").direction()).isNull();
        assertThat(new ApiUrl("p", "OUTBOUND").call()).isNull();
        assertThat(new ApiUrl("p", "OUTBOUND").inbound()).isFalse();
        assertThat(new HrmsApiProperties("b", null, null, null).defaults()).isNull();
        assertThat(new HrmsApiProperties("b", null).defaults()).isNull();
        assertThat(new HrmsApiProperties("b", null).baseUrl()).isEqualTo("b");
    }

    // ---- errors ----

    @Test
    void R_INT_aConsistentSettingHasNoErrors() {
        assertThat(new HrmsApiProperties(null, null).errors()).isEmpty();
        HrmsApiProperties ok =
                new HrmsApiProperties(
                        null,
                        Map.of(
                                OTA,
                                new ApiUrl("p", null, pooled(new Pool(1, 1, 1, 1, 1, 1, "fifo")))),
                        Map.of(
                                "connection_test",
                                new Limit(1, 1),
                                "get_ota_status",
                                new Limit(1, 1)),
                        null,
                        timeouts(1, 1, 1));
        assertThat(ok.errors()).isEmpty();
    }

    @Test
    void R_INT_anUnknownApiCodeIsAnError() {
        assertThat(apiWith("request_otaa", null).errors())
                .containsExactly("api.request_otaa is not an HRMS API code");
    }

    @Test
    void R_INT_anUnknownBulkheadCodeIsAnErrorButConnectionTestIsAllowed() {
        HrmsApiProperties props =
                new HrmsApiProperties(
                        null,
                        null,
                        Map.of("connection_test", new Limit(1, 1), "bogus", new Limit(1, 1)),
                        null);
        assertThat(props.errors()).containsExactly("bulkhead.bogus is not an HRMS API code");
    }

    @Test
    void R_INT_aNullApiEntryIsSkipped() {
        assertThat(nullEntryProps(null).errors()).isEmpty();
        assertThat(nullEntryProps(null).warnings()).isEmpty();
    }

    @Test
    void R_INT_everyNumberBelowOneInTheCallIsAnError() {
        String w = "api.request_ota.call";
        assertThat(apiWith(OTA, timeouts(0, 0, 0)).errors())
                .containsExactly(
                        w + ".connect-timeout-ms must be 1 or more, not 0",
                        w + ".read-timeout-ms must be 1 or more, not 0",
                        w + ".timeout-ms must be 1 or more, not 0");
        assertThat(apiWith(OTA, timeouts(-1, null, null)).errors())
                .containsExactly(w + ".connect-timeout-ms must be 1 or more, not -1");
        assertThat(apiWith(OTA, timeouts(null, -2, null)).errors())
                .containsExactly(w + ".read-timeout-ms must be 1 or more, not -2");
        assertThat(apiWith(OTA, timeouts(null, null, -3)).errors())
                .containsExactly(w + ".timeout-ms must be 1 or more, not -3");
    }

    @Test
    void R_INT_everyNumberBelowOneInThePoolIsAnError() {
        String w = "api.request_ota.call.pool";
        assertThat(apiWith(OTA, pooled(pool(0, 0, 0, 0, 0, 0))).errors())
                .containsExactly(
                        w + ".max-connections must be 1 or more, not 0",
                        w + ".pending-acquire-max-count must be 1 or more, not 0",
                        w + ".pending-acquire-timeout-ms must be 1 or more, not 0",
                        w + ".max-idle-seconds must be 1 or more, not 0",
                        w + ".max-life-seconds must be 1 or more, not 0",
                        w + ".evict-interval-seconds must be 1 or more, not 0");
        assertThat(apiWith(OTA, pooled(pool(-1, null, null, null, null, null))).errors())
                .containsExactly(w + ".max-connections must be 1 or more, not -1");
        assertThat(apiWith(OTA, pooled(pool(null, -2, null, null, null, null))).errors())
                .containsExactly(w + ".pending-acquire-max-count must be 1 or more, not -2");
        assertThat(apiWith(OTA, pooled(pool(null, null, -3, null, null, null))).errors())
                .containsExactly(w + ".pending-acquire-timeout-ms must be 1 or more, not -3");
        assertThat(apiWith(OTA, pooled(pool(null, null, null, -4, null, null))).errors())
                .containsExactly(w + ".max-idle-seconds must be 1 or more, not -4");
        assertThat(apiWith(OTA, pooled(pool(null, null, null, null, -5, null))).errors())
                .containsExactly(w + ".max-life-seconds must be 1 or more, not -5");
        assertThat(apiWith(OTA, pooled(pool(null, null, null, null, null, -6))).errors())
                .containsExactly(w + ".evict-interval-seconds must be 1 or more, not -6");
    }

    @Test
    void R_INT_aLeaseOrderThatIsNotFifoOrLifoIsAnError() {
        assertThat(apiWith(OTA, pooled(leasing("RANDOM"))).errors())
                .containsExactly(
                        "api.request_ota.call.pool.leasing must be FIFO or LIFO, not 'RANDOM'");
        assertThat(apiWith(OTA, pooled(leasing("Fifo"))).errors()).isEmpty();
        assertThat(apiWith(OTA, pooled(leasing("lifo"))).errors()).isEmpty();
        assertThat(apiWith(OTA, pooled(leasing(null))).errors()).isEmpty();
    }

    @Test
    void R_INT_theDefaultsAreCheckedLikeAnApi() {
        HrmsApiProperties props =
                new HrmsApiProperties(
                        null,
                        null,
                        null,
                        null,
                        call(0, null, null, null, null, null, leasing("X")));
        assertThat(props.errors())
                .containsExactly(
                        "defaults.connect-timeout-ms must be 1 or more, not 0",
                        "defaults.pool.leasing must be FIFO or LIFO, not 'X'");
    }

    @Test
    void R_INT_aCallWithoutAPoolHasNoPoolErrors() {
        assertThat(apiWith(OTA, timeouts(1, 1, 1)).errors()).isEmpty();
    }

    // ---- warnings ----

    @Test
    void R_INT_aConsistentSettingHasNoWarnings() {
        assertThat(new HrmsApiProperties(null, null).warnings()).isEmpty();
        assertThat(apiWith(OTA, timeouts(100, 200, 300)).warnings()).isEmpty();
        assertThat(apiWith(OTA, null).warnings()).isEmpty();
    }

    @Test
    void R_INT_aConnectOrReadTimeLongerThanTheWholeCallDrawsAWarning() {
        String w = "api.request_ota.call";
        assertThat(apiWith(OTA, timeouts(500, null, 400)).warnings())
                .containsExactly(
                        w
                                + ": connect-timeout-ms is longer than timeout-ms and can never be"
                                + " reached");
        assertThat(apiWith(OTA, timeouts(null, 500, 400)).warnings())
                .containsExactly(
                        w
                                + ": read-timeout-ms is longer than timeout-ms and can never be"
                                + " reached");
        assertThat(apiWith(OTA, timeouts(500, 600, 400)).warnings()).hasSize(2);
    }

    @Test
    void R_INT_equalTimesAndAnUnusableWholeCallTimeGiveNoWarning() {
        assertThat(apiWith(OTA, timeouts(400, 400, 400)).warnings()).isEmpty();
        assertThat(apiWith(OTA, timeouts(500, 600, null)).warnings()).isEmpty();
        assertThat(apiWith(OTA, timeouts(500, 600, 0)).warnings()).isEmpty();
        assertThat(apiWith(OTA, timeouts(500, 600, -1)).warnings()).isEmpty();
    }

    @Test
    void R_INT_aPoolOverFiveHundredConnectionsDrawsAWarningAtTheBoundary() {
        assertThat(apiWith(OTA, pooled(pool(500, null, null, null, null, null))).warnings())
                .isEmpty();
        assertThat(apiWith(OTA, pooled(pool(501, null, null, null, null, null))).warnings())
                .containsExactly("api.request_ota.call.pool.max-connections 501 is very large");
    }

    @Test
    void R_INT_idleLongerThanLifeDrawsAWarningButEqualDoesNot() {
        assertThat(apiWith(OTA, pooled(pool(null, null, null, 31, 30, null))).warnings())
                .containsExactly(
                        "api.request_ota.call.pool: max-idle-seconds is longer than"
                                + " max-life-seconds");
        assertThat(apiWith(OTA, pooled(pool(null, null, null, 30, 30, null))).warnings()).isEmpty();
        assertThat(apiWith(OTA, pooled(pool(null, null, null, 99, null, null))).warnings())
                .isEmpty();
        assertThat(apiWith(OTA, pooled(pool(null, null, null, null, 5, null))).warnings())
                .isEmpty();
    }

    private static HrmsApiProperties poolTotals(int... sizes) {
        Map<String, ApiUrl> api = new HashMap<>();
        String[] codes = {"request_ota", "token", "get_ota_status", "get_loan_status"};
        for (int i = 0; i < sizes.length; i++) {
            api.put(
                    codes[i],
                    new ApiUrl("p", null, pooled(pool(sizes[i], null, null, null, null, null))));
        }
        return new HrmsApiProperties(null, api, null, null, null);
    }

    @Test
    void R_INT_theTotalOfThePerApiPoolsOverAThousandDrawsAWarningAtTheBoundary() {
        assertThat(poolTotals(400, 400, 200).warnings()).isEmpty();
        assertThat(poolTotals(400, 400, 201).warnings())
                .containsExactly("the per-API pools together allow 1001 connections");
    }

    @Test
    void R_INT_defaultsAreCheckedForWarningsButNotCountedInThePoolTotal() {
        HrmsApiProperties props =
                new HrmsApiProperties(
                        null,
                        Map.of(
                                OTA,
                                new ApiUrl(
                                        "p",
                                        null,
                                        pooled(pool(600, null, null, null, null, null)))),
                        null,
                        null,
                        call(900, null, 800, null, null, null, pool(900, null, null, 2, 1, null)));
        assertThat(props.warnings())
                .containsExactlyInAnyOrder(
                        "api.request_ota.call.pool.max-connections 600 is very large",
                        "defaults: connect-timeout-ms is longer than timeout-ms and can never be"
                                + " reached",
                        "defaults.pool.max-connections 900 is very large",
                        "defaults.pool: max-idle-seconds is longer than max-life-seconds");
    }

    /**
     * The conversion Spring Boot applies to configuration, with the project's Y / N flag converter.
     */
    private static org.springframework.core.convert.ConversionService flagConversion() {
        org.springframework.core.convert.support.DefaultConversionService conversion =
                new org.springframework.core.convert.support.DefaultConversionService();
        conversion.addConverter(new in.gov.fci.payroll.platform.config.YesNoBooleanConverter());
        return conversion;
    }
}
