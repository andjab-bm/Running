//! SQLite connection pool living in the data directory.
use std::path::Path;

use sqlx::sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePool, SqlitePoolOptions};

pub async fn open(data_dir: &Path) -> anyhow::Result<SqlitePool> {
    tokio::fs::create_dir_all(data_dir).await?;
    let options = SqliteConnectOptions::new()
        .filename(data_dir.join("running.db"))
        .create_if_missing(true)
        .journal_mode(SqliteJournalMode::Wal)
        .foreign_keys(true);
    // A small pool is plenty for a household and keeps memory low on a Raspberry Pi.
    Ok(SqlitePoolOptions::new().max_connections(4).connect_with(options).await?)
}
