//! Keeps `docs/api/openapi.json` in step with the API.
//!
//! To refresh the committed file after an API change:
//! `UPDATE_OPENAPI=1 cargo test --test openapi`
mod common;

use axum::http::{Method, StatusCode};
use common::*;
use running_server::api::openapi::{ROUTES, document, document_text};

#[test]
fn committed_document_is_up_to_date() {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../docs/api/openapi.json");
    if std::env::var_os("UPDATE_OPENAPI").is_some() {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(&path, document_text()).unwrap();
    }
    let committed = std::fs::read_to_string(&path).unwrap_or_default().replace("\r\n", "\n");
    assert!(
        committed == document_text(),
        "docs/api/openapi.json is out of date: run `UPDATE_OPENAPI=1 cargo test --test openapi`"
    );
}

#[test]
fn every_listed_route_is_documented_with_responses() {
    let doc = document();
    for (method, path, _) in ROUTES {
        let op = &doc["paths"][*path][*method];
        assert!(op["summary"].is_string(), "{method} {path} has no summary");
        assert!(
            op["responses"].as_object().is_some_and(|r| !r.is_empty()),
            "{method} {path} has no responses"
        );
    }
    // Every schema reference resolves.
    let text = document_text();
    for part in text.split("#/components/schemas/").skip(1) {
        let name = part.split('"').next().unwrap();
        assert!(doc["components"]["schemas"][name].is_object(), "unknown schema {name}");
    }
}

/// A documented route that the router does not serve would answer with the API fallback.
#[tokio::test]
async fn every_listed_route_exists_in_the_router() {
    let c = ctx().await;
    for (method, path, _) in ROUTES {
        let url = format!("/api/v1{}", path.replace("{profile_id}", "424242").replace("{id}", "424242"));
        let method = Method::from_bytes(method.to_uppercase().as_bytes()).unwrap();
        let (status, _, body) = send_raw(&c, method.clone(), &url, None, &[("x-requested-with", "test")]).await;
        let text = String::from_utf8_lossy(&body);
        assert!(!text.contains("No such API endpoint"), "{method} {path} is not routed");
        assert_ne!(status, StatusCode::METHOD_NOT_ALLOWED, "{method} {path}");
    }
}
