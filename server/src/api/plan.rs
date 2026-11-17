//! Delivery of sessions and routes to the phone.
//!
//! The server cannot reach a sleeping phone: the phone fetches the plan of its profile
//! when it syncs at home, then says what it holds. That receipt is what lets the web
//! show "on the phone since…" or "waiting for the phone".
use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
};
use serde::Deserialize;
use serde_json::{Value, json};
use sqlx::{Row, SqlitePool};

use super::{ApiError, ApiResult};
use crate::{AppState, store};

const MAX_DEVICE: usize = 64;
const MAX_ITEMS: usize = 1000;

/// Where an item stands with the phone.
///
/// - `none`: not marked for the phone;
/// - `waiting`: marked, and no phone holds this version yet;
/// - `delivered`: a phone holds this very version, since `delivered_at`.
pub async fn delivery(
    db: &SqlitePool,
    profile_id: i64,
    kind: &'static str,
    item_id: i64,
    on_phone: bool,
    version: i64,
) -> anyhow::Result<Value> {
    if !on_phone {
        return Ok(json!({ "state": "none", "delivered_at": null }));
    }
    let at: Option<f64> =
        sqlx::query("SELECT MAX(received_at) FROM plan_receipts WHERE profile_id = ? AND kind = ? AND item_id = ? AND version = ?")
            .bind(profile_id)
            .bind(kind)
            .bind(item_id)
            .bind(version)
            .fetch_one(db)
            .await?
            .get(0);
    Ok(json!({ "state": if at.is_some() { "delivered" } else { "waiting" }, "delivered_at": at }))
}

/// When a phone last fetched the plan of the profile.
pub async fn last_sync(db: &SqlitePool, profile_id: i64) -> anyhow::Result<Option<f64>> {
    Ok(sqlx::query("SELECT MAX(synced_at) FROM plan_syncs WHERE profile_id = ?")
        .bind(profile_id)
        .fetch_one(db)
        .await?
        .get(0))
}

async fn require_profile(state: &AppState, profile_id: i64) -> ApiResult<()> {
    if store::profile_settings(&state.db, profile_id).await?.is_none() {
        return Err(ApiError::not_found("This profile"));
    }
    Ok(())
}

/// `GET /profiles/{id}/plan`: everything marked for the phone. Sessions come whole;
/// routes come with their figures, their points being fetched only when the version is new.
pub async fn plan(State(state): State<AppState>, Path(profile_id): Path<i64>) -> ApiResult<Json<Value>> {
    require_profile(&state, profile_id).await?;
    let workouts: Vec<Value> =
        sqlx::query("SELECT id, version, name, note, steps FROM workouts WHERE profile_id = ? AND on_phone = 1 ORDER BY id")
            .bind(profile_id)
            .fetch_all(&state.db)
            .await?
            .iter()
            .map(|r| {
                json!({
                    "id": r.get::<i64, _>("id"),
                    "version": r.get::<i64, _>("version"),
                    "name": r.get::<String, _>("name"),
                    "note": r.get::<String, _>("note"),
                    "steps": serde_json::from_str::<Value>(&r.get::<String, _>("steps")).unwrap_or(Value::Null),
                })
            })
            .collect();
    let routes: Vec<Value> = sqlx::query(
        "SELECT id, version, name, distance, gain, loss, is_loop, has_elevation FROM routes WHERE profile_id = ? AND on_phone = 1 ORDER BY id",
    )
    .bind(profile_id)
    .fetch_all(&state.db)
    .await?
    .iter()
    .map(|r| {
        json!({
            "id": r.get::<i64, _>("id"),
            "version": r.get::<i64, _>("version"),
            "name": r.get::<String, _>("name"),
            "distance": r.get::<f64, _>("distance"),
            "gain": r.get::<Option<f64>, _>("gain"),
            "loss": r.get::<Option<f64>, _>("loss"),
            "is_loop": r.get::<i64, _>("is_loop") != 0,
            "has_elevation": r.get::<i64, _>("has_elevation") != 0,
        })
    })
    .collect();
    Ok(Json(json!({
        "workouts": workouts,
        "routes": routes,
        // The basemap of the phone's map screen comes from the same style as the web's.
        "map_style_url": state.config.map_style_url,
        "map_style_url_dark": state.config.map_style_url_dark,
    })))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ReceiptItem {
    pub kind: String,
    pub id: i64,
    pub version: i64,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Receipt {
    /// A name the phone gives itself; one phone, one name.
    pub device: String,
    pub items: Vec<ReceiptItem>,
}

/// `PUT /profiles/{id}/plan/receipt`: the phone states everything it now holds for the profile.
/// The statement replaces its previous one, so an item it dropped is no longer counted as delivered.
pub async fn receipt(State(state): State<AppState>, Path(profile_id): Path<i64>, Json(r): Json<Receipt>) -> ApiResult<StatusCode> {
    require_profile(&state, profile_id).await?;
    let invalid = |m: &str| ApiError::new(StatusCode::UNPROCESSABLE_ENTITY, "invalid", m);
    let device = r.device.trim();
    if device.is_empty() || device.chars().count() > MAX_DEVICE {
        return Err(invalid("The device name must have between 1 and 64 characters."));
    }
    if r.items.len() > MAX_ITEMS || r.items.iter().any(|i| !matches!(i.kind.as_str(), "workout" | "route")) {
        return Err(invalid("Items must be of kind \"workout\" or \"route\"."));
    }
    let mut tx = state.db.begin().await?;
    sqlx::query("DELETE FROM plan_receipts WHERE profile_id = ? AND device = ?")
        .bind(profile_id)
        .bind(device)
        .execute(&mut *tx)
        .await?;
    for item in &r.items {
        sqlx::query("INSERT OR REPLACE INTO plan_receipts (profile_id, kind, item_id, device, version) VALUES (?, ?, ?, ?, ?)")
            .bind(profile_id)
            .bind(&item.kind)
            .bind(item.id)
            .bind(device)
            .bind(item.version)
            .execute(&mut *tx)
            .await?;
    }
    sqlx::query("INSERT OR REPLACE INTO plan_syncs (profile_id, device, synced_at) VALUES (?, ?, unixepoch('subsec'))")
        .bind(profile_id)
        .bind(device)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}
