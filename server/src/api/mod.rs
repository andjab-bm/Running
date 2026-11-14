//! REST API mounted under `/api/v1`.
use axum::{Json, Router, extract::State, http::StatusCode, routing::get};
use serde_json::{Value, json};

use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new().route("/health", get(health)).fallback(not_found)
}

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

/// Unknown API paths answer with JSON instead of falling through to the web app shell.
async fn not_found() -> (StatusCode, Json<Value>) {
    (
        StatusCode::NOT_FOUND,
        Json(json!({ "error": "not_found", "message": "No such API endpoint." })),
    )
}
