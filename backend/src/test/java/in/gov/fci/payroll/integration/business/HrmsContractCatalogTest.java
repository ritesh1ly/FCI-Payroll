package in.gov.fci.payroll.integration.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.business.HrmsContractCatalog.Entry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-INT-1, SRS-INT-12: the OpenAPI contract of the HRMS APIs ({@code integration/
 * hrms-openapi.json}, derived from FCI's Postman collection and owned here): what it states per
 * operation (method, path, query parameters, body fields and kinds, security), the pre-flight check
 * against it (no invented required-ness), and that the code which handles each API agrees with it.
 */
class HrmsContractCatalogTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HrmsContractCatalog CATALOG = HrmsContractCatalog.load();

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static Entry entry(String code) {
        return CATALOG.of(code).orElseThrow();
    }

    @Test
    void SRS_INT_1_theDocumentListsEveryRequestOfTheCollectionOnce() {
        assertThat(CATALOG.all()).hasSize(61);
        assertThat(CATALOG.codes()).contains("token", "welcome", "request_ota", "get_ota_status");
        assertThat(CATALOG.of("no_such_api")).isEmpty();
    }

    @Test
    void SRS_INT_4_securityFollowsTheOperationNotAGlobalRule() {
        Entry token = entry("token");
        assertThat(token.needsApiKey()).isTrue();
        assertThat(token.needsBearer()).isFalse();
        Entry welcome = entry("welcome");
        assertThat(welcome.needsApiKey()).isTrue();
        assertThat(welcome.needsBearer()).isFalse();
        long withBearer = CATALOG.all().stream().filter(Entry::needsBearer).count();
        assertThat(withBearer).isEqualTo(59);
        assertThat(CATALOG.all()).allMatch(Entry::needsApiKey);
    }

    @Test
    void SRS_INT_1_methodPathQueryAndBodyShapeAreReadFromTheDocument() {
        Entry ota = entry("request_ota");
        assertThat(ota.method()).isEqualTo("POST");
        assertThat(ota.path()).isEqualTo("/request-ota");
        assertThat(ota.body()).isTrue();
        assertThat(ota.arrayBody()).isFalse();
        assertThat(ota.fields())
                .extracting(HrmsContractCatalog.Field::name)
                .contains("request_id", "emp_num");
        Entry status = entry("get_ota_status");
        assertThat(status.method()).isEqualTo("GET");
        assertThat(status.query()).containsExactly("request_id", "emp_ota_id");
        assertThat(status.body()).isFalse();
        assertThat(entry("request_direct_payroll").arrayBody()).isTrue();
        assertThat(entry("post_cafeteria_allowance").arrayBody()).isTrue();
        assertThat(entry("cancel_ota").method()).isEqualTo("PUT");
    }

    @Test
    void SRS_INT_12_aCallThatMatchesTheDocumentHasNoDefect() {
        Entry ota = entry("request_ota");
        assertThat(
                        ota.defects(
                                null,
                                json("{\"request_id\":1,\"emp_num\":160662,\"remarks\":\"x\"}")))
                .isEmpty();
        assertThat(
                        entry("get_ota_status")
                                .defects(Map.of("request_id", "1", "emp_ota_id", "2"), null))
                .isEmpty();
    }

    @Test
    void SRS_INT_12_requiredFieldsAreNotInventedASmallerBodyIsNotADefect() {
        assertThat(entry("request_ota").defects(null, json("{\"request_id\":1}"))).isEmpty();
        assertThat(entry("get_ota_status").defects(Map.of(), null)).isEmpty();
    }

    @Test
    void SRS_INT_12_anUnknownParameterOrFieldIsRefused() {
        assertThat(entry("get_ota_status").defects(Map.of("nope", "1"), null))
                .containsExactly("query parameter 'nope' is not in the contract");
        assertThat(entry("request_ota").defects(null, json("{\"request_id\":1,\"nope\":2}")))
                .containsExactly("field 'nope' is not in the contract");
    }

    @Test
    void SRS_INT_12_aBodyWhereNoneIsStatedOrNoneWhereOneIsStatedIsRefused() {
        assertThat(entry("get_ota_status").defects(null, json("{\"a\":1}")))
                .containsExactly("the contract carries no body for this API");
        assertThat(entry("request_ota").defects(null, null))
                .containsExactly("the contract carries a body and none is given");
        assertThat(entry("request_ota").defects(null, json("null")))
                .containsExactly("the contract carries a body and none is given");
    }

    @Test
    void SRS_INT_12_anArrayBodyIsCheckedRecordByRecord() {
        Entry direct = entry("request_direct_payroll");
        assertThat(direct.defects(null, json("{\"request_id\":1}")))
                .containsExactly("the body must be an array of records");
        // "at least one record" is not in a Postman sample, so an empty array is HRMS's to judge
        assertThat(direct.defects(null, json("[]"))).isEmpty();
        assertThat(
                        direct.defects(
                                null, json("[{\"request_id\":1},{\"request_id\":2,\"zzz\":1},3]")))
                .containsExactly(
                        "record 2: field 'zzz' is not in the contract",
                        "record 3: must be an object");
        assertThat(entry("request_ota").defects(null, json("[{\"request_id\":1}]")))
                .containsExactly("the body must be a single object, not an array");
        assertThat(entry("request_ota").defects(null, json("5")))
                .containsExactly("must be an object");
    }

    @Test
    void SRS_INT_12_aScalarFieldTakesANumberOrTextAndNothingElse() {
        Entry ota = entry("request_ota");
        // One Postman sample cannot tell a number from text (a bill number is 9001 in one, "b98" in
        // another).
        assertThat(ota.defects(null, json("{\"request_id\":\"abc\",\"remarks\":5}"))).isEmpty();
        assertThat(ota.defects(null, json("{\"request_id\":12.5}"))).isEmpty();
        assertThat(ota.defects(null, json("{\"request_id\":null,\"remarks\":null}"))).isEmpty();
        assertThat(ota.defects(null, json("{\"remarks\":true}")))
                .containsExactly("field 'remarks' must be a number or text, not true or false");
        assertThat(ota.defects(null, json("{\"request_id\":[1]}")))
                .containsExactly("field 'request_id' must be a number or text, not an array");
        assertThat(ota.defects(null, json("{\"request_id\":{}}")))
                .containsExactly("field 'request_id' must be a number or text, not an object");
    }

    @Test
    void SRS_INT_12_aDateFieldMustBeAnIsoDate() {
        Entry loan = entry("request_advance_loan_hdr");
        String dateField =
                loan.fields().stream()
                        .filter(f -> f.kind().equals("date"))
                        .findFirst()
                        .orElseThrow()
                        .name();
        assertThat(loan.defects(null, json("{\"" + dateField + "\":\"2026-02-30\"}")))
                .containsExactly("field '" + dateField + "' must be a date (yyyy-MM-dd)");
        assertThat(loan.defects(null, json("{\"" + dateField + "\":20260230}")))
                .containsExactly(
                        "field '" + dateField + "' must be a date (yyyy-MM-dd), not a number");
        assertThat(loan.defects(null, json("{\"" + dateField + "\":\"2026-02-27\"}"))).isEmpty();
    }

    @Test
    void SRS_INT_12_aSampleWithNullsAllowsAnyKindForThatField() {
        Entry direct = entry("request_direct_payroll");
        assertThat(direct.fields()).allMatch(f -> f.kind().equals("null"));
        assertThat(
                        direct.defects(
                                null,
                                json("[{\"request_id\":\"x\",\"amt\":1.5,\"valid_from\":true}]")))
                .isEmpty();
    }

    @Test
    void SRS_INT_1_everyApiTheCodeHandlesAgreesWithTheDocument() {
        for (HrmsApi api : HrmsApi.values()) {
            Entry e =
                    CATALOG.of(api.code())
                            .orElseThrow(
                                    () -> new AssertionError(api.code() + " not in the contract"));
            assertThat(e.method()).as(api.code()).isEqualTo(api.method());
            assertThat(e.path()).as(api.code()).isEqualTo(api.collectionPath());
        }
    }

    @Test
    void SRS_INT_1_theFieldsOfEachIntakeContractEqualTheDocumentsFields() {
        for (HrmsApi api : HrmsApi.values()) {
            HrmsContracts.request(api)
                    .ifPresent(
                            request -> {
                                Entry e = entry(api.code());
                                List<String> documented =
                                        e.fields().stream()
                                                .map(HrmsContractCatalog.Field::name)
                                                .filter(n -> !n.startsWith("attribute"))
                                                .toList();
                                assertThat(request.fields()).as(api.code()).containsAll(documented);
                                assertThat(request.arrayBody())
                                        .as(api.code())
                                        .isEqualTo(e.arrayBody());
                                // the code's list adds attribute1..20; no other field may differ
                                assertThat(
                                                request.fields().stream()
                                                        .filter(n -> !n.startsWith("attribute"))
                                                        .toList())
                                        .as(api.code())
                                        .containsExactlyInAnyOrderElementsOf(documented);
                            });
        }
    }

    @Test
    void SRS_INT_1_aDocumentWithoutAResourceOrWithOnlyPathsParses() {
        HrmsContractCatalog tiny =
                HrmsContractCatalog.parse(
                        json(
                                "{\"paths\":{\"/x\":{\"get\":{\"operationId\":\"x\"},\"post\":{\"operationId\":\"y\","
                                    + "\"security\":[{\"BearerAuth\":[]}],\"parameters\":[{\"name\":\"h\",\"in\":\"header\"},"
                                    + "{\"name\":\"q\",\"in\":\"query\"}],\"requestBody\":{\"content\":{\"application/json\":"
                                    + "{\"schema\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"boolean\"},"
                                    + "\"b\":{\"type\":\"object\"},\"c\":{\"type\":\"array\"},\"d\":{}}}}}}}}}}"));
        assertThat(tiny.of("x").orElseThrow().needsApiKey()).isFalse();
        assertThat(tiny.of("x").orElseThrow().body()).isFalse();
        Entry y = tiny.of("y").orElseThrow();
        assertThat(y.needsBearer()).isTrue();
        assertThat(y.query()).containsExactly("q");
        assertThat(y.fields())
                .extracting(HrmsContractCatalog.Field::kind)
                .containsExactly("boolean", "object", "array", "null");
        assertThat(y.defects(null, json("{\"a\":1}")))
                .containsExactly("field 'a' must be true or false");
        assertThat(y.defects(null, json("{\"b\":1}")))
                .containsExactly("field 'b' must be an object");
        assertThat(y.defects(null, json("{\"c\":1}")))
                .containsExactly("field 'c' must be an array");
        assertThat(y.defects(null, json("{\"d\":1}"))).isEmpty();
    }
}
