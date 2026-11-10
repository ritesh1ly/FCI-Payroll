package in.gov.fci.payroll.integration.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.integration.business.HrmsContractCatalog.Entry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.NullNode;

/**
 * SRS-INT-1, SRS-INT-12: a call Payroll would make is checked against the HRMS contract field by
 * field: each kind (number, text, date, true / false, array, object, number-or-text) refuses a
 * value of another kind and names what was sent; an empty value and a field whose kind the sample
 * did not show are left to HRMS; a null or missing body is no body.
 */
class HrmsContractCatalogCoverageGapsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Entry entry() {
        String props =
                "{\"d\":{\"type\":\"string\",\"format\":\"date\"},\"s\":{\"type\":\"string\"},"
                        + "\"n\":{\"type\":\"integer\"},\"b\":{\"type\":\"boolean\"},"
                        + "\"a\":{\"type\":\"array\"},\"ob\":{\"type\":\"object\"},"
                        + "\"o\":{\"oneOf\":[{\"type\":\"number\"},{\"type\":\"string\"}]},"
                        + "\"u\":{}}";
        JsonNode doc =
                JSON.readTree(
                        "{\"paths\":{\"/obj\":{\"post\":{\"operationId\":\"obj\","
                                + "\"requestBody\":{\"content\":{\"application/json\":{\"schema\":"
                                + "{\"type\":\"object\",\"properties\":"
                                + props
                                + "}}}}}}}}");
        return HrmsContractCatalog.parse(doc).of("obj").orElseThrow();
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @Test
    void SRS_INT_12_eachKindRefusesAValueOfAnotherKindAndNamesIt() {
        assertThat(
                        entry().defects(
                                        null,
                                        json(
                                                "{\"n\":\"12\",\"s\":12,\"d\":true,\"b\":\"Y\","
                                                        + "\"a\":{},\"ob\":[],\"o\":false}")))
                .containsExactly(
                        "field 'n' must be a number, not text",
                        "field 's' must be text, not a number",
                        "field 'd' must be a date (yyyy-MM-dd), not true or false",
                        "field 'b' must be true or false",
                        "field 'a' must be an array",
                        "field 'ob' must be an object",
                        "field 'o' must be a number or text, not true or false");
    }

    @Test
    void SRS_INT_12_valuesOfTheRightKindEmptyValuesAndUnknownKindsPass() {
        assertThat(
                        entry().defects(
                                        null,
                                        json(
                                                "{\"n\":12,\"s\":\"x\",\"d\":\"2026-11-09\","
                                                        + "\"b\":true,\"a\":[1],\"ob\":{},"
                                                        + "\"o\":\"7\",\"u\":[true]}")))
                .isEmpty();
        assertThat(entry().defects(null, json("{\"n\":null,\"o\":7,\"d\":null}"))).isEmpty();
    }

    @Test
    void SRS_INT_12_aDateMustBeAnIsoDayAndATypeIsNamedForEachKindOfValue() {
        assertThat(entry().defects(null, json("{\"d\":\"09-11-2026\"}")))
                .containsExactly("field 'd' must be a date (yyyy-MM-dd)");
        assertThat(entry().defects(null, json("{\"d\":[1]}")))
                .containsExactly("field 'd' must be a date (yyyy-MM-dd), not an array");
        assertThat(entry().defects(null, json("{\"s\":{}}")))
                .containsExactly("field 's' must be text, not an object");
        assertThat(entry().defects(null, json("{\"n\":true}")))
                .containsExactly("field 'n' must be a number, not true or false");
    }

    @Test
    void SRS_INT_1_aNullOrMissingBodyIsNoBody() {
        String none = "the contract carries a body and none is given";
        assertThat(entry().defects(null, NullNode.getInstance())).containsExactly(none);
        assertThat(entry().defects(null, MissingNode.getInstance())).containsExactly(none);
        assertThat(entry().defects(null, null)).containsExactly(none);
    }
}
