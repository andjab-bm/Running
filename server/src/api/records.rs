//! Personal records and race predictions of a profile.
//!
//! Best efforts are stored per activity by the derive worker; records are read from
//! them on request, so they always reflect the profile's current activities.
use axum::{
    Json,
    extract::{Path, Query, State},
};
use serde::Deserialize;
use serde_json::{Value, json};
use sqlx::Row;

use super::{ApiError, ApiResult};
use crate::{
    AppState, derive,
    pipeline::efforts::{
        INDICATIVE_DISTANCE_RATIO, PREDICTED_DISTANCES, PREDICTION_MIN_EFFORT_M, PREDICTION_WINDOW_WEEKS, STANDARD_DISTANCES, predict,
    },
    store,
};

/// How many times are shown per distance: the record and the next two.
const TOP: usize = 3;

async fn require_profile(state: &AppState, profile_id: i64) -> ApiResult<()> {
    if store::profile_settings(&state.db, profile_id).await?.is_none() {
        return Err(ApiError::not_found("This profile"));
    }
    Ok(())
}

async fn processing(state: &AppState, profile_id: i64) -> anyhow::Result<bool> {
    Ok(derive::status(&state.db, profile_id).await?.0 > 0)
}

/// `GET /profiles/{id}/records`: for each standard distance, the record, the next two times,
/// and how the record evolved. A distance never covered has `record: null`.
pub async fn records(State(state): State<AppState>, Path(profile_id): Path<i64>) -> ApiResult<Json<Value>> {
    require_profile(&state, profile_id).await?;
    let mut distances = Vec::new();
    for d in STANDARD_DISTANCES {
        // One effort per activity and distance, so the ranking is a ranking of runs.
        let rows = sqlx::query(
            "SELECT e.moving_s, e.start_offset_m, a.id, a.title, a.start_time FROM best_efforts e
               JOIN activities a ON a.id = e.activity_id
              WHERE a.profile_id = ? AND e.distance_m = ? AND a.exclude_from_records = 0
              ORDER BY a.start_time, a.id",
        )
        .bind(profile_id)
        .bind(d)
        .fetch_all(&state.db)
        .await?;
        let effort = |r: &sqlx::sqlite::SqliteRow| {
            let t: f64 = r.get("moving_s");
            json!({
                "moving_time": t,
                "speed": d / t,
                "activity_id": r.get::<i64, _>("id"),
                "title": r.get::<String, _>("title"),
                "start_time": r.get::<f64, _>("start_time"),
                "start_offset": r.get::<f64, _>("start_offset_m"),
            })
        };
        // The record over time: each run, in date order, that beat every earlier one.
        let mut history = Vec::new();
        let mut best = f64::INFINITY;
        for r in &rows {
            let t: f64 = r.get("moving_s");
            if t < best {
                best = t;
                history.push(effort(r));
            }
        }
        let mut ranked: Vec<&sqlx::sqlite::SqliteRow> = rows.iter().collect();
        ranked.sort_by(|a, b| a.get::<f64, _>("moving_s").total_cmp(&b.get::<f64, _>("moving_s")));
        let top: Vec<Value> = ranked.iter().take(TOP).map(|r| effort(r)).collect();
        distances.push(json!({
            "distance": d,
            "record": top.first(),
            "next": top.iter().skip(1).collect::<Vec<_>>(),
            "history": history,
        }));
    }
    Ok(Json(
        json!({ "distances": distances, "processing": processing(&state, profile_id).await? }),
    ))
}

/// The best efforts of one activity, each with its current rank in the profile when it is
/// among the three best. An activity excluded from records has no rank.
pub async fn activity_efforts(state: &AppState, activity_id: i64, profile_id: i64, excluded: bool) -> anyhow::Result<Vec<Value>> {
    let rows = sqlx::query(
        "SELECT e.distance_m, e.moving_s, e.start_offset_m,
                (SELECT COUNT(*) FROM best_efforts o JOIN activities b ON b.id = o.activity_id
                  WHERE b.profile_id = ?2 AND b.exclude_from_records = 0 AND o.distance_m = e.distance_m AND o.moving_s < e.moving_s) AS faster
           FROM best_efforts e WHERE e.activity_id = ?1 ORDER BY e.distance_m",
    )
    .bind(activity_id)
    .bind(profile_id)
    .fetch_all(&state.db)
    .await?;
    Ok(rows
        .iter()
        .map(|r| {
            let rank = r.get::<i64, _>("faster") + 1;
            json!({
                "distance": r.get::<f64, _>("distance_m"),
                "moving_time": r.get::<f64, _>("moving_s"),
                "start_offset": r.get::<f64, _>("start_offset_m"),
                "rank": (!excluded && rank <= TOP as i64).then_some(rank),
            })
        })
        .collect())
}

#[derive(Deserialize)]
pub struct PredictionQuery {
    /// The instant taken as now (Unix seconds); defaults to the server clock.
    pub until: Option<f64>,
}

/// `GET /profiles/{id}/predictions`: estimated potential times over the race distances,
/// from the best efforts of the last weeks. Empty when no recent effort qualifies.
pub async fn predictions(
    State(state): State<AppState>,
    Path(profile_id): Path<i64>,
    Query(q): Query<PredictionQuery>,
) -> ApiResult<Json<Value>> {
    require_profile(&state, profile_id).await?;
    let now = q.until.unwrap_or_else(|| {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0.0, |d| d.as_secs_f64())
    });
    let since = now - PREDICTION_WINDOW_WEEKS * 7.0 * 86_400.0;
    let rows = sqlx::query(
        "SELECT e.distance_m, e.moving_s, a.id, a.title, a.start_time FROM best_efforts e
           JOIN activities a ON a.id = e.activity_id
          WHERE a.profile_id = ? AND a.exclude_from_records = 0 AND a.start_time >= ? AND a.start_time <= ? AND e.distance_m >= ?",
    )
    .bind(profile_id)
    .bind(since)
    .bind(now)
    .bind(PREDICTION_MIN_EFFORT_M)
    .fetch_all(&state.db)
    .await?;
    let longest: Option<f64> = sqlx::query(
        "SELECT MAX(distance) FROM activities WHERE profile_id = ? AND exclude_from_records = 0 AND start_time >= ? AND start_time <= ?",
    )
    .bind(profile_id)
    .bind(since)
    .bind(now)
    .fetch_one(&state.db)
    .await?
    .get(0);

    let efforts: Vec<(f64, f64)> = rows.iter().map(|r| (r.get("distance_m"), r.get("moving_s"))).collect();
    let list: Vec<Value> = PREDICTED_DISTANCES
        .iter()
        .filter_map(|&target| {
            let (time, from) = predict(&efforts, target)?;
            let r = &rows[from];
            Some(json!({
                "distance": target,
                "time": time,
                "speed": target / time,
                // No recent run comes close to that distance: the figure is only indicative.
                "indicative": longest.is_none_or(|l| target > INDICATIVE_DISTANCE_RATIO * l),
                "basis": {
                    "distance": efforts[from].0,
                    "moving_time": efforts[from].1,
                    "activity_id": r.get::<i64, _>("id"),
                    "title": r.get::<String, _>("title"),
                    "start_time": r.get::<f64, _>("start_time"),
                },
            }))
        })
        .collect();
    Ok(Json(json!({
        "window_weeks": PREDICTION_WINDOW_WEEKS,
        "longest_recent_run": longest,
        "predictions": list,
        "processing": processing(&state, profile_id).await?,
    })))
}
