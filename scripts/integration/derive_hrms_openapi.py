#!/usr/bin/env python3
"""Derive the HRMS API contract (OpenAPI 3.0.3) from FCI's Postman collection.

The contract of the HRMS APIs is the OpenAPI document backend/src/main/resources/integration/
hrms-openapi.json, not the Postman collection. FCI has no schema and shared a Postman collection
only; this script is the one-time (re-runnable) bridge that turns the collection's samples into an
OpenAPI document, which is then reviewed, versioned and owned like any other contract. The
collection is recorded only as provenance (info.x-derived-from).

What a Postman sample can prove, and therefore what the document states:
  * path, HTTP method, query parameter names, the JSON body's field names and the KIND of each value:
    a scalar (number or text - one sample cannot tell which), a date (yyyy-MM-dd), true/false, an
    object or an array; a field the sample shows as null has no type (anything is allowed);
  * the security of each call: the headers the sample sends. `api-key` -> security scheme ApiKeyAuth,
    `Authorization` -> BearerAuth; a call that sends neither (or only the api-key) says so with
    its own `security` list (an empty list = no authentication).
What it cannot prove, and the document therefore does NOT state: required fields, numeric ranges and
lengths, examples, and the response bodies (the collection has no saved response). The response is
described by a generic envelope marked x-source: observed (taken from the FCI mock server, to be
confirmed by HRMS). No secret and no sample value is copied.

Usage: python scripts/integration/derive_hrms_openapi.py "<path to FCI HRMS.postman_collection.json>"
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "backend/src/main/resources/integration/hrms-openapi.json"
DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
SCALAR = {"oneOf": [{"type": "number"}, {"type": "string"}], "description": "a number or text: one sample cannot tell which"}


def code_of(path: str) -> str:
    key = path.strip("/") or "welcome"
    return re.sub(r"[^a-z0-9]+", "_", key.lower()).strip("_")


def lenient(raw: str):
    """A Postman sample may not be valid JSON (`"request_id": ,`): read the empty values as null."""
    try:
        return json.loads(raw), False
    except json.JSONDecodeError:
        fixed = re.sub(r":\s*,", ": null,", raw)
        fixed = re.sub(r":\s*}", ": null}", fixed)
        fixed = re.sub(r",(\s*[}\]])", lambda m: m.group(1), fixed)
        return json.loads(fixed), True


def schema_of(value):
    """A JSON Schema fragment for what the sample shows; nothing for what it does not."""
    if value is None:
        return {"nullable": True, "description": "the sample is empty: the kind of value is not known"}
    if isinstance(value, bool):
        return {"type": "boolean"}
    if isinstance(value, str) and DATE.match(value):
        return {"type": "string", "format": "date"}
    if isinstance(value, (int, float, str)):
        # One sample cannot tell a number from text: the legacy HRMS columns are loosely typed (a bill number
        # is 9001 in one sample and "b98" in another, an id may arrive padded). It proves only a scalar value.
        return SCALAR
    if isinstance(value, list):
        return {"type": "array", "items": {}}
    return {"type": "object"}


def record_schema(records):
    props = {}
    for rec in records:
        for name, value in rec.items():
            schema = schema_of(value)
            prev = props.get(name)
            if prev is None or ("type" not in prev and "type" in schema):
                props[name] = schema
            elif value is None:
                props[name] = {**prev, "nullable": True}
    return {"type": "object", "properties": props, "additionalProperties": False}


def main(src: str) -> None:
    collection = json.load(open(src, encoding="utf8"))
    paths = {}

    def walk(items):
        for item in items:
            if "item" in item:
                walk(item["item"])
                continue
            req = item["request"]
            url = req["url"]
            raw = url["raw"] if isinstance(url, dict) else url
            path = "/" + raw.replace("{{site_url}}", "").split("?")[0].lstrip("/")
            code = code_of(path)
            headers = {h["key"] for h in req.get("header", [])}
            op = {
                "operationId": code,
                "summary": item["name"],
                "tags": ["HRMS"],
            }
            query = [q["key"] for q in (url.get("query") or [])] if isinstance(url, dict) else []
            if query:
                op["parameters"] = [
                    {"name": q, "in": "query", "schema": {"type": "string"}} for q in query
                ]
            raw_body = (req.get("body") or {}).get("raw")
            if raw_body and raw_body.strip():
                parsed, repaired = lenient(raw_body)
                if isinstance(parsed, list):
                    schema = {
                        "type": "array",
                        "items": record_schema([r for r in parsed if isinstance(r, dict)]),
                    }
                else:
                    schema = record_schema([parsed])
                op["requestBody"] = {"content": {"application/json": {"schema": schema}}}
                if repaired:
                    op["x-note"] = "the Postman sample is not valid JSON; its empty values were read as null"
            security = []
            if "api-key" in headers:
                security.append("ApiKeyAuth")
            if "Authorization" in headers:
                security.append("BearerAuth")
            op["security"] = [{name: [] for name in security}] if security else []
            op["responses"] = {
                "2XX": {"$ref": "#/components/responses/Success"},
                "4XX": {"$ref": "#/components/responses/Refused"},
                "5XX": {"$ref": "#/components/responses/Failure"},
            }
            paths.setdefault(path, {})[req["method"].lower()] = op

    walk(collection["item"])
    envelope = {
        "type": "object",
        "x-source": "observed on the FCI mock server; the collection has no saved response",
        "properties": {
            "status": {"type": "string", "description": "success or error"},
            "status_code": {"type": "integer"},
            "error_code": {"type": "string"},
            "message": {"type": "string"},
            "data": {"nullable": True},
            "errors": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {"field": {"type": "string"}, "message": {"type": "string"}},
                },
            },
            "timestamp": {"type": "string"},
        },
    }
    doc = {
        "openapi": "3.0.3",
        "info": {
            "title": "FCI HRMS API (as consumed by FCI Payroll)",
            "version": "1.0.0",
            "description": "Request contract of the HRMS APIs. Derived from FCI's Postman collection; owned and versioned here. Responses are the generic envelope (observed, to be confirmed by HRMS).",
            "x-derived-from": {
                "file": Path(src).name,
                "collectionId": collection.get("info", {}).get("_postman_id"),
                "tool": "scripts/integration/derive_hrms_openapi.py",
            },
        },
        "servers": [{"url": "{baseUrl}", "variables": {"baseUrl": {"default": "http://localhost:8080"}}}],
        "paths": dict(sorted(paths.items())),
        "components": {
            "securitySchemes": {
                "ApiKeyAuth": {"type": "apiKey", "in": "header", "name": "api-key"},
                "BearerAuth": {"type": "http", "scheme": "bearer"},
            },
            "responses": {
                "Success": {"description": "2xx", "content": {"application/json": {"schema": envelope}}},
                "Refused": {"description": "4xx: refused (error_code, message, errors[])", "content": {"application/json": {"schema": envelope}}},
                "Failure": {"description": "5xx: the call may be repeated", "content": {"application/json": {"schema": envelope}}},
            },
        },
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(doc, indent=2, ensure_ascii=True) + "\n", encoding="utf8", newline="\n")
    print(f"{sum(len(v) for v in paths.values())} operations -> {OUT}")


if __name__ == "__main__":
    main(sys.argv[1])
