package in.gov.fci.payroll.integration.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.integration.business.HrmsContractCatalog.Entry;
import in.gov.fci.payroll.integration.business.HrmsContractCatalog.Field;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-INT-1, SRS-INT-12: how the contract document is read into body shape and field kinds, on a
 * small synthetic OpenAPI document (the kind of every field, array against object body, no body).
 */
class HrmsContractCatalogMutationGapsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode body(String schema) {
        return JSON.readTree(
                "{\"requestBody\":{\"content\":{\"application/json\":{\"schema\":"
                        + schema
                        + "}}}}");
    }

    private static HrmsContractCatalog catalog() {
        String props =
                "{\"d\":{\"type\":\"string\",\"format\":\"date\"},\"s\":{\"type\":\"string\"},"
                    + "\"f\":{\"type\":\"string\",\"format\":\"date-time\"},"
                    + "\"n\":{\"type\":\"integer\"},"
                    + "\"o\":{\"oneOf\":[{\"type\":\"number\"},{\"type\":\"string\"}],\"nullable\":true},"
                    + "\"t\":{\"type\":\"string\",\"oneOf\":[{\"type\":\"number\"}]},\"u\":{},"
                    + "\"b\":{\"type\":\"boolean\",\"nullable\":true}}";
        JsonNode doc =
                JSON.readTree(
                        "{\"paths\":{\"/arr\":{\"post\":{\"operationId\":\"arr\","
                            + "\"requestBody\":{\"content\":{\"application/json\":{\"schema\":"
                            + "{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":"
                                + props
                                + "}}}}}}},"
                                + "\"/obj\":{\"post\":{\"operationId\":\"obj\","
                                + "\"requestBody\":{\"content\":{\"application/json\":{\"schema\":"
                                + "{\"type\":\"object\",\"properties\":"
                                + props
                                + "}}}}}},"
                                + "\"/none\":{\"get\":{\"operationId\":\"none\"}}}}");
        return HrmsContractCatalog.parse(doc);
    }

    @Test
    void SRS_INT_1_anArrayBodyTakesItsFieldsFromTheItemsAndAnObjectBodyFromItself() {
        HrmsContractCatalog c = catalog();
        Entry arr = c.of("arr").orElseThrow();
        Entry obj = c.of("obj").orElseThrow();
        assertThat(arr.body()).isTrue();
        assertThat(arr.arrayBody()).isTrue();
        assertThat(obj.body()).isTrue();
        assertThat(obj.arrayBody()).isFalse();
        assertThat(arr.fields()).hasSize(8).isEqualTo(obj.fields());
    }

    @Test
    void SRS_INT_1_aRequestWithoutABodyHasNoShape() {
        Entry none = catalog().of("none").orElseThrow();
        assertThat(none.body()).isFalse();
        assertThat(none.arrayBody()).isFalse();
        assertThat(none.fields()).isEmpty();
        assertThat(none.method()).isEqualTo("GET");
    }

    @Test
    void SRS_INT_1_everyFieldKindIsReadFromTheSchema() {
        assertThat(catalog().of("obj").orElseThrow().fields())
                .containsExactly(
                        new Field("d", "date", false),
                        new Field("s", "string", false),
                        new Field("f", "string", false),
                        new Field("n", "number", false),
                        new Field("o", "scalar", true),
                        new Field("t", "string", false),
                        new Field("u", "null", false),
                        new Field("b", "boolean", true));
    }

    @Test
    void SRS_INT_1_aBodyThatIsNotAnObjectSchemaIsNoBody() {
        JsonNode doc =
                JSON.readTree(
                        "{\"paths\":{\"/x\":{\"post\":{\"operationId\":\"x\","
                            + "\"requestBody\":{\"content\":{\"application/json\":{\"schema\":true}}}}}}}");
        Entry x = HrmsContractCatalog.parse(doc).of("x").orElseThrow();
        assertThat(x.body()).isFalse();
        assertThat(body("{}").isObject()).isTrue();
    }
}
