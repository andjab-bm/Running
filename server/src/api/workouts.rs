//! Structured sessions of a profile: library, editing, copying, marking for the phone.
use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
};
use serde::Deserialize;
use serde_json::{Value, json};
use sqlx::{Row, sqlite::SqliteRow};

use super::{ApiError, ApiResult, plan};
use crate::{
    AppState,
    pipeline::workout::{Item, MAX_NAME, MAX_NOTE, validate},
    store,
};

const COLUMNS: &str = "id, profile_id, name, note, steps, on_phone, version, updated_at";

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

fn clean_note(note: &str) -> ApiResult<String> {
    let note = note.trim();
    if note.chars().count() > MAX_NOTE {
        return Err(invalid(format!("The note is limited to {MAX_NOTE} characters.")));
    }
    Ok(note.to_string())
}

/// Validates the steps and returns them as stored, with the number of steps once unfolded.
fn clean_steps(steps: &[Item]) -> ApiResult<String> {
    validate(steps).map_err(invalid)?;
    Ok(serde_json::to_string(steps)?)
}

async fn to_json(state: &AppState, r: &SqliteRow) -> anyhow::Result<Value> {
    let (id, profile_id, version): (i64, i64, i64) = (r.get("id"), r.get("profile_id"), r.get("version"));
    let on_phone = r.get::<i64, _>("on_phone") != 0;
    let steps: Vec<Item> = serde_json::from_str(&r.get::<String, _>("steps"))?;
    Ok(json!({
        "id": id,
        "profile_id": profile_id,
        "name": r.get::<String, _>("name"),
        "note": r.get::<String, _>("note"),
        "steps": steps,
        // Steps in the order they are run, repeats expanded.
        "unfolded": crate::pipeline::workout::unfold(&steps),
        "on_phone": on_phone,
        "version": version,
        "updated_at": r.get::<f64, _>("updated_at"),
        "delivery": plan::delivery(&state.db, profile_id, "workout", id, on_phone, version).await?,
    }))
}

async fn load(state: &AppState, id: i64) -> ApiResult<Value> {
    let Some(row) = sqlx::query(sqlx::AssertSqlSafe(format!("SELECT {COLUMNS} FROM workouts WHERE id = ?")))
        .bind(id)
        .fetch_optional(&state.db)
        .await?
    else {
        return Err(ApiError::not_found("This session"));
    };
    Ok(to_json(state, &row).await?)
}

/// `GET /profiles/{id}/workouts`
pub async fn list(State(state): State<AppState>, Path(profile_id): Path<i64>) -> ApiResult<Json<Value>> {
    if store::profile_settings(&state.db, profile_id).await?.is_none() {
        return Err(ApiError::not_found("This profile"));
    }
    let rows = sqlx::query(sqlx::AssertSqlSafe(format!(
        "SELECT {COLUMNS} FROM workouts WHERE profile_id = ? ORDER BY updated_at DESC, id DESC"
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

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct NewWorkout {
    pub name: String,
    #[serde(default)]
    pub note: String,
    pub steps: Vec<Item>,
    #[serde(default)]
    pub on_phone: bool,
}

/// `POST /profiles/{id}/workouts`
pub async fn create(
    State(state): State<AppState>,
    Path(profile_id): Path<i64>,
    Json(w): Json<NewWorkout>,
) -> ApiResult<(StatusCode, Json<Value>)> {
    if store::profile_settings(&state.db, profile_id).await?.is_none() {
        return Err(ApiError::not_found("This profile"));
    }
    let id = sqlx::query("INSERT INTO workouts (profile_id, name, note, steps, on_phone) VALUES (?, ?, ?, ?, ?)")
        .bind(profile_id)
        .bind(clean_name(&w.name)?)
        .bind(clean_note(&w.note)?)
        .bind(clean_steps(&w.steps)?)
        .bind(w.on_phone)
        .execute(&state.db)
        .await?
        .last_insert_rowid();
    Ok((StatusCode::CREATED, Json(load(&state, id).await?)))
}

/// `GET /workouts/{id}`
pub async fn detail(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<Json<Value>> {
    Ok(Json(load(&state, id).await?))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Changes {
    pub name: Option<String>,
    pub note: Option<String>,
    pub steps: Option<Vec<Item>>,
    pub on_phone: Option<bool>,
}

/// `PUT /workouts/{id}`: changes the given fields. A change of content bumps the version,
/// so a phone holding the previous one is "waiting" again; marking or unmarking does not.
pub async fn update(State(state): State<AppState>, Path(id): Path<i64>, Json(c): Json<Changes>) -> ApiResult<Json<Value>> {
    let Some(row) = sqlx::query("SELECT name, note, steps, on_phone FROM workouts WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
    else {
        return Err(ApiError::not_found("This session"));
    };
    let (old_name, old_note, old_steps): (String, String, String) = (row.get("name"), row.get("note"), row.get("steps"));
    let name = match &c.name {
        Some(n) => clean_name(n)?,
        None => old_name.clone(),
    };
    let note = match &c.note {
        Some(n) => clean_note(n)?,
        None => old_note.clone(),
    };
    let steps = match &c.steps {
        Some(s) => clean_steps(s)?,
        None => old_steps.clone(),
    };
    let changed = name != old_name || note != old_note || steps != old_steps;
    sqlx::query(
        "UPDATE workouts SET name = ?, note = ?, steps = ?, on_phone = ?, version = version + ?,
                updated_at = CASE WHEN ? THEN unixepoch('subsec') ELSE updated_at END WHERE id = ?",
    )
    .bind(&name)
    .bind(&note)
    .bind(&steps)
    .bind(c.on_phone.unwrap_or(row.get::<i64, _>("on_phone") != 0))
    .bind(i64::from(changed))
    .bind(changed)
    .bind(id)
    .execute(&state.db)
    .await?;
    Ok(Json(load(&state, id).await?))
}

/// `DELETE /workouts/{id}`: activities run with the session keep their own copy of its steps.
pub async fn delete(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<StatusCode> {
    let gone = sqlx::query("DELETE FROM workouts WHERE id = ?")
        .bind(id)
        .execute(&state.db)
        .await?
        .rows_affected();
    if gone == 0 {
        return Err(ApiError::not_found("This session"));
    }
    sqlx::query("DELETE FROM plan_receipts WHERE kind = 'workout' AND item_id = ?")
        .bind(id)
        .execute(&state.db)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CopyTo {
    pub profile_id: i64,
    /// Name of the copy; defaults to the original's.
    pub name: Option<String>,
}

/// `POST /workouts/{id}/copy`: an independent copy, in the same profile (duplicate) or in another.
/// A heart-rate zone target keeps its zone number, which then refers to the other profile's zones.
pub async fn copy(State(state): State<AppState>, Path(id): Path<i64>, Json(c): Json<CopyTo>) -> ApiResult<(StatusCode, Json<Value>)> {
    let Some(row) = sqlx::query("SELECT name, note, steps FROM workouts WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?
    else {
        return Err(ApiError::not_found("This session"));
    };
    if store::profile_settings(&state.db, c.profile_id).await?.is_none() {
        return Err(ApiError::not_found("The target profile"));
    }
    let name = match &c.name {
        Some(n) => clean_name(n)?,
        None => row.get("name"),
    };
    let new_id = sqlx::query("INSERT INTO workouts (profile_id, name, note, steps) VALUES (?, ?, ?, ?)")
        .bind(c.profile_id)
        .bind(name)
        .bind(row.get::<String, _>("note"))
        .bind(row.get::<String, _>("steps"))
        .execute(&state.db)
        .await?
        .last_insert_rowid();
    Ok((StatusCode::CREATED, Json(load(&state, new_id).await?)))
}
