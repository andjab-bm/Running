//! Self-hosted running tracker: HTTP API and embedded web app.
pub mod api;
pub mod config;
pub mod db;
pub mod web;

use axum::Router;
use sqlx::SqlitePool;

use config::Config;

/// State shared by every request handler.
#[derive(Clone)]
pub struct AppState {
    pub db: SqlitePool,
    pub config: Config,
}

/// Builds the whole application: API under `/api/v1`, web app everywhere else.
pub fn app(state: AppState) -> Router {
    Router::new().nest("/api/v1", api::router()).fallback(web::serve).with_state(state)
}
