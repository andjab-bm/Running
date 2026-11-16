//! Household profiles, their settings, and copying or moving activities between them.
use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
};
use serde::{Deserialize, Deserializer};
use serde_json::{Value, json};
use sqlx::{Row, sqlite::SqliteRow};

use super::{ApiError, ApiResult};
use crate::{
    AppState,
    derive::{self, Job},
    store::{self, CopyOutcome, DEFAULT_HR_MAX, MoveOutcome},
};

/// `GET /profiles/{id}/derive-status`: how much background computation is left for a profile,
/// so that records, load and segments can say they are still being worked out.
pub async fn derive_status(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<Json<Value>> {
    if sqlx::query("SELECT 1 FROM profiles WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
        .is_none()
    {
        return Err(ApiError::not_found("This profile"));
    }
    let (pending, total) = derive::status(&state.db, id).await?;
    Ok(Json(json!({
        "pending_activities": pending,
        "total_activities": total,
        "busy": pending > 0 || state.deriver.queued() > 0,
    })))
}

pub const HR_MAX_RANGE: std::ops::RangeInclusive<i64> = 100..=230;
const MAX_NAME: usize = 24;
const PROFILE_COLUMNS: &str = "p.id, p.name, p.color, p.hr_max, p.hr_rest, p.zone_bounds, p.units, p.timezone,
    (SELECT COUNT(*) FROM activities a WHERE a.profile_id = p.id) AS activity_count";

fn invalid(message: impl Into<String>) -> ApiError {
    ApiError::new(StatusCode::UNPROCESSABLE_ENTITY, "invalid", message)
}

fn conflict(code: &'static str, message: impl Into<String>) -> ApiError {
    ApiError::new(StatusCode::CONFLICT, code, message)
}

fn profile_json(r: &SqliteRow) -> Value {
    let hr_max: Option<i64> = r.get("hr_max");
    let custom: Option<[f64; 6]> = r
        .get::<Option<String>, _>("zone_bounds")
        .and_then(|j| serde_json::from_str(&j).ok());
    let bounds = custom.unwrap_or_else(|| crate::pipeline::ZoneConfig::from_max(hr_max.map_or(DEFAULT_HR_MAX, |v| v as f64)).bounds);
    json!({
        "id": r.get::<i64, _>("id"),
        "name": r.get::<String, _>("name"),
        "color": r.get::<String, _>("color"),
        "hr_max": hr_max,
        "hr_rest": r.get::<Option<i64>, _>("hr_rest"),
        "units": r.get::<String, _>("units"),
        "timezone": r.get::<Option<String>, _>("timezone"),
        "activity_count": r.get::<i64, _>("activity_count"),
        "zones": {
            "bounds": bounds,
            "custom": custom.is_some(),
            // True until the athlete sets a maximum heart rate or custom zones.
            "uses_default_hr_max": hr_max.is_none() && custom.is_none(),
            "default_hr_max": DEFAULT_HR_MAX,
        },
    })
}

async fn load(state: &AppState, id: i64) -> ApiResult<Value> {
    sqlx::query(sqlx::AssertSqlSafe(format!(
        "SELECT {PROFILE_COLUMNS} FROM profiles p WHERE p.id = ?"
    )))
    .bind(id)
    .fetch_optional(&state.db)
    .await?
    .map(|r| profile_json(&r))
    .ok_or_else(|| ApiError::not_found("This profile"))
}

/// `GET /profiles`
pub async fn list(State(state): State<AppState>) -> ApiResult<Json<Value>> {
    let rows = sqlx::query(sqlx::AssertSqlSafe(format!(
        "SELECT {PROFILE_COLUMNS} FROM profiles p ORDER BY p.id"
    )))
    .fetch_all(&state.db)
    .await?;
    Ok(Json(json!({ "profiles": rows.iter().map(profile_json).collect::<Vec<_>>() })))
}

/// `GET /profiles/{id}`
pub async fn detail(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<Json<Value>> {
    Ok(Json(load(&state, id).await?))
}

fn clean_name(name: &str) -> ApiResult<String> {
    let name = name.trim();
    if name.is_empty() || name.chars().count() > MAX_NAME {
        return Err(invalid(format!("The name must have between 1 and {MAX_NAME} characters.")));
    }
    Ok(name.to_string())
}

fn clean_color(color: &str) -> ApiResult<String> {
    let ok = color.len() == 7 && color.starts_with('#') && color[1..].chars().all(|c| c.is_ascii_hexdigit());
    if ok {
        Ok(color.to_uppercase())
    } else {
        Err(invalid("The colour must look like #B9AFFF."))
    }
}

async fn name_taken(state: &AppState, name: &str, except: Option<i64>) -> ApiResult<bool> {
    Ok(sqlx::query("SELECT 1 FROM profiles WHERE name = ? AND id IS NOT ?")
        .bind(name)
        .bind(except)
        .fetch_optional(&state.db)
        .await?
        .is_some())
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct NewProfile {
    pub name: String,
    pub color: Option<String>,
}

/// `POST /profiles`: no password, anyone on the network can add a profile.
pub async fn create(State(state): State<AppState>, Json(p): Json<NewProfile>) -> ApiResult<(StatusCode, Json<Value>)> {
    let name = clean_name(&p.name)?;
    if name_taken(&state, &name, None).await? {
        return Err(conflict("name_taken", "A profile with this name already exists."));
    }
    let color = match &p.color {
        Some(c) => clean_color(c)?,
        None => {
            // Rotate through the approved avatar colours.
            const PALETTE: [&str; 6] = ["#B9AFFF", "#F7AFC3", "#8FE0BB", "#FFC39C", "#9CCBF7", "#EFD877"];
            let n: i64 = sqlx::query("SELECT COUNT(*) FROM profiles").fetch_one(&state.db).await?.get(0);
            PALETTE[n as usize % PALETTE.len()].to_string()
        }
    };
    let id = sqlx::query("INSERT INTO profiles (name, color) VALUES (?, ?)")
        .bind(&name)
        .bind(&color)
        .execute(&state.db)
        .await?
        .last_insert_rowid();
    Ok((StatusCode::CREATED, Json(load(&state, id).await?)))
}

/// Distinguishes a field set to `null` (clear it) from a field left out (keep it).
fn nullable<'de, D: Deserializer<'de>, T: Deserialize<'de>>(d: D) -> Result<Option<Option<T>>, D::Error> {
    Option::<T>::deserialize(d).map(Some)
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ProfileChanges {
    pub name: Option<String>,
    pub color: Option<String>,
    #[serde(default, deserialize_with = "nullable")]
    pub hr_max: Option<Option<i64>>,
    #[serde(default, deserialize_with = "nullable")]
    pub hr_rest: Option<Option<i64>>,
    /// Six increasing bpm values (lower bound of zones 1–5, then the maximum); `null` restores the defaults.
    #[serde(default, deserialize_with = "nullable")]
    pub zone_bounds: Option<Option<Vec<f64>>>,
    pub units: Option<String>,
    #[serde(default, deserialize_with = "nullable")]
    pub timezone: Option<Option<String>>,
}

/// `PUT /profiles/{id}` (also `PATCH`): changes the given settings and leaves the others alone.
pub async fn update(State(state): State<AppState>, Path(id): Path<i64>, Json(c): Json<ProfileChanges>) -> ApiResult<Json<Value>> {
    let Some(row) = sqlx::query("SELECT name, color, hr_max, hr_rest, zone_bounds, units, timezone FROM profiles WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
    else {
        return Err(ApiError::not_found("This profile"));
    };
    let name = match &c.name {
        Some(n) => clean_name(n)?,
        None => row.get("name"),
    };
    if c.name.is_some() && name_taken(&state, &name, Some(id)).await? {
        return Err(conflict("name_taken", "A profile with this name already exists."));
    }
    let color = match &c.color {
        Some(v) => clean_color(v)?,
        None => row.get("color"),
    };
    let hr_max: Option<i64> = c.hr_max.unwrap_or_else(|| row.get("hr_max"));
    if hr_max.is_some_and(|m| !HR_MAX_RANGE.contains(&m)) {
        return Err(invalid(format!(
            "The maximum heart rate must be between {} and {} bpm.",
            HR_MAX_RANGE.start(),
            HR_MAX_RANGE.end()
        )));
    }
    let hr_rest: Option<i64> = c.hr_rest.unwrap_or_else(|| row.get("hr_rest"));
    if hr_rest.is_some_and(|r| r < 25 || r as f64 >= hr_max.map_or(DEFAULT_HR_MAX, |m| m as f64)) {
        return Err(invalid(
            "The resting heart rate must be at least 25 bpm and below the maximum heart rate.",
        ));
    }
    let old_bounds: Option<String> = row.get("zone_bounds");
    let zone_bounds: Option<String> = match &c.zone_bounds {
        None => old_bounds.clone(),
        Some(None) => None,
        Some(Some(b)) => {
            if b.len() != 6 || b.iter().any(|v| !v.is_finite() || *v < 30.0 || *v > 250.0) || b.windows(2).any(|w| w[0] >= w[1]) {
                return Err(invalid("Zones need six heart-rate values in strictly increasing order."));
            }
            Some(serde_json::to_string(b)?)
        }
    };
    let units = match c.units.as_deref() {
        None => row.get::<String, _>("units"),
        Some(u @ ("metric" | "imperial")) => u.to_string(),
        Some(_) => return Err(invalid("Units must be \"metric\" or \"imperial\".")),
    };
    let timezone: Option<String> = match &c.timezone {
        None => row.get("timezone"),
        Some(tz) => tz.as_ref().map(|t| t.trim().to_string()).filter(|t| !t.is_empty()),
    };
    if timezone
        .as_ref()
        .is_some_and(|t| t.len() > 64 || !t.chars().all(|ch| ch.is_ascii_alphanumeric() || "/_+-".contains(ch)))
    {
        return Err(invalid("The time zone must be an IANA name such as Europe/Paris."));
    }

    let derived_changed =
        hr_max != row.get::<Option<i64>, _>("hr_max") || zone_bounds != old_bounds || units != row.get::<String, _>("units");
    sqlx::query("UPDATE profiles SET name = ?, color = ?, hr_max = ?, hr_rest = ?, zone_bounds = ?, units = ?, timezone = ? WHERE id = ?")
        .bind(&name)
        .bind(&color)
        .bind(hr_max)
        .bind(hr_rest)
        .bind(&zone_bounds)
        .bind(&units)
        .bind(&timezone)
        .bind(id)
        .execute(&state.db)
        .await?;
    if derived_changed {
        // Zone times, effort and splits depend on these settings.
        store::recompute_profile(&state.db, id).await?;
        // Load, and everything built on it, follows in the background.
        derive::invalidate_profile(&state.db, id).await?;
        state.deriver.enqueue(Job::Profile(id));
    }
    Ok(Json(load(&state, id).await?))
}

/// `DELETE /profiles/{id}`: removes the profile and all its activities. The last profile cannot be deleted.
pub async fn delete(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<StatusCode> {
    let count: i64 = sqlx::query("SELECT COUNT(*) FROM profiles").fetch_one(&state.db).await?.get(0);
    if sqlx::query("SELECT 1 FROM profiles WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
        .is_none()
    {
        return Err(ApiError::not_found("This profile"));
    }
    if count <= 1 {
        return Err(conflict("last_profile", "The last profile cannot be deleted."));
    }
    store::delete_profile(&state.db, &state.config.data_dir, id).await?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Transfer {
    pub profile_id: i64,
    /// Only for copies: keep the recorder's heart rate. Defaults to false.
    #[serde(default)]
    pub keep_hr: bool,
}

/// `POST /activities/{id}/copy`: attributes a shared run to another profile as an independent activity.
pub async fn copy(State(state): State<AppState>, Path(id): Path<i64>, Json(t): Json<Transfer>) -> ApiResult<(StatusCode, Json<Value>)> {
    match store::copy_activity(&state.db, id, t.profile_id, t.keep_hr).await? {
        CopyOutcome::Created(new_id) => {
            state.deriver.enqueue(Job::Activity(new_id));
            Ok((StatusCode::CREATED, Json(json!({ "activity_id": new_id }))))
        }
        CopyOutcome::AlreadyPresent(existing) => Ok((
            StatusCode::CONFLICT,
            Json(json!({ "error": "already_present", "message": "This run is already in that profile.", "activity_id": existing })),
        )),
        CopyOutcome::SourceMissing => Err(ApiError::not_found("This activity")),
        CopyOutcome::TargetMissing => Err(ApiError::not_found("The target profile")),
        CopyOutcome::SameProfile => Err(invalid("The activity already belongs to that profile.")),
    }
}

/// `POST /activities/{id}/move`: for a run imported into the wrong profile.
pub async fn move_to(State(state): State<AppState>, Path(id): Path<i64>, Json(t): Json<Transfer>) -> ApiResult<Json<Value>> {
    let source: Option<i64> = sqlx::query("SELECT profile_id FROM activities WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
        .map(|r| r.get(0));
    match store::move_activity(&state.db, id, t.profile_id).await? {
        MoveOutcome::Moved => {
            // The run now counts for the target profile, and no longer for the one it left.
            derive::invalidate_activity(&state.db, id).await?;
            state.deriver.enqueue(Job::Activity(id));
            if let Some(p) = source {
                state.deriver.enqueue(Job::Profile(p));
            }
            Ok(Json(json!({ "activity_id": id, "profile_id": t.profile_id })))
        }
        MoveOutcome::SourceMissing => Err(ApiError::not_found("This activity")),
        MoveOutcome::TargetMissing => Err(ApiError::not_found("The target profile")),
        MoveOutcome::SameProfile => Err(invalid("The activity already belongs to that profile.")),
    }
}
