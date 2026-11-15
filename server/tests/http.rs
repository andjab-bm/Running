use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use running_server::{AppState, app, config::Config, db};
use tower::ServiceExt;

async fn test_app() -> (axum::Router, tempfile::TempDir) {
    let dir = tempfile::tempdir().unwrap();
    let mut config = Config::from_lookup(|_| None).unwrap();
    config.data_dir = dir.path().to_path_buf();
    let db = db::open(&config.data_dir).await.unwrap();
    (app(AppState::new(db, config)), dir)
}

async fn get(app: &axum::Router, path: &str) -> (StatusCode, Vec<u8>) {
    let res = app.clone().oneshot(Request::get(path).body(Body::empty()).unwrap()).await.unwrap();
    let status = res.status();
    (status, res.into_body().collect().await.unwrap().to_bytes().to_vec())
}

#[tokio::test]
async fn health_reports_status_and_version() {
    let (app, _dir) = test_app().await;
    let (status, body) = get(&app, "/api/v1/health").await;
    assert_eq!(status, StatusCode::OK);
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["status"], "ok");
    assert_eq!(json["database"], "ok");
    assert_eq!(json["version"], env!("CARGO_PKG_VERSION"));
}

#[tokio::test]
async fn database_file_is_created_in_the_data_directory() {
    let (_app, dir) = test_app().await;
    assert!(dir.path().join("running.db").exists());
}

#[tokio::test]
async fn unknown_api_path_is_a_json_404_not_the_web_shell() {
    let (app, _dir) = test_app().await;
    let (status, body) = get(&app, "/api/v1/nope").await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["error"], "not_found");
}

#[tokio::test]
async fn unknown_client_route_gets_the_same_response_as_the_root() {
    let (app, _dir) = test_app().await;
    let (root_status, root_body) = get(&app, "/").await;
    let (deep_status, deep_body) = get(&app, "/activities/42").await;
    assert_eq!(root_status, deep_status);
    assert_eq!(root_body, deep_body);
}
