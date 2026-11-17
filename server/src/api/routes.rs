//! Routes of a profile: imported from GPX or taken from a run, shown, exported, marked for the phone.
use axum::{
    Json,
    body::Body,
    extract::{Multipart, Path, State},
    http::{StatusCode, header},
    response::{IntoResponse, Response},
};
use serde::Deserialize;
use serde_json::{Value, json};
use sqlx::{Row, sqlite::SqliteRow};

use super::{ApiError, ApiResult, plan};
use crate::{
    AppState,
    pipeline::{
        gpx,
        routes::{self, Route, Shape},
    },
    store,
};

const MAX_NAME: usize = 80;
const PREVIEW_POINTS: usize = 64;
const COLUMNS: &str = "id, profile_id, name, distance, gain, loss, is_loop, has_elevation, preview, on_phone, version, updated_at";

fn invalid(message: impl Into<String>) -> ApiError {
    ApiError::new(StatusCode::UNPROCESSABLE_ENTITY, "invalid", message)
}

fn clean_name(name: &str) -> ApiResult<String> {
    let name = name.trim();
    if name.is_empty() || name.chars().count() > MAX_NAME {
        return Err(invalid(format!("The name must have between 1 and {MAX_NAME} characters.")));
    }
    Ok(name.to_string())
}

fn encode(shape: &Shape) -> anyhow::Result<Vec<u8>> {
    Ok(zstd::encode_all(serde_json::to_vec(shape)?.as_slice(), 3)?)
}

fn decode(blob: &[u8]) -> anyhow::Result<Shape> {
    Ok(serde_json::from_slice(&zstd::decode_all(blob)?)?)
}

async fn insert(state: &AppState, profile_id: i64, name: &str, r: &Route) -> anyhow::Result<i64> {
    Ok(sqlx::query(
        "INSERT INTO routes (profile_id, name, distance, gain, loss, is_loop, has_elevation, points, preview)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(profile_id)
    .bind(name)
    .bind(r.distance)
    .bind(r.gain)
    .bind(r.loss)
    .bind(r.is_loop)
    .bind(r.shape.ele.is_some())
    .bind(encode(&r.shape)?)
    .bind(serde_json::to_string(&routes::preview(&r.shape, PREVIEW_POINTS))?)
    .execute(&state.db)
    .await?
    .last_insert_rowid())
}

async fn to_json(state: &AppState, r: &SqliteRow) -> anyhow::Result<Value> {
    let (id, profile_id, version): (i64, i64, i64) = (r.get("id"), r.get("profile_id"), r.get("version"));
    let on_phone = r.get::<i64, _>("on_phone") != 0;
    Ok(json!({
        "id": id,
        "profile_id": profile_id,
        "name": r.get::<String, _>("name"),
        "distance": r.get::<f64, _>("distance"),
        // Null without elevation data: gradient-adjusted pacing is then not offered.
        "gain": r.get::<Option<f64>, _>("gain"),
        "loss": r.get::<Option<f64>, _>("loss"),
        "is_loop": r.get::<i64, _>("is_loop") != 0,
        "has_elevation": r.get::<i64, _>("has_elevation") != 0,
        "preview": serde_json::from_str::<Value>(&r.get::<String, _>("preview")).unwrap_or(Value::Null),
        "on_phone": on_phone,
        "version": version,
        "updated_at": r.get::<f64, _>("updated_at"),
        "delivery": plan::delivery(&state.db, profile_id, "route", id, on_phone, version).await?,
    }))
}

async fn row(state: &AppState, id: i64) -> ApiResult<SqliteRow> {
    sqlx::query(sqlx::AssertSqlSafe(format!("SELECT {COLUMNS}, points FROM routes WHERE id = ?")))
        .bind(id)
        .fetch_optional(&state.db)
        .await?
        .ok_or_else(|| ApiError::not_found("This route"))
}

async fn load(state: &AppState, id: i64) -> ApiResult<Value> {
    let r = row(state, id).await?;
    let mut v = to_json(state, &r).await?;
    // The runs done while following this route, most recent first.
    let runs: Vec<Value> = sqlx::query(
        "SELECT id, title, start_time, distance, moving_time FROM activities WHERE route_id = ? ORDER BY start_time DESC LIMIT 50",
    )
    .bind(id)
    .fetch_all(&state.db)
    .await?
    .iter()
    .map(|a| {
        json!({
            "id": a.get::<i64, _>("id"),
            "title": a.get::<String, _>("title"),
            "start_time": a.get::<f64, _>("start_time"),
            "distance": a.get::<f64, _>("distance"),
            "moving_time": a.get::<f64, _>("moving_time"),
        })
    })
    .collect();
    v["activities"] = json!(runs);
    Ok(v)
}

/// `GET /profiles/{id}/routes`
pub async fn list(State(state): State<AppState>, Path(profile_id): Path<i64>) -> ApiResult<Json<Value>> {
    if store::profile_settings(&state.db, profile_id).await?.is_none() {
        return Err(ApiError::not_found("This profile"));
    }
    let rows = sqlx::query(sqlx::AssertSqlSafe(format!(
        "SELECT {COLUMNS} FROM routes WHERE profile_id = ? ORDER BY updated_at DESC, id DESC"
    )))
    .bind(profile_id)
    .fetch_all(&state.db)
    .await?;
    let mut items = Vec::new();
    for r in &rows {
        items.push(to_json(&state, r).await?);
    }
    Ok(Json(
        json!({ "items": items, "last_phone_sync": plan::last_sync(&state.db, profile_id).await? }),
    ))
}

/// `POST /profiles/{id}/routes`: one or more `.gpx` files, each `<rte>` or `<trk>` giving a route.
/// Timestamps are not needed and recorded data is ignored. One outcome per route found.
pub async fn import(State(state): State<AppState>, Path(profile_id): Path<i64>, mut multipart: Multipart) -> ApiResult<Json<Value>> {
    if store::profile_settings(&state.db, profile_id).await?.is_none() {
        return Err(ApiError::not_found("This profile"));
    }
    let max = state.config.max_upload_bytes;
    let mut results: Vec<Value> = Vec::new();
    loop {
        let mut field = match multipart.next_field().await {
            Ok(Some(f)) => f,
            Ok(None) => break,
            Err(e) => return Err(ApiError::bad_request(format!("The upload could not be read: {e}"))),
        };
        let Some(file) = field.file_name().map(str::to_string) else {
            continue;
        };
        let mut bytes: Vec<u8> = Vec::new();
        let mut too_large = false;
        loop {
            match field.chunk().await {
                Ok(Some(chunk)) => {
                    if too_large || bytes.len() + chunk.len() > max {
                        too_large = true;
                        bytes = Vec::new();
                    } else {
                        bytes.extend_from_slice(&chunk);
                    }
                }
                Ok(None) => break,
                Err(e) => return Err(ApiError::bad_request(format!("The upload was interrupted: {e}"))),
            }
        }
        let failed = |reason: String| json!({ "file": file, "status": "failed", "reason": reason });
        if too_large {
            results.push(failed(format!("The file is larger than the {} MB limit.", max / (1024 * 1024))));
            continue;
        }
        let paths = match tokio::task::spawn_blocking(move || gpx::read_paths(&bytes)).await? {
            Ok(p) => p,
            Err(e) => {
                results.push(failed(match e {
                    crate::pipeline::ParseError::Unrecognised => "The file is not a GPX document.".into(),
                    crate::pipeline::ParseError::Empty => "The file contains no route.".into(),
                    other => other.to_string(),
                }));
                continue;
            }
        };
        for (name, points) in paths {
            let built = tokio::task::spawn_blocking(move || routes::from_points(&points)).await?;
            let Some(route) = built else {
                results.push(failed("A route needs at least two distinct points.".into()));
                continue;
            };
            // Named after the route in the file, else after the file itself.
            let fallback = file.rsplit_once('.').map_or(file.as_str(), |(stem, _)| stem);
            let name: String = name
                .filter(|n| !n.trim().is_empty())
                .unwrap_or_else(|| fallback.to_string())
                .trim()
                .chars()
                .take(MAX_NAME)
                .collect();
            let id = insert(&state, profile_id, if name.is_empty() { "Parcours" } else { &name }, &route).await?;
            results.push(json!({ "file": file, "status": "imported", "route_id": id }));
        }
    }
    Ok(Json(json!({ "results": results })))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FromActivity {
    pub name: Option<String>,
    /// Portion of the run to keep, in metres from its start; the whole run by default.
    pub from_m: Option<f64>,
    pub to_m: Option<f64>,
}

/// `POST /activities/{id}/route`: makes a route from a run, in the run's profile.
/// Only the path and elevations are kept: no time, pace or heart rate.
pub async fn from_activity(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Json(f): Json<FromActivity>,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let Some(a) = sqlx::query("SELECT profile_id, title, streams FROM activities WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
    else {
        return Err(ApiError::not_found("This activity"));
    };
    let name = match &f.name {
        Some(n) => clean_name(n)?,
        None => a.get::<String, _>("title").chars().take(MAX_NAME).collect(),
    };
    let blob: Vec<u8> = a.get("streams");
    let (from, to) = (f.from_m.unwrap_or(0.0), f.to_m.unwrap_or(f64::INFINITY));
    let built = tokio::task::spawn_blocking(move || -> anyhow::Result<Option<Route>> {
        Ok(routes::from_streams(&store::decode_streams(&blob)?.streams, from, to))
    })
    .await??;
    let Some(route) = built else {
        return Err(invalid("This portion of the run has no usable path."));
    };
    let new_id = insert(&state, a.get("profile_id"), &name, &route).await?;
    Ok((StatusCode::CREATED, Json(load(&state, new_id).await?)))
}

/// `GET /routes/{id}`
pub async fn detail(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<Json<Value>> {
    Ok(Json(load(&state, id).await?))
}

/// `GET /routes/{id}/points`: the path as index-aligned arrays, for the map, the profile and the phone.
pub async fn points(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<Json<Value>> {
    let r = row(&state, id).await?;
    let shape = decode(&r.get::<Vec<u8>, _>("points"))?;
    Ok(Json(json!({
        "id": id,
        "version": r.get::<i64, _>("version"),
        "count": shape.lat.len(),
        "lat": shape.lat,
        "lon": shape.lon,
        "ele": shape.ele,
        "dist": shape.dist,
    })))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Changes {
    pub name: Option<String>,
    pub on_phone: Option<bool>,
}

/// `PATCH /routes/{id}`: rename (bumps the version) or mark for the phone.
pub async fn update(State(state): State<AppState>, Path(id): Path<i64>, Json(c): Json<Changes>) -> ApiResult<Json<Value>> {
    let r = row(&state, id).await?;
    if let Some(n) = &c.name {
        let name = clean_name(n)?;
        if name != r.get::<String, _>("name") {
            sqlx::query("UPDATE routes SET name = ?, version = version + 1, updated_at = unixepoch('subsec') WHERE id = ?")
                .bind(name)
                .bind(id)
                .execute(&state.db)
                .await?;
        }
    }
    if let Some(on) = c.on_phone {
        sqlx::query("UPDATE routes SET on_phone = ? WHERE id = ?")
            .bind(on)
            .bind(id)
            .execute(&state.db)
            .await?;
    }
    Ok(Json(load(&state, id).await?))
}

/// `DELETE /routes/{id}`: activities run with the route keep its name.
pub async fn delete(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<StatusCode> {
    let gone = sqlx::query("DELETE FROM routes WHERE id = ?")
        .bind(id)
        .execute(&state.db)
        .await?
        .rows_affected();
    if gone == 0 {
        return Err(ApiError::not_found("This route"));
    }
    sqlx::query("DELETE FROM plan_receipts WHERE kind = 'route' AND item_id = ?")
        .bind(id)
        .execute(&state.db)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn stored(state: &AppState, id: i64) -> ApiResult<(SqliteRow, Route)> {
    let r = row(state, id).await?;
    let shape = decode(&r.get::<Vec<u8>, _>("points"))?;
    let route = Route {
        shape,
        distance: r.get("distance"),
        gain: r.get("gain"),
        loss: r.get("loss"),
        is_loop: r.get::<i64, _>("is_loop") != 0,
    };
    Ok((r, route))
}

/// `POST /routes/{id}/reverse`: the same path the other way, as a new route.
pub async fn reverse(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<(StatusCode, Json<Value>)> {
    let (r, route) = stored(&state, id).await?;
    let name: String = format!("{} (inverse)", r.get::<String, _>("name")).chars().take(MAX_NAME).collect();
    let new_id = insert(&state, r.get("profile_id"), &name, &routes::reversed(&route)).await?;
    Ok((StatusCode::CREATED, Json(load(&state, new_id).await?)))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CopyTo {
    pub profile_id: i64,
}

/// `POST /routes/{id}/copy`: an independent copy in another profile.
pub async fn copy(State(state): State<AppState>, Path(id): Path<i64>, Json(c): Json<CopyTo>) -> ApiResult<(StatusCode, Json<Value>)> {
    let (r, route) = stored(&state, id).await?;
    if store::profile_settings(&state.db, c.profile_id).await?.is_none() {
        return Err(ApiError::not_found("The target profile"));
    }
    let new_id = insert(&state, c.profile_id, &r.get::<String, _>("name"), &route).await?;
    Ok((StatusCode::CREATED, Json(load(&state, new_id).await?)))
}

/// `GET /routes/{id}/export.gpx`
pub async fn export_gpx(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<Response> {
    let (r, route) = stored(&state, id).await?;
    let name: String = r.get("name");
    let body = gpx::write_path(&name, &route.shape.lat, &route.shape.lon, route.shape.ele.as_deref());
    let file: String = name
        .chars()
        .map(|ch| if ch.is_ascii_alphanumeric() { ch } else { '-' })
        .collect::<String>()
        .trim_matches('-')
        .to_string();
    let file = if file.is_empty() { format!("parcours-{id}") } else { file };
    Ok((
        [
            (header::CONTENT_TYPE, "application/gpx+xml".to_string()),
            (header::CONTENT_DISPOSITION, format!("attachment; filename=\"{file}.gpx\"")),
        ],
        Body::from(body),
    )
        .into_response())
}
