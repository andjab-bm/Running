//! Training load of a profile: daily and weekly load, short-term against long-term
//! load with its status, and the advised recovery after the latest run.
//!
//! The load of each activity is stored by the derive worker; everything else is
//! computed on request from those values, with days cut in the caller's local time.
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
    pipeline::{
        load::{LONG_TERM_DAYS, SHORT_TERM_DAYS, TYPICAL_WINDOW_WEEKS, averaged, median, recovery_hours, status},
        time::civil_from_days,
    },
    store,
};

const DAY_S: f64 = 86_400.0;
const DEFAULT_DAYS: i64 = 84;
const MAX_DAYS: i64 = 366;

#[derive(Deserialize)]
pub struct LoadQuery {
    /// How many days to return, ending today.
    pub days: Option<i64>,
    /// The instant taken as now (Unix seconds); defaults to the server clock.
    pub until: Option<f64>,
    pub tz_offset_minutes: Option<i32>,
}

struct Run {
    id: i64,
    start: f64,
    end: f64,
    load: f64,
    estimated: bool,
}

fn date(day: i64) -> String {
    let (y, m, d) = civil_from_days(day);
    format!("{y:04}-{m:02}-{d:02}")
}

/// `GET /profiles/{id}/load`
pub async fn load(State(state): State<AppState>, Path(profile_id): Path<i64>, Query(q): Query<LoadQuery>) -> ApiResult<Json<Value>> {
    if store::profile_settings(&state.db, profile_id).await?.is_none() {
        return Err(ApiError::not_found("This profile"));
    }
    let now = q.until.unwrap_or_else(|| {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0.0, |d| d.as_secs_f64())
    });
    let off = f64::from(q.tz_offset_minutes.unwrap_or(0).clamp(-840, 840)) * 60.0;
    let local_day = |t: f64| ((t + off) / DAY_S).floor() as i64;
    let wanted = q.days.unwrap_or(DEFAULT_DAYS).clamp(7, MAX_DAYS) as usize;
    let processing = derive::status(&state.db, profile_id).await?.0 > 0;

    // Activities not processed yet have no load: they are left out until the worker reaches them.
    let runs: Vec<Run> = sqlx::query(
        "SELECT id, start_time, elapsed_time, load, load_estimated FROM activities
          WHERE profile_id = ? AND load IS NOT NULL AND start_time <= ? ORDER BY start_time",
    )
    .bind(profile_id)
    .bind(now)
    .fetch_all(&state.db)
    .await?
    .iter()
    .map(|r| Run {
        id: r.get("id"),
        start: r.get("start_time"),
        end: r.get::<f64, _>("start_time") + r.get::<f64, _>("elapsed_time"),
        load: r.get("load"),
        estimated: r.get::<i64, _>("load_estimated") != 0,
    })
    .collect();

    let today = local_day(now);
    let Some(first) = runs.first().map(|r| local_day(r.start)) else {
        return Ok(Json(json!({
            "days": [], "weeks": [], "recovery": null, "typical_load": null, "processing": processing,
            "status": { "code": "not_enough_data", "ratio": null, "short": null, "long": null, "history_days": 0 },
        })));
    };

    // One value per day from the first run to today, rest days at zero.
    let history = (today - first + 1) as usize;
    let mut daily = vec![0.0; history];
    let mut estimated = vec![false; history];
    for r in &runs {
        let i = (local_day(r.start) - first) as usize;
        daily[i] += r.load;
        estimated[i] |= r.estimated;
    }
    let (short, long) = (averaged(&daily, SHORT_TERM_DAYS), averaged(&daily, LONG_TERM_DAYS));
    let ratio_at = |i: usize| if long[i] > 0.0 { short[i] / long[i] } else { 1.0 };

    let from = history.saturating_sub(wanted);
    let days: Vec<Value> = (from..history)
        .map(|i| {
            json!({
                "date": date(first + i as i64),
                "load": daily[i],
                "estimated": estimated[i],
                "short": short[i],
                "long": long[i],
            })
        })
        .collect();

    // Weeks from Monday, covering the returned days.
    let monday = |day: i64| day - (day + 3).rem_euclid(7);
    let mut weeks: Vec<Value> = Vec::new();
    let mut week = monday(first + from as i64);
    while week <= today {
        let total: f64 = (week..week + 7)
            .filter(|d| (first..=today).contains(d))
            .map(|d| daily[(d - first) as usize])
            .sum();
        weeks.push(json!({ "start": date(week), "load": total, "current": week == monday(today) }));
        week += 7;
    }

    // Recovery: each run adds to what was still owed from the one before.
    let window = now - TYPICAL_WINDOW_WEEKS * 7.0 * DAY_S;
    let typical = median(&mut runs.iter().filter(|r| r.start >= window).map(|r| r.load).collect::<Vec<_>>());
    let mut recovered_at = f64::NEG_INFINITY;
    let mut recovery = Value::Null;
    for r in &runs {
        let owed_h = ((recovered_at - r.start) / 3600.0).max(0.0);
        let i = (local_day(r.start) - first) as usize;
        let advised = recovery_hours(r.load, typical.unwrap_or(r.load), ratio_at(i), owed_h);
        recovered_at = r.end + advised * 3600.0;
        recovery = json!({
            "activity_id": r.id,
            "load": r.load,
            "estimated": r.estimated,
            "hours_advised": advised,
            "hours_remaining": ((recovered_at - now) / 3600.0).max(0.0),
            "recovered_at": recovered_at,
        });
    }

    let (s, l) = (short[history - 1], long[history - 1]);
    let code = status(s, l, history).code();
    Ok(Json(json!({
        "days": days,
        "weeks": weeks,
        "status": {
            "code": code,
            "ratio": (code != "not_enough_data").then(|| s / l),
            "short": s,
            "long": l,
            "history_days": history,
        },
        "recovery": recovery,
        "typical_load": typical,
        "processing": processing,
    })))
}
