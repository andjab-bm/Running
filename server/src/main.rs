use running_server::{AppState, app, config::Config, db};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let config = Config::from_env()?;
    // `running-server healthcheck` asks the running instance for its health and exits with 0 or 1.
    // The container image has no shell or HTTP client, so the binary checks itself.
    if std::env::args().nth(1).as_deref() == Some("healthcheck") {
        std::process::exit(if healthy(config.port).await { 0 } else { 1 });
    }

    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")))
        .init();

    let db = db::open(&config.data_dir).await?;
    let listener = tokio::net::TcpListener::bind(("0.0.0.0", config.port)).await?;
    tracing::info!(port = config.port, data_dir = %config.data_dir.display(), "running-server {} listening", env!("CARGO_PKG_VERSION"));

    axum::serve(listener, app(AppState::new(db, config)))
        .with_graceful_shutdown(shutdown())
        .await?;
    Ok(())
}

/// True when the instance listening on `port` answers its health route with a success status.
async fn healthy(port: u16) -> bool {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let check = async {
        let mut stream = tokio::net::TcpStream::connect(("127.0.0.1", port)).await.ok()?;
        stream
            .write_all(b"GET /api/v1/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            .await
            .ok()?;
        let mut head = [0u8; 12];
        stream.read_exact(&mut head).await.ok()?;
        Some(head.starts_with(b"HTTP/1.1 2"))
    };
    matches!(tokio::time::timeout(std::time::Duration::from_secs(3), check).await, Ok(Some(true)))
}

/// Resolves on Ctrl-C or, in a container, on the termination signal sent by `docker stop`.
async fn shutdown() {
    let interrupt = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    #[cfg(unix)]
    let terminate = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut signal) => {
                signal.recv().await;
            }
            Err(_) => std::future::pending::<()>().await,
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        _ = interrupt => {}
        _ = terminate => {}
    }
    tracing::info!("shutting down");
}
