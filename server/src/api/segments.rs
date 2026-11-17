//! Personal segments: the recurring portions of route found by the derive worker,
//! and the efforts timed on them. (`courses` in the database; see `pipeline::courses`.)
use axum::{
    Json,
    extract::{Path, Query, State},
    http::StatusCode,
};
use serde::Deserialize;
use serde_json::{Value, json};
use sqlx::Row;

use super::{ApiError, ApiResult};
use crate::{
    AppState,
    derive::{self, segments::decode_shape},
    store,
};

const MAX_NAME: usize = 80;
/// Efforts marked on a segment page: the best three.
const TOP: i64 = 3;
const PREVIEW_POINTS: usize = 48;

#[derive(Deserialize)]
pub struct ListQuery {
    /// `count` (most run first, the default) or `recent` (latest effort first).
    pub sort: Option<String>,
    /// List the hidden segments instead of the visible ones.
    #[serde(default)]
    pub hidden: bool,
}

/// `GET /profiles/{id}/segments`
pub async fn list(State(state): State<AppState>, Path(profile_id): Path<i64>, Query(q): Query<ListQuery>) -> ApiResult<Json<Value>> {
    if store::profile_settings(&state.db, profile_id).await?.is_none() {
        return Err(ApiError::not_found("This profile"));
    }
    let recent = match q.sort.as_deref() {
        None | Some("count") => false,
        Some("recent") => true,
        Some(_) => return Err(ApiError::bad_request("The sort must be \"count\" or \"recent\".")),
    };
    let rows = sqlx::query(
        "SELECT c.id, c.name, c.distance, c.gain, c.hidden, c.points,
                COUNT(e.id) AS efforts, MIN(e.elapsed_s) AS best, MAX(a.start_time + e.start_t) AS latest
           FROM courses c
           LEFT JOIN course_efforts e ON e.course_id = c.id
           LEFT JOIN activities a ON a.id = e.activity_id
          WHERE c.profile_id = ? AND c.hidden = ?
          GROUP BY c.id",
    )
    .bind(profile_id)
    .bind(q.hidden)
    .fetch_all(&state.db)
    .await?;
    let mut items = Vec::new();
    for r in &rows {
        let shape = decode_shape(&r.get::<Vec<u8>, _>("points"))?;
        let step = (shape.points.len() as f64 / PREVIEW_POINTS as f64).max(1.0);
        let mut preview: Vec<[f64; 2]> = (0..)
            .map(|i| (i as f64 * step) as usize)
            .take_while(|i| *i < shape.points.len())
            .map(|i| [shape.points[i].lat, shape.points[i].lon])
            .collect();
        if let Some(end) = shape.points.last() {
            preview.push([end.lat, end.lon]);
        }
        items.push(json!({
            "id": r.get::<i64, _>("id"),
            "name": r.get::<String, _>("name"),
            "distance": r.get::<f64, _>("distance"),
            "gain": r.get::<Option<f64>, _>("gain"),
            "hidden": r.get::<i64, _>("hidden") != 0,
            "efforts": r.get::<i64, _>("efforts"),
            "best_time": r.get::<Option<f64>, _>("best"),
            "latest_effort": r.get::<Option<f64>, _>("latest"),
            "preview": preview,
        }));
    }
    let key = |v: &Value, k: &str| v[k].as_f64().unwrap_or(0.0);
    if recent {
        items.sort_by(|a, b| key(b, "latest_effort").total_cmp(&key(a, "latest_effort")));
    } else {
        items.sort_by(|a, b| {
            key(b, "efforts")
                .total_cmp(&key(a, "efforts"))
                .then(key(b, "latest_effort").total_cmp(&key(a, "latest_effort")))
        });
    }
    let hidden_count: i64 = sqlx::query("SELECT COUNT(*) FROM courses WHERE profile_id = ? AND hidden = 1")
        .bind(profile_id)
        .fetch_one(&state.db)
        .await?
        .get(0);
    Ok(Json(json!({
        "items": items,
        "hidden_count": hidden_count,
        // Detection follows imports in the background.
        "processing": derive::status(&state.db, profile_id).await?.0 > 0,
    })))
}

async fn detail_json(state: &AppState, id: i64) -> ApiResult<Value> {
    let Some(c) = sqlx::query("SELECT id, profile_id, name, distance, gain, hidden, points FROM courses WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
    else {
        return Err(ApiError::not_found("This segment"));
    };
    let distance: f64 = c.get("distance");
    let shape = decode_shape(&c.get::<Vec<u8>, _>("points"))?;
    let rows = sqlx::query(
        "SELECT e.id, e.activity_id, e.start_t, e.elapsed_s, e.avg_hr, a.title, a.start_time
           FROM course_efforts e JOIN activities a ON a.id = e.activity_id
          WHERE e.course_id = ? ORDER BY e.elapsed_s, a.start_time",
    )
    .bind(id)
    .fetch_all(&state.db)
    .await?;
    let best: Option<f64> = rows.first().map(|r| r.get("elapsed_s"));
    let efforts: Vec<Value> = rows
        .iter()
        .enumerate()
        .map(|(i, r)| {
            let t: f64 = r.get("elapsed_s");
            json!({
                "id": r.get::<i64, _>("id"),
                "activity_id": r.get::<i64, _>("activity_id"),
                "title": r.get::<String, _>("title"),
                // When the passage began.
                "start_time": r.get::<f64, _>("start_time") + r.get::<f64, _>("start_t"),
                "start_t": r.get::<f64, _>("start_t"),
                "elapsed_time": t,
                "speed": if t > 0.0 { distance / t } else { 0.0 },
                "avg_hr": r.get::<Option<f64>, _>("avg_hr"),
                "rank": i + 1,
                "gap_to_best": best.map(|b| t - b),
            })
        })
        .collect();
    Ok(json!({
        "id": c.get::<i64, _>("id"),
        "profile_id": c.get::<i64, _>("profile_id"),
        "name": c.get::<String, _>("name"),
        "distance": distance,
        "gain": c.get::<Option<f64>, _>("gain"),
        "hidden": c.get::<i64, _>("hidden") != 0,
        "points": {
            "lat": shape.points.iter().map(|p| p.lat).collect::<Vec<_>>(),
            "lon": shape.points.iter().map(|p| p.lon).collect::<Vec<_>>(),
            "dist": shape.points.iter().map(|p| p.dist).collect::<Vec<_>>(),
            "ele": shape.ele,
        },
        // Ranked by time, fastest first.
        "efforts": efforts,
    }))
}

/// `GET /segments/{id}`: the trace, its profile and every effort, ranked.
pub async fn detail(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<Json<Value>> {
    Ok(Json(detail_json(&state, id).await?))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Changes {
    pub name: Option<String>,
    pub hidden: Option<bool>,
}

/// `PATCH /segments/{id}`: rename, hide or restore. A hidden segment keeps its row, so it is
/// not detected again, and keeps collecting efforts, so restoring it is complete.
pub async fn update(State(state): State<AppState>, Path(id): Path<i64>, Json(c): Json<Changes>) -> ApiResult<Json<Value>> {
    if sqlx::query("SELECT 1 FROM courses WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
        .is_none()
    {
        return Err(ApiError::not_found("This segment"));
    }
    if let Some(name) = &c.name {
        let name = name.trim();
        if name.is_empty() || name.chars().count() > MAX_NAME {
            return Err(ApiError::new(
                StatusCode::UNPROCESSABLE_ENTITY,
                "invalid",
                format!("The name must have between 1 and {MAX_NAME} characters."),
            ));
        }
        sqlx::query("UPDATE courses SET name = ?, custom_name = 1 WHERE id = ?")
            .bind(name)
            .bind(id)
            .execute(&state.db)
            .await?;
    }
    if let Some(hidden) = c.hidden {
        sqlx::query("UPDATE courses SET hidden = ? WHERE id = ?")
            .bind(hidden)
            .bind(id)
            .execute(&state.db)
            .await?;
    }
    Ok(Json(detail_json(&state, id).await?))
}

/// The efforts of one activity on visible segments, each with its rank among the
/// profile's efforts on that segment and the gap to the best.
pub async fn activity_efforts(state: &AppState, activity_id: i64) -> anyhow::Result<Vec<Value>> {
    let rows = sqlx::query(
        "SELECT e.id, e.course_id, e.start_t, e.elapsed_s, e.avg_hr, c.name, c.distance,
                (SELECT COUNT(*) FROM course_efforts o WHERE o.course_id = e.course_id AND o.elapsed_s < e.elapsed_s) AS faster,
                (SELECT COUNT(*) FROM course_efforts o WHERE o.course_id = e.course_id) AS total,
                (SELECT MIN(o.elapsed_s) FROM course_efforts o WHERE o.course_id = e.course_id) AS best
           FROM course_efforts e JOIN courses c ON c.id = e.course_id
          WHERE e.activity_id = ? AND c.hidden = 0
          ORDER BY e.start_t",
    )
    .bind(activity_id)
    .fetch_all(&state.db)
    .await?;
    Ok(rows
        .iter()
        .map(|r| {
            let (t, best): (f64, f64) = (r.get("elapsed_s"), r.get("best"));
            let rank = r.get::<i64, _>("faster") + 1;
            json!({
                "segment_id": r.get::<i64, _>("course_id"),
                "name": r.get::<String, _>("name"),
                "distance": r.get::<f64, _>("distance"),
                "start_t": r.get::<f64, _>("start_t"),
                "elapsed_time": t,
                "avg_hr": r.get::<Option<f64>, _>("avg_hr"),
                "rank": rank,
                "efforts": r.get::<i64, _>("total"),
                "gap_to_best": t - best,
                "podium": rank <= TOP,
            })
        })
        .collect())
}
