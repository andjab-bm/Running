//! Self-hosted running tracker: HTTP API and embedded web app.
pub mod api;
pub mod config;
pub mod db;
pub mod pipeline;
pub mod store;
pub mod web;

use std::sync::Arc;

use axum::Router;
use sqlx::SqlitePool;
use tokio::sync::Semaphore;

use config::Config;

/// Files parsed and analysed at the same time during an import.
const IMPORT_CONCURRENCY: usize = 2;

/// State shared by every request handler.
#[derive(Clone)]
pub struct AppState {
    pub db: SqlitePool,
    pub config: Config,
    /// Bounds CPU-heavy import work so a large batch cannot exhaust a Raspberry Pi.
    pub import_slots: Arc<Semaphore>,
}

impl AppState {
    pub fn new(db: SqlitePool, config: Config) -> Self {
        Self {
            db,
            config,
            import_slots: Arc::new(Semaphore::new(IMPORT_CONCURRENCY)),
        }
    }
}

/// Builds the whole application: API under `/api/v1`, web app everywhere else.
pub fn app(state: AppState) -> Router {
    Router::new().nest("/api/v1", api::router()).fallback(web::serve).with_state(state)
}
