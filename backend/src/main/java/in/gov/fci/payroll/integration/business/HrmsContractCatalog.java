package in.gov.fci.payroll.integration.business;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The contract of the HRMS APIs (SRS-INT-1, SRS-INT-12; docs/integration/HRMS_API_CONTRACT.md): the
 * OpenAPI 3 document {@code integration/hrms-openapi.json}. That document is the contract, owned
 * and versioned here; the Postman collection FCI supplied is only where it was first derived from
 * (see {@code scripts/integration/derive_hrms_openapi.py}). A Postman sample proves the path,
 * method, query parameter names, body field names, the kind of each value and the authentication
 * headers it sends, so that is all the document states: required fields, ranges and lengths are not
 * in a sample and are therefore never enforced here (HRMS decides and answers 400). The pre-flight
 * check refuses what the document proves wrong - a parameter or field it never lists, a body where
 * it states none (or none where it states one), an array where an object is stated, a number where
 * it states text - so Payroll does not send HRMS a message HRMS never described. Authentication
 * follows the document's per-operation {@code security}: the api-key and the bearer token are sent
 * only where it says so.
 */
public final class HrmsContractCatalog {

    /** The resource the derivation script writes. */
    public static final String RESOURCE = "integration/hrms-openapi.json";

    /** One body field: its name and the kind of value the sample carried. */
    public record Field(String name, String kind, boolean nullable) {}

    /**
     * One request of the collection.
     *
     * @param apiKey the operation requires the {@code ApiKeyAuth} scheme (header {@code api-key})
     * @param bearer the operation requires the {@code BearerAuth} scheme
     * @param query query parameter names the document lists
     * @param body whether the sample has a body
     * @param arrayBody the body is a JSON array of records
     * @param fields the body fields, in the order of the sample
     */
    public record Entry(
            String code,
            String name,
            String method,
            String path,
            boolean apiKey,
            boolean bearer,
            List<String> query,
            boolean body,
            boolean arrayBody,
            List<Field> fields) {

        /** Whether the call carries {@code Authorization: Bearer}. */
        public boolean needsBearer() {
            return bearer;
        }

        /** Whether the call carries the {@code api-key} header. */
        public boolean needsApiKey() {
            return apiKey;
        }

        /**
         * What is wrong with a call against the contract; empty when it is consistent with it.
         *
         * @param params the query parameters Payroll would send, or null
         * @param payload the JSON body Payroll would send, or null
         */
        public List<String> defects(Map<String, String> params, JsonNode payload) {
            List<String> defects = new ArrayList<>();
            if (params != null) {
                for (String name : params.keySet()) {
                    if (!query.contains(name)) {
                        defects.add("query parameter '" + name + "' is not in the contract");
                    }
                }
            }
            boolean sent = payload != null && !payload.isNull() && !payload.isMissingNode();
            if (!sent) {
                if (body) {
                    defects.add("the contract carries a body and none is given");
                }
                return defects;
            }
            if (!body) {
                defects.add("the contract carries no body for this API");
                return defects;
            }
            if (arrayBody) {
                if (!payload.isArray()) {
                    defects.add("the body must be an array of records");
                    return defects;
                }
                int row = 0;
                for (JsonNode record : payload) {
                    row++;
                    recordDefects(record, "record " + row + ": ", defects);
                }
            } else if (payload.isArray()) {
                defects.add("the body must be a single object, not an array");
            } else {
                recordDefects(payload, "", defects);
            }
            return defects;
        }

        private void recordDefects(JsonNode record, String where, List<String> defects) {
            if (!record.isObject()) {
                defects.add(where + "must be an object");
                return;
            }
            Map<String, Field> known = new LinkedHashMap<>();
            fields.forEach(f -> known.put(f.name(), f));
            for (String name : record.propertyNames()) {
                Field field = known.get(name);
                if (field == null) {
                    defects.add(where + "field '" + name + "' is not in the contract");
                    continue;
                }
                String problem = kindProblem(field, record.get(name));
                if (problem != null) {
                    defects.add(where + "field '" + name + "' " + problem);
                }
            }
        }

        private static String kindProblem(Field field, JsonNode value) {
            if (value == null || value.isNull()) {
                return null; // an empty value is HRMS's to judge
            }
            return switch (field.kind()) {
                case "scalar" ->
                        value.isNumber() || value.isString()
                                ? null
                                : "must be a number or text, not " + typeOf(value);
                case "number" -> value.isNumber() ? null : "must be a number, not " + typeOf(value);
                case "string" -> value.isString() ? null : "must be text, not " + typeOf(value);
                case "date" -> dateProblem(value);
                case "boolean" -> value.isBoolean() ? null : "must be true or false";
                case "array" -> value.isArray() ? null : "must be an array";
                case "object" -> value.isObject() ? null : "must be an object";
                default -> null; // the sample was null: the kind is not known
            };
        }

        private static String dateProblem(JsonNode value) {
            if (!value.isString()) {
                return "must be a date (yyyy-MM-dd), not " + typeOf(value);
            }
            try {
                LocalDate.parse(value.asString());
                return null;
            } catch (DateTimeParseException ex) {
                return "must be a date (yyyy-MM-dd)";
            }
        }

        private static String typeOf(JsonNode value) {
            if (value.isNumber()) {
                return "a number";
            }
            if (value.isString()) {
                return "text";
            }
            if (value.isBoolean()) {
                return "true or false";
            }
            return value.isArray() ? "an array" : "an object";
        }
    }

    private final Map<String, Entry> byCode;

    private HrmsContractCatalog(Map<String, Entry> byCode) {
        this.byCode = byCode;
    }

    /** The catalogue of the generated file on the class path. */
    public static HrmsContractCatalog load() {
        try (InputStream in =
                HrmsContractCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the class path");
            }
            return parse(JsonMapper.builder().build().readTree(in));
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read " + RESOURCE, ex);
        }
    }

    /** The catalogue of an OpenAPI document (tests and tools build their own). */
    public static HrmsContractCatalog parse(JsonNode document) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (String path : document.get("paths").propertyNames()) {
            JsonNode item = document.get("paths").get(path);
            for (String method : item.propertyNames()) {
                JsonNode op = item.get(method);
                entries.put(op.get("operationId").asString(), entry(path, method, op));
            }
        }
        return new HrmsContractCatalog(Map.copyOf(entries));
    }

    private static Entry entry(String path, String method, JsonNode op) {
        List<String> query = new ArrayList<>();
        JsonNode parameters = op.get("parameters");
        if (parameters != null) {
            for (JsonNode p : parameters) {
                if ("query".equals(p.get("in").asString())) {
                    query.add(p.get("name").asString());
                }
            }
        }
        boolean apiKey = false;
        boolean bearer = false;
        JsonNode security = op.get("security");
        if (security != null) {
            for (JsonNode requirement : security) {
                apiKey |= requirement.has("ApiKeyAuth");
                bearer |= requirement.has("BearerAuth");
            }
        }
        JsonNode schema =
                op.path("requestBody").path("content").path("application/json").path("schema");
        boolean hasBody = schema.isObject();
        boolean array = hasBody && "array".equals(schema.path("type").asString());
        JsonNode record = array ? schema.path("items") : schema;
        List<Field> fields = new ArrayList<>();
        if (hasBody) {
            JsonNode properties = record.path("properties");
            for (String name : properties.propertyNames()) {
                fields.add(field(name, properties.get(name)));
            }
        }
        return new Entry(
                op.get("operationId").asString(),
                op.path("summary").asString(""),
                method.toUpperCase(java.util.Locale.ROOT),
                path,
                apiKey,
                bearer,
                List.copyOf(query),
                hasBody,
                array,
                List.copyOf(fields));
    }

    private static Field field(String name, JsonNode schema) {
        String type = schema.path("type").asString("");
        if (type.isEmpty() && schema.has("oneOf")) {
            // a number or text: one sample cannot tell which
            return new Field(name, "scalar", schema.path("nullable").asBoolean(false));
        }
        String kind =
                switch (type) {
                    case "number", "integer" -> "number";
                    case "boolean", "array", "object" -> type;
                    case "string" ->
                            "date".equals(schema.path("format").asString()) ? "date" : "string";
                    default -> "null"; // the sample was empty: the kind of value is not known
                };
        return new Field(name, kind, schema.path("nullable").asBoolean(false));
    }

    /** The contract of the API with this setting code. */
    public Optional<Entry> of(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /** Every request of the collection. */
    public Collection<Entry> all() {
        return byCode.values();
    }

    /** The codes the collection lists. */
    public Set<String> codes() {
        return byCode.keySet();
    }
}
