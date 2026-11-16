//! OpenAPI description of the REST API.
//!
//! It is written here, next to the router, and committed as
//! `docs/api/openapi.json`. A test fails when the committed file differs from
//! this description or when a route listed in [`ROUTES`] is missing from either.
use serde_json::{Map, Value, json};

/// Every route of the API: method, path, summary, whether it changes state.
pub const ROUTES: &[(&str, &str, &str)] = &[
    ("get", "/health", "Service and data store status"),
    ("get", "/config", "Settings the web app needs: map styles and upload limit"),
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
    ("get", "/activities", "List the whole household's activities, most recent first"),
    (
        "get",
        "/profiles/{profile_id}/stats/calendar",
        "Runs per day of a year, with the year's totals",
    ),
    (
        "get",
        "/profiles/{profile_id}/stats/volume",
        "Totals per week or per month, empty periods included",
    ),
    (
        "get",
        "/profiles/{profile_id}/stats/progress",
        "Average speed, longest run and average heart rate per month",
    ),
    (
        "get",
        "/profiles/{profile_id}/load",
        "Training load, its status and the advised recovery",
    ),
    (
        "get",
        "/profiles/{profile_id}/records",
        "Best times over standard distances, with their history",
    ),
    (
        "get",
        "/profiles/{profile_id}/predictions",
        "Estimated potential race times from recent efforts",
    ),
    ("get", "/profiles/{profile_id}/tags", "Tags offered to a profile"),
    ("post", "/profiles/{profile_id}/imports", "Upload .gpx / .fit files into a profile"),
    (
        "get",
        "/profiles/{profile_id}/derive-status",
        "Background computation left for a profile (records, load, segments)",
    ),
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

fn tz_param() -> Value {
    json!({ "name": "tz_offset_minutes", "in": "query", "schema": { "type": "integer", "default": 0 },
        "description": "Minutes east of UTC at the caller; days, weeks and months are cut in that local time" })
}

fn until_param() -> Value {
    json!({ "name": "until", "in": "query", "schema": { "type": "number" },
        "description": "The instant taken as now (Unix seconds); defaults to the server clock" })
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
            "load": { "type": ["number", "null"], "description": "Training load: the effort, or an estimate from duration and perceived effort; null until processed" },
            "load_estimated": { "type": "boolean", "description": "The load is not based on heart rate" },
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
                    "exclude_from_records": { "type": "boolean" },
                    "best_efforts": { "type": "array", "description": "Empty until the background computation has processed the activity",
                        "items": { "type": "object", "properties": {
                            "distance": { "type": "number" }, "moving_time": { "type": "number" }, "start_offset": { "type": "number" },
                            "rank": { "type": ["integer", "null"], "description": "1 to 3 when it is currently among the profile's three best times" } } } },
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
                "tags": { "type": "array", "maxItems": 20, "items": { "type": "string", "maxLength": 40 } },
                "exclude_from_records": { "type": "boolean", "description": "Leave the activity out of records and predictions" }
            }
        },
        "Effort": {
            "type": "object",
            "description": "The fastest stretch of a standard distance inside one run, timed on moving time (pauses excluded).",
            "properties": {
                "moving_time": { "type": "number" },
                "speed": { "type": "number" },
                "activity_id": { "type": "integer" },
                "title": { "type": "string" },
                "start_time": { "type": "number", "description": "Start of the activity" },
                "start_offset": { "type": "number", "description": "Metres from the start of the run to the start of the stretch" }
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
        ("get", "/config") => {
            responses.insert(
                "200".into(),
                ok(
                    "Client configuration",
                    Some(json!({ "type": "object", "properties": {
                        "version": { "type": "string" },
                        "map_style_url": { "type": "string", "description": "MapLibre style for the light theme; empty disables the basemap" },
                        "map_style_url_dark": { "type": "string" },
                        "max_upload_bytes": { "type": "integer" }
                    } })),
                ),
            );
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
        ("get", "/activities") => {
            params.extend([
                json!({ "name": "profile_id", "in": "query", "schema": { "type": "integer" },
                    "description": "Only this profile. Without it, a run copied between profiles is listed once and names its copies in shared_with" }),
                json!({ "name": "limit", "in": "query", "schema": { "type": "integer", "default": 20, "minimum": 1, "maximum": 100 } }),
                json!({ "name": "cursor", "in": "query", "schema": { "type": "string" }, "description": "next_cursor of the previous page" }),
                json!({ "name": "tag", "in": "query", "schema": { "type": "string" }, "description": "Tag name, matched in every profile" }),
                json!({ "name": "from", "in": "query", "schema": { "type": "number" }, "description": "Start time ≥ this Unix time" }),
                json!({ "name": "to", "in": "query", "schema": { "type": "number" }, "description": "Start time < this Unix time" }),
            ]);
            responses.insert(
                "200".into(),
                ok(
                    "One page; each item carries profile_id and, without a profile filter, shared_with: [{profile_id, activity_id}]",
                    Some(r#ref("ActivityPage")),
                ),
            );
            responses.insert("404".into(), ok("No such profile", Some(r#ref("Error"))));
        }
        ("get", "/profiles/{profile_id}/stats/calendar") => {
            params.extend([
                json!({ "name": "year", "in": "query", "schema": { "type": "integer" }, "description": "Defaults to the current year" }),
                tz_param(),
            ]);
            responses.insert(
                "200".into(),
                ok(
                    "Only days with a run are listed",
                    Some(json!({ "type": "object", "properties": {
                        "year": { "type": "integer" },
                        "days": { "type": "array", "items": { "type": "object", "properties": {
                            "date": { "type": "string", "format": "date" },
                            "distance": { "type": "number" }, "moving_time": { "type": "number" }, "load": { "type": "number" },
                            "activities": { "type": "array", "items": { "type": "object", "properties": {
                                "id": { "type": "integer" }, "title": { "type": "string" }, "distance": { "type": "number" } } } }
                        } } },
                        "totals": { "type": "object", "properties": {
                            "runs": { "type": "integer" }, "active_days": { "type": "integer" },
                            "distance": { "type": "number" }, "moving_time": { "type": "number" }, "gain": { "type": "number" } } }
                    } })),
                ),
            );
        }
        ("get", "/profiles/{profile_id}/stats/volume") => {
            params.extend([
                json!({ "name": "unit", "in": "query", "schema": { "type": "string", "enum": ["week", "month"], "default": "week" },
                    "description": "Weeks start on Monday" }),
                json!({ "name": "count", "in": "query", "schema": { "type": "integer", "default": 12, "minimum": 1, "maximum": 60 },
                    "description": "Number of periods, ending with the one in progress" }),
                until_param(),
                tz_param(),
            ]);
            responses.insert(
                "200".into(),
                ok(
                    "Oldest first",
                    Some(json!({ "type": "object", "properties": {
                        "unit": { "type": "string" },
                        "periods": { "type": "array", "items": { "type": "object", "properties": {
                            "start": { "type": "string", "format": "date" }, "runs": { "type": "integer" },
                            "distance": { "type": "number" }, "moving_time": { "type": "number" }, "gain": { "type": "number" },
                            "current": { "type": "boolean", "description": "The period in progress" } } } }
                    } })),
                ),
            );
            responses.insert("400".into(), ok("Unknown unit", Some(r#ref("Error"))));
        }
        ("get", "/profiles/{profile_id}/stats/progress") => {
            params.extend([
                json!({ "name": "months", "in": "query", "schema": { "type": "integer", "default": 12, "minimum": 1, "maximum": 60 } }),
                json!({ "name": "tag", "in": "query", "schema": { "type": "string" }, "description": "Only runs carrying this tag" }),
                until_param(),
                tz_param(),
            ]);
            responses.insert(
                "200".into(),
                ok(
                    "Oldest first; a measure without data in a month is null",
                    Some(json!({ "type": "object", "properties": {
                        "months": { "type": "array", "items": { "type": "object", "properties": {
                            "month": { "type": "string", "description": "YYYY-MM" }, "runs": { "type": "integer" },
                            "avg_speed": nullable("number"), "longest": nullable("number"), "avg_hr": nullable("number") } } }
                    } })),
                ),
            );
        }
        ("get", "/profiles/{profile_id}/load") => {
            params.extend([
                json!({ "name": "days", "in": "query", "schema": { "type": "integer", "default": 84, "minimum": 7, "maximum": 366 },
                    "description": "Days returned, ending today; averages always use the whole history" }),
                until_param(),
                tz_param(),
            ]);
            responses.insert(
                "200".into(),
                ok(
                    "Estimates from recorded runs only",
                    Some(json!({ "type": "object", "properties": {
                        "days": { "type": "array", "items": { "type": "object", "properties": {
                            "date": { "type": "string", "format": "date" },
                            "load": { "type": "number", "description": "0 on a rest day" },
                            "estimated": { "type": "boolean", "description": "A run of that day has no heart rate" },
                            "short": { "type": "number", "description": "Daily average weighted over about 7 days" },
                            "long": { "type": "number", "description": "Daily average weighted over about 28 days" } } } },
                        "weeks": { "type": "array", "items": { "type": "object", "properties": {
                            "start": { "type": "string", "format": "date" }, "load": { "type": "number" }, "current": { "type": "boolean" } } } },
                        "status": { "type": "object", "properties": {
                            "code": { "type": "string", "enum": ["not_enough_data", "resuming", "light", "steady", "building", "too_much"] },
                            "ratio": nullable("number"), "short": nullable("number"), "long": nullable("number"),
                            "history_days": { "type": "integer", "description": "Days since the first run; the status needs 14" } } },
                        "recovery": { "type": ["object", "null"], "description": "After the latest run", "properties": {
                            "activity_id": { "type": "integer" }, "load": { "type": "number" }, "estimated": { "type": "boolean" },
                            "hours_advised": { "type": "number", "description": "Between 6 and 96" },
                            "hours_remaining": { "type": "number", "description": "0 once recovered" },
                            "recovered_at": { "type": "number", "description": "Unix time" } } },
                        "typical_load": nullable("number"),
                        "processing": { "type": "boolean" }
                    } })),
                ),
            );
        }
        ("get", "/profiles/{profile_id}/records") => {
            responses.insert(
                "200".into(),
                ok(
                    "One entry per standard distance (400 m, 1 km, 5 km, 10 km, half-marathon, marathon); record is null for a distance never covered",
                    Some(json!({ "type": "object", "properties": {
                        "distances": { "type": "array", "items": { "type": "object", "properties": {
                            "distance": { "type": "number" },
                            "record": { "oneOf": [r#ref("Effort"), { "type": "null" }] },
                            "next": { "type": "array", "items": r#ref("Effort"), "description": "Second and third best times" },
                            "history": { "type": "array", "items": r#ref("Effort"), "description": "Each run that beat the record, oldest first" } } } },
                        "processing": { "type": "boolean", "description": "Some activities are not taken into account yet" }
                    } })),
                ),
            );
        }
        ("get", "/profiles/{profile_id}/predictions") => {
            params.push(until_param());
            responses.insert(
                "200".into(),
                ok(
                    "Estimates only. Empty when no effort of at least 1 km exists in the window",
                    Some(json!({ "type": "object", "properties": {
                        "window_weeks": { "type": "number" },
                        "longest_recent_run": nullable("number"),
                        "predictions": { "type": "array", "items": { "type": "object", "properties": {
                            "distance": { "type": "number" }, "time": { "type": "number" }, "speed": { "type": "number" },
                            "indicative": { "type": "boolean", "description": "No recent run comes close to this distance" },
                            "basis": { "type": "object", "description": "The effort the estimate is projected from", "properties": {
                                "distance": { "type": "number" }, "moving_time": { "type": "number" }, "activity_id": { "type": "integer" },
                                "title": { "type": "string" }, "start_time": { "type": "number" } } } } } },
                        "processing": { "type": "boolean" }
                    } })),
                ),
            );
        }
        ("get", "/profiles/{profile_id}/derive-status") => {
            responses.insert(
                "200".into(),
                ok(
                    "Imports never wait for this work; figures derived from several activities appear once it is done",
                    Some(json!({ "type": "object", "properties": {
                        "pending_activities": { "type": "integer", "description": "Activities of the profile not processed yet" },
                        "total_activities": { "type": "integer" },
                        "busy": { "type": "boolean", "description": "True while anything is pending or queued" }
                    } })),
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
