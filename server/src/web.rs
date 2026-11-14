//! Serves the web app, embedded in the binary at build time.
//!
//! Any path that is not a built asset gets the single-page-app shell, so
//! client-side routes such as `/activities/42` survive a page reload.
use axum::{
    body::Body,
    http::{StatusCode, Uri, header},
    response::{IntoResponse, Response},
};
use rust_embed::RustEmbed;

#[derive(RustEmbed)]
#[folder = "$CARGO_MANIFEST_DIR/../web/build"]
struct Assets;

/// Name of the fallback page produced by the SvelteKit static adapter.
const SHELL: &str = "200.html";

pub async fn serve(uri: Uri) -> Response {
    let path = uri.path().trim_start_matches('/');
    if !path.is_empty()
        && let Some(file) = Assets::get(path)
    {
        let mime = mime_guess::from_path(path).first_or_octet_stream();
        // Files under `_app/immutable` carry a content hash in their name.
        let cache = if path.starts_with("_app/immutable/") {
            "public, max-age=31536000, immutable"
        } else {
            "no-cache"
        };
        return (
            [(header::CONTENT_TYPE, mime.as_ref()), (header::CACHE_CONTROL, cache)],
            Body::from(file.data),
        )
            .into_response();
    }
    match Assets::get(SHELL) {
        Some(shell) => (
            [
                (header::CONTENT_TYPE, "text/html; charset=utf-8"),
                (header::CACHE_CONTROL, "no-cache"),
            ],
            Body::from(shell.data),
        )
            .into_response(),
        None => (
            StatusCode::SERVICE_UNAVAILABLE,
            "The web app is not built into this binary. Run `npm run build` in web/ and rebuild the server.",
        )
            .into_response(),
    }
}
