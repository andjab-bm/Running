//! OpenAPI description of the REST API.
//!
//! It is written here, next to the router, and committed as
//! `docs/api/openapi.json`. A test fails when the committed file differs from
//! this description or when a route listed in [`ROUTES`] is missing from either.
use serde_json::{Map, Value, json};

/// Every route of the API: method, path, summary, whether it changes state.
pub const ROUTES: &[(&str, &str, &str)] = &[
    ("get", "/health", "Service and data store status"),
    ("get", "/profiles", "List household profiles"),
    ("post", "/profiles", "Create a profile"),
    ("get", "/profiles/{profile_id}", "Read a profile and its heart-rate zones"),
    ("put", "/profiles/{profile_id}", "Change profile settings (only the given fields)"),
    ("patch", "/profiles/{profile_id}", "Change profile settings (only the given fields)"),
    ("delete", "/profiles/{profile_id}", "Delete a profile and all its activities"),
    (
        "get",
        "/profiles/{profile_id}/activities",
        "List a profile's activities, most recent first",
    ),
    ("get", "/profiles/{profile_id}/tags", "Tags offered to a profile"),
    ("post", "/profiles/{profile_id}/imports", "Upload .gpx / .fit files into a profile"),
    ("get", "/activities/{id}", "Read an activity with splits, laps and zones"),
    ("patch", "/activities/{id}", "Edit the journal of an activity"),
    ("delete", "/activities/{id}", "Delete an activity and its original file"),
    ("get", "/activities/{id}/streams", "Index-aligned sample series"),
    ("get", "/activities/{id}/export.gpx", "Download the activity as GPX 1.1"),
    ("get", "/activities/{id}/original", "Download the unmodified uploaded file"),
    ("post", "/activities/{id}/copy", "Copy an activity to another profile"),
    ("post", "/activities/{id}/move", "Move an activity to another profile"),
];

fn r#ref(name: &str) -> Value {
    json!({ "$ref": format!("#/components/schemas/{name}") })
}

fn json_body(schema: Value) -> Value {
    json!({ "required": true, "content": { "application/json": { "schema": schema } } })
}

fn ok(description: &str, schema: Option<Value>) -> Value {
    match schema {
        Some(s) => json!({ "description": description, "content": { "application/json": { "schema": s } } }),
        None => json!({ "description": description }),
    }
}

fn nullable(t: &str) -> Value {
    json!({ "type": [t, "null"] })
}

fn schemas() -> Value {
    let segment = json!({
        "type": "object",
        "description": "A split, a lap, or a whole activity. SI units.",
        "properties": {
            "start_t": { "type": "number", "description": "Seconds from the start of the activity" },
            "distance": { "type": "number", "description": "Metres" },
            "moving_time": { "type": "number", "description": "Seconds" },
            "elapsed_time": { "type": "number", "description": "Seconds" },
            "speed": { "type": "number", "description": "Metres per second over moving time" },
            "gap_speed": nullable("number"),
            "gain": nullable("number"),
            "loss": nullable("number"),
            "avg_hr": nullable("number"),
            "avg_cad": { "type": ["number", "null"], "description": "Steps per minute, both legs" }
        }
    });
    let summary = json!({
        "type": "object",
        "properties": {
            "id": { "type": "integer" },
            "profile_id": { "type": "integer" },
            "title": { "type": "string" },
            "start_time": { "type": "number", "description": "Unix seconds, UTC" },
            "sport": nullable("string"),
            "distance": { "type": "number" },
            "elapsed_time": { "type": "number" },
            "moving_time": { "type": "number" },
            "speed": { "type": "number" },
            "gap_speed": nullable("number"),
            "gain": nullable("number"),
            "loss": nullable("number"),
            "avg_hr": nullable("number"),
            "max_hr": nullable("number"),
            "avg_cad": nullable("number"),
            "effort": { "type": ["number", "null"], "description": "Relative effort; null without heart rate" },
            "rpe": { "type": ["integer", "null"], "minimum": 1, "maximum": 10 },
            "copied_from": { "type": ["integer", "null"], "description": "Activity this one was copied from" },
            "tags": { "type": "array", "items": { "type": "string" } },
            "preview": { "type": "array", "description": "Simplified trace as [lat, lon] pairs", "items": { "type": "array", "items": { "type": "number" } } }
        }
    });
    let series = |t: &str| json!({ "type": ["array", "null"], "items": { "type": [t, "null"] } });
    json!({
        "Error": {
            "type": "object",
            "required": ["error", "message"],
            "properties": { "error": { "type": "string", "description": "Stable code" }, "message": { "type": "string" } }
        },
        "Health": {
            "type": "object",
            "properties": { "status": { "type": "string", "enum": ["ok", "degraded"] }, "version": { "type": "string" }, "database": { "type": "string" } }
        },
        "Zones": {
            "type": "object",
            "properties": {
                "bounds": { "type": "array", "minItems": 6, "maxItems": 6, "items": { "type": "number" }, "description": "Lower bound of zones 1–5, then the maximum (bpm)" },
                "custom": { "type": "boolean" },
                "uses_default_hr_max": { "type": "boolean" },
                "default_hr_max": { "type": "number" }
            }
        },
        "Profile": {
            "type": "object",
            "properties": {
                "id": { "type": "integer" },
                "name": { "type": "string" },
                "color": { "type": "string", "examples": ["#B9AFFF"] },
                "hr_max": nullable("integer"),
                "hr_rest": nullable("integer"),
                "units": { "type": "string", "enum": ["metric", "imperial"] },
                "timezone": nullable("string"),
                "activity_count": { "type": "integer" },
                "zones": r#ref("Zones")
            }
        },
        "NewProfile": {
            "type": "object",
            "required": ["name"],
            "properties": { "name": { "type": "string", "maxLength": 24 }, "color": { "type": "string" } }
        },
        "ProfileChanges": {
            "type": "object",
            "description": "Fields left out are kept; null clears hr_max, hr_rest, zone_bounds (back to defaults) or timezone.",
            "properties": {
                "name": { "type": "string", "maxLength": 24 },
                "color": { "type": "string" },
                "hr_max": { "type": ["integer", "null"], "minimum": 100, "maximum": 230 },
                "hr_rest": nullable("integer"),
                "zone_bounds": { "type": ["array", "null"], "minItems": 6, "maxItems": 6, "items": { "type": "number" } },
                "units": { "type": "string", "enum": ["metric", "imperial"] },
                "timezone": nullable("string")
            }
        },
        "Segment": segment,
        "ActivitySummary": summary,
        "Activity": {
            "allOf": [r#ref("ActivitySummary"), {
                "type": "object",
                "properties": {
                    "description": { "type": "string" },
                    "source_format": { "type": "string", "enum": ["gpx", "fit"] },
                    "has_original": { "type": "boolean" },
                    "metrics_version": { "type": "integer" },
                    "zones": {
                        "type": ["object", "null"],
                        "properties": {
                            "seconds": { "type": "array", "minItems": 5, "maxItems": 5, "items": { "type": "number" } },
                            "bounds": { "type": "array", "items": { "type": "number" } },
                            "uses_default_hr_max": { "type": "boolean" }
                        }
                    },
                    "splits": { "type": "array", "items": r#ref("Segment") },
                    "laps": { "type": "array", "items": r#ref("Segment") }
                }
            }]
        },
        "ActivityPage": {
            "type": "object",
            "properties": { "items": { "type": "array", "items": r#ref("ActivitySummary") }, "next_cursor": nullable("string") }
        },
        "Journal": {
            "type": "object",
            "description": "Fields left out are kept; rpe null clears it; tags replaces the whole set.",
            "properties": {
                "title": { "type": "string", "maxLength": 200 },
                "description": { "type": "string" },
                "rpe": { "type": ["integer", "null"], "minimum": 1, "maximum": 10 },
                "tags": { "type": "array", "maxItems": 20, "items": { "type": "string", "maxLength": 40 } }
            }
        },
        "Streams": {
            "type": "object",
            "description": "All arrays have `count` entries and share one index.",
            "properties": {
                "resolution": { "type": "string", "enum": ["low", "full"] },
                "count": { "type": "integer" },
                "t": { "type": "array", "items": { "type": "number" }, "description": "Seconds since start" },
                "dist": { "type": "array", "items": { "type": "number" }, "description": "Metres" },
                "lat": series("number"),
                "lon": series("number"),
                "speed": { "type": "array", "items": { "type": "number" }, "description": "Metres per second" },
                "moving": { "type": "array", "items": { "type": "boolean" } },
                "ele": series("number"),
                "hr": series("number"),
                "cad": series("number")
            }
        },
        "ImportResult": {
            "type": "object",
            "properties": {
                "results": { "type": "array", "items": {
                    "type": "object",
                    "required": ["file", "status"],
                    "properties": {
                        "file": { "type": "string" },
                        "status": { "type": "string", "enum": ["imported", "duplicate", "failed"] },
                        "activity_id": { "type": "integer", "description": "Created activity, or the existing one for a duplicate" },
                        "reason": { "type": "string" }
                    }
                } }
            }
        },
        "Transfer": {
            "type": "object",
            "required": ["profile_id"],
            "properties": { "profile_id": { "type": "integer" }, "keep_hr": { "type": "boolean", "default": false, "description": "Copies only" } }
        }
    })
}

fn operation(method: &str, path: &str, summary: &str) -> Value {
    let mut op = Map::new();
    op.insert("summary".into(), summary.into());
    let mut params: Vec<Value> = Vec::new();
    for name in ["profile_id", "id"] {
        if path.contains(&format!("{{{name}}}")) {
            params.push(json!({ "name": name, "in": "path", "required": true, "schema": { "type": "integer" } }));
        }
    }
    let writes = !matches!(method, "get");
    if writes {
        params.push(
            json!({ "name": "X-Requested-With", "in": "header", "required": true, "schema": { "type": "string" },
            "description": "Any value. Required on every state-changing request; cross-site requests are refused." }),
        );
    }
    let mut responses = Map::new();
    let key = (method, path);
    match key {
        ("get", "/health") => {
            responses.insert("200".into(), ok("Healthy", Some(r#ref("Health"))));
            responses.insert("503".into(), ok("Data store unreachable", Some(r#ref("Health"))));
        }
        ("get", "/profiles") => {
            responses.insert(
                "200".into(),
                ok(
                    "Profiles",
                    Some(json!({ "type": "object", "properties": { "profiles": { "type": "array", "items": r#ref("Profile") } } })),
                ),
            );
        }
        ("post", "/profiles") => {
            op.insert("requestBody".into(), json_body(r#ref("NewProfile")));
            responses.insert("201".into(), ok("Created", Some(r#ref("Profile"))));
            responses.insert("409".into(), ok("Name already used", Some(r#ref("Error"))));
        }
        ("get", "/profiles/{profile_id}") => {
            responses.insert("200".into(), ok("Profile", Some(r#ref("Profile"))));
        }
        ("put" | "patch", "/profiles/{profile_id}") => {
            op.insert("requestBody".into(), json_body(r#ref("ProfileChanges")));
            responses.insert(
                "200".into(),
                ok(
                    "Updated; zone times, effort and splits of the profile's activities are recomputed when they depend on the change",
                    Some(r#ref("Profile")),
                ),
            );
            responses.insert("409".into(), ok("Name already used", Some(r#ref("Error"))));
        }
        ("delete", "/profiles/{profile_id}") => {
            responses.insert("204".into(), ok("Deleted", None));
            responses.insert("409".into(), ok("The last profile cannot be deleted", Some(r#ref("Error"))));
        }
        ("get", "/profiles/{profile_id}/activities") => {
            params.extend([
                json!({ "name": "limit", "in": "query", "schema": { "type": "integer", "default": 20, "minimum": 1, "maximum": 100 } }),
                json!({ "name": "cursor", "in": "query", "schema": { "type": "string" }, "description": "next_cursor of the previous page" }),
                json!({ "name": "tag", "in": "query", "schema": { "type": "string" } }),
                json!({ "name": "from", "in": "query", "schema": { "type": "number" }, "description": "Start time ≥ this Unix time" }),
                json!({ "name": "to", "in": "query", "schema": { "type": "number" }, "description": "Start time < this Unix time" }),
            ]);
            responses.insert("200".into(), ok("One page", Some(r#ref("ActivityPage"))));
        }
        ("get", "/profiles/{profile_id}/tags") => {
            responses.insert(
                "200".into(),
                ok(
                    "Predefined tags, then the profile's own",
                    Some(json!({ "type": "object", "properties": { "tags": { "type": "array", "items": { "type": "string" } } } })),
                ),
            );
        }
        ("post", "/profiles/{profile_id}/imports") => {
            params.push(json!({ "name": "tz_offset_minutes", "in": "query", "schema": { "type": "integer" },
                "description": "Minutes east of UTC at the uploader; only used to name untitled runs by time of day" }));
            op.insert("requestBody".into(), json!({ "required": true, "content": { "multipart/form-data": { "schema": {
                "type": "object", "properties": { "files": { "type": "array", "items": { "type": "string", "format": "binary" } } } } } } }));
            responses.insert(
                "200".into(),
                ok(
                    "One outcome per activity found in each file; a failed file does not fail the request",
                    Some(r#ref("ImportResult")),
                ),
            );
        }
        ("get", "/activities/{id}") => {
            responses.insert("200".into(), ok("Activity", Some(r#ref("Activity"))));
        }
        ("patch", "/activities/{id}") => {
            op.insert("requestBody".into(), json_body(r#ref("Journal")));
            responses.insert("200".into(), ok("Updated", Some(r#ref("Activity"))));
        }
        ("delete", "/activities/{id}") => {
            responses.insert("204".into(), ok("Deleted", None));
        }
        ("get", "/activities/{id}/streams") => {
            params.push(
                json!({ "name": "resolution", "in": "query", "schema": { "type": "string", "enum": ["low", "full"], "default": "low" },
                "description": "low returns at most 2000 points" }),
            );
            responses.insert("200".into(), ok("Series", Some(r#ref("Streams"))));
        }
        ("get", "/activities/{id}/export.gpx") => {
            responses.insert(
                "200".into(),
                json!({ "description": "GPX 1.1 document", "content": { "application/gpx+xml": {} } }),
            );
        }
        ("get", "/activities/{id}/original") => {
            responses.insert("200".into(), json!({ "description": "The uploaded file, byte for byte", "content": { "application/octet-stream": {}, "application/gpx+xml": {} } }));
        }
        ("post", "/activities/{id}/copy") => {
            op.insert("requestBody".into(), json_body(r#ref("Transfer")));
            responses.insert(
                "201".into(),
                ok(
                    "Copied; heart rate is omitted unless keep_hr",
                    Some(json!({ "type": "object", "properties": { "activity_id": { "type": "integer" } } })),
                ),
            );
            responses.insert(
                "409".into(),
                ok(
                    "Already present in that profile; activity_id names the existing one",
                    Some(r#ref("Error")),
                ),
            );
        }
        ("post", "/activities/{id}/move") => {
            op.insert("requestBody".into(), json_body(r#ref("Transfer")));
            responses.insert("200".into(), ok("Moved; zone figures recomputed for the target profile", Some(json!({ "type": "object", "properties": { "activity_id": { "type": "integer" }, "profile_id": { "type": "integer" } } }))));
        }
        _ => {
            responses.insert("200".into(), ok("OK", None));
        }
    }
    if !params.iter().any(|p| p["in"] == "path") {
        // nothing to add
    } else {
        responses.insert("404".into(), ok("No such profile or activity", Some(r#ref("Error"))));
    }
    if writes {
        responses.insert(
            "403".into(),
            ok("Cross-site request or missing X-Requested-With header", Some(r#ref("Error"))),
        );
        if op.contains_key("requestBody") && path != "/profiles/{profile_id}/imports" {
            responses.insert("422".into(), ok("Validation error", Some(r#ref("Error"))));
        }
    }
    if !params.is_empty() {
        op.insert("parameters".into(), params.into());
    }
    op.insert("responses".into(), responses.into());
    op.into()
}

/// The complete OpenAPI 3.1 document.
pub fn document() -> Value {
    let mut paths = Map::new();
    for (method, path, summary) in ROUTES {
        let entry = paths.entry(path.to_string()).or_insert_with(|| json!({}));
        entry[*method] = operation(method, path, summary);
    }
    json!({
        "openapi": "3.1.0",
        "info": {
            "title": "Running API",
            "version": env!("CARGO_PKG_VERSION"),
            "description": "Self-hosted running tracker. No authentication: the service must only be reachable from a trusted network. Quantities are in SI units (metres, seconds, metres per second) and times are Unix seconds in UTC; clients convert for display."
        },
        "servers": [{ "url": "/api/v1" }],
        "paths": paths,
        "components": { "schemas": schemas() }
    })
}

/// The document as committed: pretty-printed with a trailing newline.
pub fn document_text() -> String {
    let mut s = serde_json::to_string_pretty(&document()).unwrap_or_default();
    s.push('\n');
    s
}
