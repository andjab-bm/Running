//! Statistics of a profile: calendar, weekly and monthly volume, monthly progress.
//!
//! Nothing is stored: figures are aggregated from the activity rows on request, so
//! they always reflect the profile's current activities. Days, weeks and months are
//! cut in the caller's local time, given as `tz_offset_minutes` (minutes east of UTC),
//! because the server has no time-zone database.
use axum::{
    Json,
    extract::{Path, Query, State},
};
use serde::Deserialize;
use serde_json::{Value, json};
use sqlx::Row;

use super::{ApiError, ApiResult};
use crate::{
    AppState,
    pipeline::time::{civil_from_days, days_from_civil},
    store,
};

const DAY_S: i64 = 86_400;
const DEFAULT_PERIODS: i64 = 12;
const MAX_PERIODS: i64 = 60;
const MAX_OFFSET_MIN: i64 = 14 * 60;

struct Run {
    id: i64,
    title: String,
    start: f64,
    distance: f64,
    moving: f64,
    gain: Option<f64>,
    hr: Option<f64>,
    load: Option<f64>,
}

fn offset(minutes: Option<i32>) -> i64 {
    i64::from(minutes.unwrap_or(0)).clamp(-MAX_OFFSET_MIN, MAX_OFFSET_MIN)
}

/// Days since 1970-01-01 of the local date an instant falls on.
fn local_day(unix: f64, offset_min: i64) -> i64 {
    (unix.floor() as i64 + offset_min * 60).div_euclid(DAY_S)
}

/// The instant a local day starts at.
fn day_start(day: i64, offset_min: i64) -> f64 {
    (day * DAY_S - offset_min * 60) as f64
}

fn date(day: i64) -> String {
    let (y, m, d) = civil_from_days(day);
    format!("{y:04}-{m:02}-{d:02}")
}

/// Monday of the week a day belongs to (1970-01-01 was a Thursday).
fn monday(day: i64) -> i64 {
    day - (day + 3).rem_euclid(7)
}

/// First day of the month `back` months before the month of `day`.
fn month_start(day: i64, back: i64) -> i64 {
    let (y, m, _) = civil_from_days(day);
    let n = y * 12 + (m - 1) - back;
    days_from_civil(n.div_euclid(12), n.rem_euclid(12) + 1, 1)
}

fn now() -> f64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0.0, |d| d.as_secs_f64())
}

async fn require_profile(state: &AppState, profile_id: i64) -> ApiResult<()> {
    if store::profile_settings(&state.db, profile_id).await?.is_none() {
        return Err(ApiError::not_found("This profile"));
    }
    Ok(())
}

async fn runs(state: &AppState, profile_id: i64, from: f64, to: f64, tag: Option<&str>) -> anyhow::Result<Vec<Run>> {
    let rows = sqlx::query(
        "SELECT a.id, a.title, a.start_time, a.distance, a.moving_time, a.gain, a.avg_hr, a.load FROM activities a
          WHERE a.profile_id = ?1 AND a.start_time >= ?2 AND a.start_time < ?3
            AND (?4 IS NULL OR EXISTS (SELECT 1 FROM activity_tags x JOIN tags t ON t.id = x.tag_id
                                       WHERE x.activity_id = a.id AND t.name = ?4))
          ORDER BY a.start_time",
    )
    .bind(profile_id)
    .bind(from)
    .bind(to)
    .bind(tag)
    .fetch_all(&state.db)
    .await?;
    Ok(rows
        .iter()
        .map(|r| Run {
            id: r.get("id"),
            title: r.get("title"),
            start: r.get("start_time"),
            distance: r.get("distance"),
            moving: r.get("moving_time"),
            gain: r.get("gain"),
            hr: r.get("avg_hr"),
            load: r.get("load"),
        })
        .collect())
}

#[derive(Deserialize)]
pub struct CalendarQuery {
    pub year: Option<i64>,
    pub tz_offset_minutes: Option<i32>,
}

/// `GET /profiles/{id}/stats/calendar`: one entry per day with a run in the year, and the year's totals.
pub async fn calendar(
    State(state): State<AppState>,
    Path(profile_id): Path<i64>,
    Query(q): Query<CalendarQuery>,
) -> ApiResult<Json<Value>> {
    require_profile(&state, profile_id).await?;
    let off = offset(q.tz_offset_minutes);
    let year = q.year.unwrap_or_else(|| civil_from_days(local_day(now(), off)).0);
    if !(1970..=9999).contains(&year) {
        return Err(ApiError::bad_request("The year is not valid."));
    }
    let (first, next) = (days_from_civil(year, 1, 1), days_from_civil(year + 1, 1, 1));
    let list = runs(&state, profile_id, day_start(first, off), day_start(next, off), None).await?;

    let mut days: Vec<Value> = Vec::new();
    let mut i = 0;
    while i < list.len() {
        let day = local_day(list[i].start, off);
        let same: Vec<&Run> = list[i..].iter().take_while(|r| local_day(r.start, off) == day).collect();
        i += same.len();
        days.push(json!({
            "date": date(day),
            "distance": same.iter().map(|r| r.distance).sum::<f64>(),
            "moving_time": same.iter().map(|r| r.moving).sum::<f64>(),
            "load": same.iter().filter_map(|r| r.load).sum::<f64>(),
            "activities": same.iter().map(|r| json!({ "id": r.id, "title": r.title, "distance": r.distance })).collect::<Vec<_>>(),
        }));
    }
    Ok(Json(json!({
        "year": year,
        "days": days,
        "totals": {
            "runs": list.len(),
            "active_days": days.len(),
            "distance": list.iter().map(|r| r.distance).sum::<f64>(),
            "moving_time": list.iter().map(|r| r.moving).sum::<f64>(),
            "gain": list.iter().filter_map(|r| r.gain).sum::<f64>(),
        },
    })))
}

#[derive(Deserialize)]
pub struct VolumeQuery {
    /// `week` (default) or `month`.
    pub unit: Option<String>,
    /// How many periods, ending with the one in progress.
    pub count: Option<i64>,
    /// The instant "now" is taken to be (Unix seconds); defaults to the server clock.
    pub until: Option<f64>,
    pub tz_offset_minutes: Option<i32>,
}

/// Start days of `count` consecutive periods ending with the one holding `today`, plus the day after the last.
fn period_starts(monthly: bool, today: i64, count: i64) -> Vec<i64> {
    (0..=count)
        .rev()
        .map(|back| {
            if monthly {
                month_start(today, back - 1)
            } else {
                monday(today) - 7 * (back - 1)
            }
        })
        .collect()
}

/// `GET /profiles/{id}/stats/volume`: totals per week (Monday first) or per month, empty periods included.
pub async fn volume(State(state): State<AppState>, Path(profile_id): Path<i64>, Query(q): Query<VolumeQuery>) -> ApiResult<Json<Value>> {
    require_profile(&state, profile_id).await?;
    let monthly = match q.unit.as_deref() {
        None | Some("week") => false,
        Some("month") => true,
        Some(_) => return Err(ApiError::bad_request("The unit must be \"week\" or \"month\".")),
    };
    let off = offset(q.tz_offset_minutes);
    let count = q.count.unwrap_or(DEFAULT_PERIODS).clamp(1, MAX_PERIODS);
    let starts = period_starts(monthly, local_day(q.until.unwrap_or_else(now), off), count);
    let list = runs(
        &state,
        profile_id,
        day_start(starts[0], off),
        day_start(starts[starts.len() - 1], off),
        None,
    )
    .await?;

    let periods: Vec<Value> = starts
        .windows(2)
        .enumerate()
        .map(|(i, w)| {
            let inside: Vec<&Run> = list.iter().filter(|r| (w[0]..w[1]).contains(&local_day(r.start, off))).collect();
            json!({
                "start": date(w[0]),
                "runs": inside.len(),
                "distance": inside.iter().map(|r| r.distance).sum::<f64>(),
                "moving_time": inside.iter().map(|r| r.moving).sum::<f64>(),
                "gain": inside.iter().filter_map(|r| r.gain).sum::<f64>(),
                "current": i as i64 == count - 1,
            })
        })
        .collect();
    Ok(Json(json!({ "unit": if monthly { "month" } else { "week" }, "periods": periods })))
}

#[derive(Deserialize)]
pub struct ProgressQuery {
    pub months: Option<i64>,
    pub tag: Option<String>,
    pub until: Option<f64>,
    pub tz_offset_minutes: Option<i32>,
}

/// `GET /profiles/{id}/stats/progress`: average speed, longest run and average heart rate per month.
/// A month without data for a measure carries `null`, never zero.
pub async fn progress(
    State(state): State<AppState>,
    Path(profile_id): Path<i64>,
    Query(q): Query<ProgressQuery>,
) -> ApiResult<Json<Value>> {
    require_profile(&state, profile_id).await?;
    let off = offset(q.tz_offset_minutes);
    let count = q.months.unwrap_or(DEFAULT_PERIODS).clamp(1, MAX_PERIODS);
    let tag = q.tag.as_deref().map(str::trim).filter(|t| !t.is_empty());
    let starts = period_starts(true, local_day(q.until.unwrap_or_else(now), off), count);
    let list = runs(
        &state,
        profile_id,
        day_start(starts[0], off),
        day_start(starts[starts.len() - 1], off),
        tag,
    )
    .await?;

    let months: Vec<Value> = starts
        .windows(2)
        .map(|w| {
            let inside: Vec<&Run> = list.iter().filter(|r| (w[0]..w[1]).contains(&local_day(r.start, off))).collect();
            let (dist, moving): (f64, f64) = inside.iter().fold((0.0, 0.0), |a, r| (a.0 + r.distance, a.1 + r.moving));
            // Heart rate is averaged over the time of the runs that carry it.
            let (hr_sum, hr_time) = inside
                .iter()
                .filter_map(|r| Some((r.hr? * r.moving, r.moving)))
                .fold((0.0, 0.0), |a, v| (a.0 + v.0, a.1 + v.1));
            json!({
                "month": date(w[0])[..7],
                "runs": inside.len(),
                "avg_speed": (moving > 0.0).then(|| dist / moving),
                "longest": inside.iter().map(|r| r.distance).reduce(f64::max),
                "avg_hr": (hr_time > 0.0).then(|| hr_sum / hr_time),
            })
        })
        .collect();
    Ok(Json(json!({ "months": months })))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn weeks_start_on_monday() {
        // 2026-11-16 is a Monday.
        let mon = days_from_civil(2026, 11, 16);
        assert_eq!(monday(mon), mon);
        assert_eq!(monday(mon + 6), mon);
        assert_eq!(monday(mon - 1), mon - 7);
    }

    #[test]
    fn months_step_back_across_years() {
        let d = days_from_civil(2026, 2, 14);
        assert_eq!(date(month_start(d, 0)), "2026-02-01");
        assert_eq!(date(month_start(d, 2)), "2025-12-01");
        assert_eq!(date(month_start(d, -1)), "2026-03-01");
    }

    #[test]
    fn local_day_follows_the_offset() {
        let t = (days_from_civil(2026, 11, 15) * DAY_S + 22 * 3600 + 30 * 60) as f64; // Sunday 22:30 UTC
        assert_eq!(date(local_day(t, 0)), "2026-11-15");
        assert_eq!(date(local_day(t, 120)), "2026-11-16");
    }
}
