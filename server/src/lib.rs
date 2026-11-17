//! Self-hosted running tracker: HTTP API and embedded web app.
// The OpenAPI description is one large `json!` literal.
#![recursion_limit = "512"]
pub mod api;
pub mod config;
pub mod db;
pub mod derive;
pub mod pipeline;
pub mod store;
pub mod web;

use std::sync::Arc;

use axum::Router;
use sqlx::SqlitePool;
use tokio::sync::Semaphore;

use config::Config;
use derive::{Deriver, Job};

/// Files parsed and analysed at the same time during an import.
const IMPORT_CONCURRENCY: usize = 2;

/// State shared by every request handler.
#[derive(Clone)]
pub struct AppState {
    pub db: SqlitePool,
    pub config: Config,
    /// Bounds CPU-heavy import work so a large batch cannot exhaust a Raspberry Pi.
    pub import_slots: Arc<Semaphore>,
    /// Hands background computation (records, load, segments) to the derive worker.
    pub deriver: Deriver,
}

impl AppState {
    /// Starts the derive worker and asks it to catch up on activities that predate it.
    /// Must be called inside a Tokio runtime.
    pub fn new(db: SqlitePool, config: Config) -> Self {
        let (deriver, worker) = Deriver::channel();
        tokio::spawn(worker.run(db.clone()));
        deriver.enqueue(Job::Backfill);
        Self::with_deriver(db, config, deriver)
    }

    /// For callers that run, or deliberately hold back, the worker themselves.
    pub fn with_deriver(db: SqlitePool, config: Config, deriver: Deriver) -> Self {
        Self {
            db,
            config,
            import_slots: Arc::new(Semaphore::new(IMPORT_CONCURRENCY)),
            deriver,
        }
    }
}

/// Builds the whole application: API under `/api/v1`, web app everywhere else.
pub fn app(state: AppState) -> Router {
    Router::new().nest("/api/v1", api::router()).fallback(web::serve).with_state(state)
}
