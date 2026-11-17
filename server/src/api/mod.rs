//! REST API mounted under `/api/v1`.
mod activities;
pub mod guard;
mod imports;
mod load;
pub mod openapi;
mod plan;
mod profiles;
mod records;
mod routes;
mod segments;
mod stats;
mod workouts;

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post, put},
};
use serde_json::{Value, json};

use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/health", get(health))
        .route("/config", get(config))
        // Uploads are limited per file while streaming, not by a total body size,
        // so a whole history can be imported in one request.
        .route(
            "/profiles/{profile_id}/imports",
            post(imports::import).layer(DefaultBodyLimit::disable()),
        )
        .route("/profiles", get(profiles::list).post(profiles::create))
        .route(
            "/profiles/{profile_id}",
            get(profiles::detail)
                .put(profiles::update)
                .patch(profiles::update)
                .delete(profiles::delete),
        )
        .route("/profiles/{profile_id}/derive-status", get(profiles::derive_status))
        .route("/activities/{id}/copy", post(profiles::copy))
        .route("/activities/{id}/move", post(profiles::move_to))
        .route("/profiles/{profile_id}/activities", get(activities::list))
        .route("/activities", get(activities::household))
        .route("/profiles/{profile_id}/stats/calendar", get(stats::calendar))
        .route("/profiles/{profile_id}/stats/volume", get(stats::volume))
        .route("/profiles/{profile_id}/stats/progress", get(stats::progress))
        .route("/profiles/{profile_id}/workouts", get(workouts::list).post(workouts::create))
        .route(
            "/workouts/{id}",
            get(workouts::detail).put(workouts::update).delete(workouts::delete),
        )
        .route("/workouts/{id}/copy", post(workouts::copy))
        // Uploads are limited per file while streaming, as for activity imports.
        .route(
            "/profiles/{profile_id}/routes",
            get(routes::list).post(routes::import).layer(DefaultBodyLimit::disable()),
        )
        .route("/activities/{id}/route", post(routes::from_activity))
        .route("/routes/{id}", get(routes::detail).patch(routes::update).delete(routes::delete))
        .route("/routes/{id}/points", get(routes::points))
        .route("/routes/{id}/reverse", post(routes::reverse))
        .route("/routes/{id}/copy", post(routes::copy))
        .route("/routes/{id}/export.gpx", get(routes::export_gpx))
        .route("/profiles/{profile_id}/plan", get(plan::plan))
        .route("/profiles/{profile_id}/plan/receipt", put(plan::receipt))
        .route("/profiles/{profile_id}/segments", get(segments::list))
        .route("/segments/{id}", get(segments::detail).patch(segments::update))
        .route("/profiles/{profile_id}/load", get(load::load))
        .route("/profiles/{profile_id}/records", get(records::records))
        .route("/profiles/{profile_id}/predictions", get(records::predictions))
        .route("/profiles/{profile_id}/tags", get(activities::tags))
        .route(
            "/activities/{id}",
            get(activities::detail).patch(activities::update).delete(activities::delete),
        )
        .route("/activities/{id}/streams", get(activities::streams))
        .route("/activities/{id}/export.gpx", get(activities::export_gpx))
        .route("/activities/{id}/original", get(activities::original))
        .fallback(not_found)
        .layer(axum::middleware::from_fn(guard::same_site_writes))
}

/// An error answered as `{ "error": code, "message": text }`.
pub struct ApiError {
    pub status: StatusCode,
    pub code: &'static str,
    pub message: String,
}

impl ApiError {
    pub fn new(status: StatusCode, code: &'static str, message: impl Into<String>) -> Self {
        Self {
            status,
            code,
            message: message.into(),
        }
    }
    pub fn not_found(what: &str) -> Self {
        Self::new(StatusCode::NOT_FOUND, "not_found", format!("{what} does not exist."))
    }
    pub fn bad_request(message: impl Into<String>) -> Self {
        Self::new(StatusCode::BAD_REQUEST, "bad_request", message)
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.status, Json(json!({ "error": self.code, "message": self.message }))).into_response()
    }
}

/// Unexpected failures are logged in full and answered without internal detail.
impl<E: Into<anyhow::Error>> From<E> for ApiError {
    fn from(e: E) -> Self {
        let e: anyhow::Error = e.into();
        tracing::error!("request failed: {e:#}");
        Self::new(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal",
            "The server could not complete the request.",
        )
    }
}

pub type ApiResult<T> = Result<T, ApiError>;

/// Reports whether the service and its data store work. Discloses no user data.
async fn health(State(state): State<AppState>) -> (StatusCode, Json<Value>) {
    let db_ok = sqlx::query("SELECT 1").execute(&state.db).await.is_ok();
    let status = if db_ok { StatusCode::OK } else { StatusCode::SERVICE_UNAVAILABLE };
    (
        status,
        Json(json!({
            "status": if db_ok { "ok" } else { "degraded" },
            "version": env!("CARGO_PKG_VERSION"),
            "database": if db_ok { "ok" } else { "unreachable" },
        })),
    )
}

/// Settings the web app needs from the server. Empty map style URLs mean "no basemap".
async fn config(State(state): State<AppState>) -> Json<Value> {
    Json(json!({
        "version": env!("CARGO_PKG_VERSION"),
        "map_style_url": state.config.map_style_url,
        "map_style_url_dark": state.config.map_style_url_dark,
        "max_upload_bytes": state.config.max_upload_bytes,
    }))
}

/// Unknown API paths answer with JSON instead of falling through to the web app shell.
async fn not_found() -> ApiError {
    ApiError::new(StatusCode::NOT_FOUND, "not_found", "No such API endpoint.")
}
