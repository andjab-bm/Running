//! Activity endpoints: list, detail, streams, journal, delete, export.
use axum::{
    Json,
    body::Body,
    extract::{Path, Query, State},
    http::{StatusCode, header},
    response::{IntoResponse, Response},
};
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::{Value, json};
use sqlx::{Row, sqlite::SqliteRow};

use super::{ApiError, ApiResult};
use crate::{
    AppState,
    derive::{self, Job},
    pipeline::{
        gpx::{self, ExportPoint},
        time,
        workout::{Item, unfold, validate},
    },
    store,
};

/// Tags every profile starts with.
pub const PREDEFINED_TAGS: [&str; 3] = ["Endurance fondamentale", "Fractionné", "Compétition"];
const DEFAULT_PAGE: i64 = 20;
const MAX_PAGE: i64 = 100;
const MAX_TITLE: usize = 200;
const MAX_DESCRIPTION: usize = 10_000;
const MAX_TAGS: usize = 20;
const MAX_TAG_LEN: usize = 40;
const MAX_LINK_NAME: usize = 80;

const SUMMARY_COLUMNS: &str = "a.id, a.profile_id, a.title, a.description, a.rpe, a.start_time, a.sport, a.source_format, a.file_hash,
    a.copied_from, a.metrics_version, a.distance, a.elapsed_time, a.moving_time, a.speed, a.gap_speed, a.gain, a.loss,
    a.avg_hr, a.max_hr, a.avg_cad, a.effort, a.zone_seconds, a.preview, a.exclude_from_records, a.load, a.load_estimated,
    a.workout_id, a.workout_snapshot, a.route_id, a.route_name, a.pacer,
    (SELECT json_group_array(name) FROM (SELECT t.name FROM activity_tags x JOIN tags t ON t.id = x.tag_id
        WHERE x.activity_id = a.id ORDER BY t.name)) AS tags";

fn parse_json(text: Option<String>) -> Value {
    text.and_then(|t| serde_json::from_str(&t).ok()).unwrap_or(Value::Null)
}

/// Fields shared by the list and the detail view. Units are SI; times are Unix seconds.
fn summary_json(r: &SqliteRow) -> Value {
    json!({
        "id": r.get::<i64, _>("id"),
        "profile_id": r.get::<i64, _>("profile_id"),
        "title": r.get::<String, _>("title"),
        "start_time": r.get::<f64, _>("start_time"),
        "sport": r.get::<Option<String>, _>("sport"),
        "distance": r.get::<f64, _>("distance"),
        "elapsed_time": r.get::<f64, _>("elapsed_time"),
        "moving_time": r.get::<f64, _>("moving_time"),
        "speed": r.get::<f64, _>("speed"),
        "gap_speed": r.get::<Option<f64>, _>("gap_speed"),
        "gain": r.get::<Option<f64>, _>("gain"),
        "loss": r.get::<Option<f64>, _>("loss"),
        "avg_hr": r.get::<Option<f64>, _>("avg_hr"),
        "max_hr": r.get::<Option<f64>, _>("max_hr"),
        "avg_cad": r.get::<Option<f64>, _>("avg_cad"),
        "effort": r.get::<Option<f64>, _>("effort"),
        "load": r.get::<Option<f64>, _>("load"),
        "load_estimated": r.get::<i64, _>("load_estimated") != 0,
        "rpe": r.get::<Option<i64>, _>("rpe"),
        "copied_from": r.get::<Option<i64>, _>("copied_from"),
        "tags": parse_json(r.get("tags")),
        "preview": parse_json(Some(r.get("preview"))),
    })
}

#[derive(Deserialize)]
pub struct ListQuery {
    pub cursor: Option<String>,
    pub limit: Option<i64>,
    pub tag: Option<String>,
    /// Inclusive lower bound on the start time (Unix seconds).
    pub from: Option<f64>,
    /// Exclusive upper bound on the start time (Unix seconds).
    pub to: Option<f64>,
    /// Household list only: restrict to one profile.
    pub profile_id: Option<i64>,
}

/// `GET /profiles/{profile_id}/activities`: most recent first, in pages.
pub async fn list(State(state): State<AppState>, Path(profile_id): Path<i64>, Query(q): Query<ListQuery>) -> ApiResult<Json<Value>> {
    if store::profile_settings(&state.db, profile_id).await?.is_none() {
        return Err(ApiError::not_found("This profile"));
    }
    page(&state, Some(profile_id), false, q).await
}

/// `GET /activities`: the whole household, most recent first, in pages.
///
/// Without `profile_id`, a run copied between profiles is listed once: the copy is left
/// out while its original exists, and the original names the copies in `shared_with`.
pub async fn household(State(state): State<AppState>, Query(q): Query<ListQuery>) -> ApiResult<Json<Value>> {
    if let Some(p) = q.profile_id
        && store::profile_settings(&state.db, p).await?.is_none()
    {
        return Err(ApiError::not_found("This profile"));
    }
    let profile = q.profile_id;
    page(&state, profile, profile.is_none(), q).await
}

async fn page(state: &AppState, profile_id: Option<i64>, collapse_copies: bool, q: ListQuery) -> ApiResult<Json<Value>> {
    let limit = q.limit.unwrap_or(DEFAULT_PAGE).clamp(1, MAX_PAGE);
    let cursor: Option<(f64, i64)> = match q.cursor.as_deref() {
        None | Some("") => None,
        Some(c) => Some(
            c.split_once(':')
                .and_then(|(t, id)| Some((t.parse().ok()?, id.parse().ok()?)))
                .ok_or_else(|| ApiError::bad_request("The cursor is not valid."))?,
        ),
    };
    let tag = q.tag.as_deref().map(str::trim).filter(|t| !t.is_empty());
    // Only constants are interpolated; every value is bound.
    let rows = sqlx::query(sqlx::AssertSqlSafe(format!(
        "SELECT {SUMMARY_COLUMNS},
                (SELECT json_group_array(json_object('profile_id', c.profile_id, 'activity_id', c.id))
                   FROM activities c WHERE ?8 AND c.copied_from = a.id) AS shared_with
           FROM activities a
          WHERE (?1 IS NULL OR a.profile_id = ?1)
            AND (NOT ?8 OR a.copied_from IS NULL OR NOT EXISTS (SELECT 1 FROM activities o WHERE o.id = a.copied_from))
            AND (?2 IS NULL OR (a.start_time, a.id) < (?2, ?3))
            AND (?4 IS NULL OR a.start_time >= ?4)
            AND (?5 IS NULL OR a.start_time < ?5)
            AND (?6 IS NULL OR EXISTS (SELECT 1 FROM activity_tags x JOIN tags t ON t.id = x.tag_id
                                       WHERE x.activity_id = a.id AND t.name = ?6))
          ORDER BY a.start_time DESC, a.id DESC
          LIMIT ?7"
    )))
    .bind(profile_id)
    .bind(cursor.map(|c| c.0))
    .bind(cursor.map(|c| c.1))
    .bind(q.from)
    .bind(q.to)
    .bind(tag)
    .bind(limit + 1)
    .bind(collapse_copies)
    .fetch_all(&state.db)
    .await?;
    let more = rows.len() as i64 > limit;
    let page = &rows[..rows.len().min(limit as usize)];
    let next_cursor = more
        .then(|| {
            page.last()
                .map(|r| format!("{}:{}", r.get::<f64, _>("start_time"), r.get::<i64, _>("id")))
        })
        .flatten();
    let items: Vec<Value> = page
        .iter()
        .map(|r| {
            let mut item = summary_json(r);
            if collapse_copies {
                item["shared_with"] = parse_json(r.get("shared_with"));
            }
            item
        })
        .collect();
    Ok(Json(json!({ "items": items, "next_cursor": next_cursor })))
}

async fn segments(state: &AppState, id: i64, kind: &'static str) -> anyhow::Result<Vec<Value>> {
    let rows = sqlx::query(
        "SELECT start_t, distance, moving_time, elapsed_time, speed, gap_speed, gain, loss, avg_hr, avg_cad
         FROM segments WHERE activity_id = ? AND kind = ? ORDER BY idx",
    )
    .bind(id)
    .bind(kind)
    .fetch_all(&state.db)
    .await?;
    Ok(rows
        .iter()
        .map(|r| {
            json!({
                "start_t": r.get::<f64, _>("start_t"),
                "distance": r.get::<f64, _>("distance"),
                "moving_time": r.get::<f64, _>("moving_time"),
                "elapsed_time": r.get::<f64, _>("elapsed_time"),
                "speed": r.get::<f64, _>("speed"),
                "gap_speed": r.get::<Option<f64>, _>("gap_speed"),
                "gain": r.get::<Option<f64>, _>("gain"),
                "loss": r.get::<Option<f64>, _>("loss"),
                "avg_hr": r.get::<Option<f64>, _>("avg_hr"),
                "avg_cad": r.get::<Option<f64>, _>("avg_cad"),
            })
        })
        .collect())
}

async fn detail_json(state: &AppState, id: i64) -> ApiResult<Value> {
    let Some(row) = sqlx::query(sqlx::AssertSqlSafe(format!(
        "SELECT {SUMMARY_COLUMNS} FROM activities a WHERE a.id = ?"
    )))
    .bind(id)
    .fetch_optional(&state.db)
    .await?
    else {
        return Err(ApiError::not_found("This activity"));
    };
    let mut v = summary_json(&row);
    let profile_id: i64 = row.get("profile_id");
    let zone_seconds = parse_json(row.get("zone_seconds"));
    let zones = if zone_seconds.is_null() {
        Value::Null
    } else {
        let hr_max: Option<i64> = sqlx::query("SELECT hr_max FROM profiles WHERE id = ?")
            .bind(profile_id)
            .fetch_one(&state.db)
            .await?
            .get(0);
        let bounds = store::profile_settings(&state.db, profile_id).await?.map(|(z, _)| z.bounds);
        // `uses_default_hr_max` lets the page invite the athlete to set their own value.
        json!({ "seconds": zone_seconds, "bounds": bounds, "uses_default_hr_max": hr_max.is_none() })
    };
    let excluded = row.get::<i64, _>("exclude_from_records") != 0;

    // What the run was done with. The session is the copy kept with the activity, as it was
    // on the day; `in_library` tells whether the original still exists.
    let mut laps = segments(state, id, "lap").await?;
    let snapshot: Option<WorkoutLink> = row
        .get::<Option<String>, _>("workout_snapshot")
        .and_then(|s| serde_json::from_str(&s).ok());
    let workout = match snapshot {
        None => Value::Null,
        Some(w) => {
            let steps = unfold(&w.steps);
            // A step change is a lap, and nothing else marks a lap in a guided run: step n is lap n.
            for (lap, step) in laps.iter_mut().zip(&steps) {
                lap["step"] = json!(step);
            }
            let in_library = match w.id {
                Some(wid) => sqlx::query("SELECT 1 FROM workouts WHERE id = ?")
                    .bind(wid)
                    .fetch_optional(&state.db)
                    .await?
                    .is_some(),
                None => false,
            };
            json!({
                "id": w.id,
                "name": w.name,
                "steps": w.steps,
                "unfolded": steps,
                "steps_done": laps.len().min(steps.len()),
                "in_library": in_library,
            })
        }
    };
    let route = match row.get::<Option<String>, _>("route_name") {
        None => Value::Null,
        Some(name) => {
            let rid: Option<i64> = row.get("route_id");
            let in_library = match rid {
                Some(r) => sqlx::query("SELECT 1 FROM routes WHERE id = ?")
                    .bind(r)
                    .fetch_optional(&state.db)
                    .await?
                    .is_some(),
                None => false,
            };
            json!({ "id": rid, "name": name, "in_library": in_library })
        }
    };
    let extra = json!({
        "description": row.get::<String, _>("description"),
        "source_format": row.get::<String, _>("source_format"),
        "has_original": row.get::<Option<String>, _>("file_hash").is_some(),
        "metrics_version": row.get::<i64, _>("metrics_version"),
        "zones": zones,
        "splits": segments(state, id, "split").await?,
        "laps": laps,
        "workout": workout,
        "route": route,
        "pacer": parse_json(row.get("pacer")),
        // Passages over the profile's recurring segments, in the order they were run.
        "segment_efforts": super::segments::activity_efforts(state, id).await?,
        "exclude_from_records": excluded,
        // Fastest stretches of the standard distances; `rank` is set for the profile's three best times.
        "best_efforts": super::records::activity_efforts(state, id, profile_id, excluded).await?,
    });
    if let (Some(a), Some(b)) = (v.as_object_mut(), extra.as_object()) {
        a.extend(b.clone());
    }
    Ok(v)
}

/// `GET /activities/{id}`
pub async fn detail(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<Json<Value>> {
    Ok(Json(detail_json(&state, id).await?))
}

#[derive(Deserialize)]
pub struct StreamsQuery {
    pub resolution: Option<String>,
}

/// `GET /activities/{id}/streams?resolution=low|full`: index-aligned series.
pub async fn streams(State(state): State<AppState>, Path(id): Path<i64>, Query(q): Query<StreamsQuery>) -> ApiResult<Json<Value>> {
    let full = match q.resolution.as_deref() {
        None | Some("low") => false,
        Some("full") => true,
        Some(other) => {
            return Err(ApiError::bad_request(format!(
                "Unknown resolution {other:?}: use \"low\" or \"full\"."
            )));
        }
    };
    let Some(row) = sqlx::query("SELECT streams FROM activities WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
    else {
        return Err(ApiError::not_found("This activity"));
    };
    let stored = store::decode_streams(&row.get::<Vec<u8>, _>("streams"))?;
    let s = &stored.streams;
    let idx: Vec<usize> = if full { (0..s.len()).collect() } else { stored.low_res.clone() };
    fn pick<T: Clone>(v: &[T], idx: &[usize]) -> Vec<T> {
        idx.iter().map(|i| v[*i].clone()).collect()
    }
    Ok(Json(json!({
        "resolution": if full { "full" } else { "low" },
        "count": idx.len(),
        "t": pick(&s.t, &idx),
        "dist": pick(&s.dist, &idx),
        "lat": pick(&s.lat, &idx),
        "lon": pick(&s.lon, &idx),
        "speed": pick(&s.speed, &idx),
        "moving": pick(&s.moving, &idx),
        "ele": s.ele.as_ref().map(|e| pick(e, &idx)),
        "hr": s.hr.as_ref().map(|h| pick(h, &idx)),
        "cad": s.cad.as_ref().map(|c| pick(c, &idx)),
    })))
}

/// Distinguishes a field set to `null` (clear it) from a field left out (keep it).
fn nullable<'de, D: Deserializer<'de>, T: Deserialize<'de>>(d: D) -> Result<Option<Option<T>>, D::Error> {
    Option::<T>::deserialize(d).map(Some)
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Journal {
    pub title: Option<String>,
    pub description: Option<String>,
    #[serde(default, deserialize_with = "nullable")]
    pub rpe: Option<Option<i64>>,
    pub tags: Option<Vec<String>>,
    /// Leaves the activity out of records and predictions (a bike ride, a faulty trace).
    pub exclude_from_records: Option<bool>,
    /// The session the run was guided by, as it was on the day; `null` removes it.
    #[serde(default, deserialize_with = "nullable")]
    pub workout: Option<Option<WorkoutLink>>,
    /// The route that was followed; `null` removes it.
    #[serde(default, deserialize_with = "nullable")]
    pub route: Option<Option<RouteLink>>,
    /// The pace target and how the run ended against it; `null` removes it.
    #[serde(default, deserialize_with = "nullable")]
    pub pacer: Option<Option<PacerResult>>,
}

/// A session as it was when the run was done. Kept with the activity, so that deleting
/// or editing the session later does not rewrite history.
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct WorkoutLink {
    /// The session in the library, when it came from there.
    pub id: Option<i64>,
    pub name: String,
    pub steps: Vec<Item>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RouteLink {
    pub id: Option<i64>,
    pub name: String,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PacerResult {
    /// `pace` (seconds per kilometre) or `time` (seconds to finish the route).
    pub target: String,
    pub value: f64,
    /// `even`, or `grade` when the target was adjusted to the route's gradient.
    pub strategy: String,
    /// Seconds ahead of the target at the end; negative when behind.
    pub final_gap: f64,
}

fn invalid(message: impl Into<String>) -> ApiError {
    ApiError::new(StatusCode::UNPROCESSABLE_ENTITY, "invalid", message)
}

/// `PATCH /activities/{id}`: edits the journal. Recorded data and metrics are never touched.
pub async fn update(State(state): State<AppState>, Path(id): Path<i64>, Json(j): Json<Journal>) -> ApiResult<Json<Value>> {
    let Some(row) = sqlx::query("SELECT profile_id FROM activities WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
    else {
        return Err(ApiError::not_found("This activity"));
    };
    let profile_id: i64 = row.get(0);

    // Validate everything before writing anything.
    let title = j.title.map(|t| t.trim().to_string());
    if let Some(t) = &title
        && (t.is_empty() || t.chars().count() > MAX_TITLE)
    {
        return Err(invalid(format!("The title must have between 1 and {MAX_TITLE} characters.")));
    }
    if j.description.as_ref().is_some_and(|d| d.chars().count() > MAX_DESCRIPTION) {
        return Err(invalid(format!("The description is limited to {MAX_DESCRIPTION} characters.")));
    }
    if let Some(Some(r)) = j.rpe
        && !(1..=10).contains(&r)
    {
        return Err(invalid("The perceived effort must be between 1 and 10."));
    }
    let tags = match j.tags {
        None => None,
        Some(list) => {
            let mut clean: Vec<String> = Vec::new();
            for t in list {
                let t = t.trim().to_string();
                if t.is_empty() || t.chars().count() > MAX_TAG_LEN {
                    return Err(invalid(format!("A tag must have between 1 and {MAX_TAG_LEN} characters.")));
                }
                if !clean.iter().any(|c| c.to_lowercase() == t.to_lowercase()) {
                    clean.push(t);
                }
            }
            if clean.len() > MAX_TAGS {
                return Err(invalid(format!("An activity can carry at most {MAX_TAGS} tags.")));
            }
            Some(clean)
        }
    };

    if let Some(Some(w)) = &j.workout {
        if w.name.trim().is_empty() || w.name.chars().count() > MAX_LINK_NAME {
            return Err(invalid(format!(
                "The session name must have between 1 and {MAX_LINK_NAME} characters."
            )));
        }
        validate(&w.steps).map_err(invalid)?;
    }
    if let Some(Some(r)) = &j.route
        && (r.name.trim().is_empty() || r.name.chars().count() > MAX_LINK_NAME)
    {
        return Err(invalid(format!(
            "The route name must have between 1 and {MAX_LINK_NAME} characters."
        )));
    }
    if let Some(Some(p)) = &j.pacer {
        let known = matches!(p.target.as_str(), "pace" | "time") && matches!(p.strategy.as_str(), "even" | "grade");
        if !known || !p.value.is_finite() || p.value <= 0.0 || !p.final_gap.is_finite() {
            return Err(invalid("The pace target is not valid."));
        }
    }

    let mut tx = state.db.begin().await?;
    if let Some(w) = &j.workout {
        sqlx::query("UPDATE activities SET workout_id = ?, workout_snapshot = ? WHERE id = ?")
            .bind(w.as_ref().and_then(|w| w.id))
            .bind(w.as_ref().map(serde_json::to_string).transpose()?)
            .bind(id)
            .execute(&mut *tx)
            .await?;
    }
    if let Some(r) = &j.route {
        sqlx::query("UPDATE activities SET route_id = ?, route_name = ? WHERE id = ?")
            .bind(r.as_ref().and_then(|r| r.id))
            .bind(r.as_ref().map(|r| r.name.trim().to_string()))
            .bind(id)
            .execute(&mut *tx)
            .await?;
    }
    if let Some(p) = &j.pacer {
        sqlx::query("UPDATE activities SET pacer = ? WHERE id = ?")
            .bind(p.as_ref().map(serde_json::to_string).transpose()?)
            .bind(id)
            .execute(&mut *tx)
            .await?;
    }
    if let Some(t) = &title {
        sqlx::query("UPDATE activities SET title = ? WHERE id = ?")
            .bind(t)
            .bind(id)
            .execute(&mut *tx)
            .await?;
    }
    if let Some(d) = &j.description {
        sqlx::query("UPDATE activities SET description = ? WHERE id = ?")
            .bind(d.trim())
            .bind(id)
            .execute(&mut *tx)
            .await?;
    }
    if let Some(r) = j.rpe {
        sqlx::query("UPDATE activities SET rpe = ? WHERE id = ?")
            .bind(r)
            .bind(id)
            .execute(&mut *tx)
            .await?;
    }
    if let Some(x) = j.exclude_from_records {
        // Records are read from the stored efforts on request: nothing to recompute.
        sqlx::query("UPDATE activities SET exclude_from_records = ? WHERE id = ?")
            .bind(x)
            .bind(id)
            .execute(&mut *tx)
            .await?;
    }
    if let Some(tags) = &tags {
        sqlx::query("DELETE FROM activity_tags WHERE activity_id = ?")
            .bind(id)
            .execute(&mut *tx)
            .await?;
        for name in tags {
            // Unknown tags are created on first use; names are unique per profile, ignoring case.
            sqlx::query("INSERT OR IGNORE INTO tags (profile_id, name) VALUES (?, ?)")
                .bind(profile_id)
                .bind(name)
                .execute(&mut *tx)
                .await?;
            sqlx::query(
                "INSERT OR IGNORE INTO activity_tags (activity_id, tag_id) SELECT ?, id FROM tags WHERE profile_id = ? AND name = ?",
            )
            .bind(id)
            .bind(profile_id)
            .bind(name)
            .execute(&mut *tx)
            .await?;
        }
    }
    tx.commit().await?;
    if j.rpe.is_some() {
        // The training load of a run without heart rate is estimated from the perceived effort.
        derive::invalidate_activity(&state.db, id).await?;
        state.deriver.enqueue(Job::Activity(id));
    }
    Ok(Json(detail_json(&state, id).await?))
}

/// `DELETE /activities/{id}`: removes the activity, its journal and, if unused, its original file.
pub async fn delete(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<StatusCode> {
    let profile: Option<i64> = sqlx::query("SELECT profile_id FROM activities WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
        .map(|r| r.get(0));
    if store::delete_activity(&state.db, &state.config.data_dir, id).await? {
        if let Some(p) = profile {
            state.deriver.enqueue(Job::Profile(p));
        }
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(ApiError::not_found("This activity"))
    }
}

#[derive(Serialize)]
pub struct TagList {
    pub tags: Vec<String>,
}

/// `GET /profiles/{profile_id}/tags`: predefined tags plus those the profile has created.
pub async fn tags(State(state): State<AppState>, Path(profile_id): Path<i64>) -> ApiResult<Json<TagList>> {
    if store::profile_settings(&state.db, profile_id).await?.is_none() {
        return Err(ApiError::not_found("This profile"));
    }
    let mut tags: Vec<String> = PREDEFINED_TAGS.iter().map(|t| t.to_string()).collect();
    for r in sqlx::query("SELECT name FROM tags WHERE profile_id = ? ORDER BY name")
        .bind(profile_id)
        .fetch_all(&state.db)
        .await?
    {
        let name: String = r.get(0);
        if !tags.iter().any(|t| t.to_lowercase() == name.to_lowercase()) {
            tags.push(name);
        }
    }
    Ok(Json(TagList { tags }))
}

/// A file name safe for a `Content-Disposition` header: date, then a slug of the title.
fn download_name(start_time: f64, title: &str, extension: &str) -> String {
    let slug: String = title
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c.to_ascii_lowercase() } else { '-' })
        .collect::<String>()
        .split('-')
        .filter(|p| !p.is_empty())
        .collect::<Vec<_>>()
        .join("-");
    let date = &time::format_rfc3339(start_time)[..10];
    if slug.is_empty() {
        format!("{date}.{extension}")
    } else {
        format!("{date}-{slug}.{extension}")
    }
}

fn attachment(name: &str, content_type: &'static str, body: impl Into<Body>) -> Response {
    (
        [
            (header::CONTENT_TYPE, content_type.to_string()),
            (header::CONTENT_DISPOSITION, format!("attachment; filename=\"{name}\"")),
        ],
        body.into(),
    )
        .into_response()
}

/// `GET /activities/{id}/export.gpx`
pub async fn export_gpx(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<Response> {
    let Some(row) = sqlx::query("SELECT title, start_time, streams FROM activities WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
    else {
        return Err(ApiError::not_found("This activity"));
    };
    let (title, start_time): (String, f64) = (row.get("title"), row.get("start_time"));
    let s = store::decode_streams(&row.get::<Vec<u8>, _>("streams"))?.streams;
    let points: Vec<ExportPoint> = (0..s.len())
        .filter_map(|i| {
            Some(ExportPoint {
                time: start_time + s.t[i],
                lat: s.lat[i]?,
                lon: s.lon[i]?,
                ele: s.ele.as_ref().map(|e| e[i]),
                hr: s.hr.as_ref().and_then(|h| h[i]),
                cad: s.cad.as_ref().and_then(|c| c[i]),
            })
        })
        .collect();
    Ok(attachment(
        &download_name(start_time, &title, "gpx"),
        "application/gpx+xml",
        gpx::write(&title, &points),
    ))
}

/// `GET /activities/{id}/original`: the unmodified uploaded file.
pub async fn original(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<Response> {
    let Some(row) = sqlx::query("SELECT title, start_time, file_hash, source_format FROM activities WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
    else {
        return Err(ApiError::not_found("This activity"));
    };
    let format: String = row.get("source_format");
    let hash = row
        .get::<Option<String>, _>("file_hash")
        .ok_or_else(|| ApiError::not_found("The original file of this activity"))?;
    // Activities from a multi-track file carry a `#n` suffix after the content hash.
    let hash = hash.split('#').next().unwrap_or_default();
    let bytes = match tokio::fs::read(store::original_path(&state.config.data_dir, hash, &format)).await {
        Ok(b) => b,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Err(ApiError::not_found("The original file of this activity")),
        Err(e) => return Err(e.into()),
    };
    let content_type = if format == "gpx" {
        "application/gpx+xml"
    } else {
        "application/octet-stream"
    };
    Ok(attachment(
        &download_name(row.get("start_time"), &row.get::<String, _>("title"), &format),
        content_type,
        bytes,
    ))
}
