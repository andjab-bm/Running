//! Cross-site request protection.
//!
//! The service has no authentication, so any web page open in a household
//! browser could otherwise make that browser change or delete data here.
//! State-changing requests are therefore refused when they come from another
//! site, and must carry a custom header that a cross-site page cannot add
//! without a CORS preflight, which this server never grants.
use axum::{
    extract::Request,
    http::{Method, StatusCode, header},
    middleware::Next,
    response::{IntoResponse, Response},
};

use super::ApiError;

/// Header every state-changing request must carry, with any value.
pub const REQUIRED_HEADER: &str = "x-requested-with";

fn refuse(message: &str) -> Response {
    ApiError::new(StatusCode::FORBIDDEN, "cross_site", message).into_response()
}

fn host_of(origin: &str) -> Option<&str> {
    origin.split_once("://").map(|(_, rest)| rest.split('/').next().unwrap_or(rest))
}

pub async fn same_site_writes(req: Request, next: Next) -> Response {
    if matches!(*req.method(), Method::GET | Method::HEAD | Method::OPTIONS) {
        return next.run(req).await;
    }
    let h = req.headers();
    let get = |name: &str| h.get(name).and_then(|v| v.to_str().ok());
    // Browsers state where a request comes from; anything but this site is refused.
    if let Some(site) = get("sec-fetch-site")
        && !matches!(site, "same-origin" | "none")
    {
        return refuse("Requests from other sites are not accepted.");
    }
    if let Some(origin) = get(header::ORIGIN.as_str()) {
        let same = match (host_of(origin), get(header::HOST.as_str())) {
            (Some(o), Some(host)) => o.eq_ignore_ascii_case(host),
            _ => false,
        };
        if !same {
            return refuse("Requests from other sites are not accepted.");
        }
    }
    if get(REQUIRED_HEADER).is_none() {
        return refuse("State-changing requests must carry the X-Requested-With header.");
    }
    next.run(req).await
}
