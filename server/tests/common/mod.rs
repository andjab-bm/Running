//! Helpers shared by the HTTP integration tests.
#![allow(dead_code)]
use axum::{
    Router,
    body::Body,
    http::{HeaderMap, Method, Request, StatusCode, header},
};
use http_body_util::BodyExt;
use running_server::{
    AppState, app,
    config::Config,
    db,
    derive::{Deriver, Worker},
    pipeline,
};
use serde_json::Value;
use sqlx::SqlitePool;
use tower::ServiceExt;

pub struct Ctx {
    pub app: Router,
    pub db: SqlitePool,
    pub dir: tempfile::TempDir,
    pub profile: i64,
    pub deriver: Deriver,
}

pub async fn ctx() -> Ctx {
    let dir = tempfile::tempdir().unwrap();
    let mut config = Config::from_lookup(|_| None).unwrap();
    config.data_dir = dir.path().to_path_buf();
    let db = db::open(&config.data_dir).await.unwrap();
    let profile = add_profile(&db, "Alex").await;
    let state = AppState::new(db.clone(), config);
    Ctx {
        deriver: state.deriver.clone(),
        app: app(state),
        db,
        dir,
        profile,
    }
}

/// Like [`ctx`], but the derive worker is handed back instead of started,
/// so a test can look at the state requests leave before any background work.
pub async fn ctx_held() -> (Ctx, Worker) {
    let dir = tempfile::tempdir().unwrap();
    let mut config = Config::from_lookup(|_| None).unwrap();
    config.data_dir = dir.path().to_path_buf();
    let db = db::open(&config.data_dir).await.unwrap();
    let profile = add_profile(&db, "Alex").await;
    let (deriver, worker) = Deriver::channel();
    let state = AppState::with_deriver(db.clone(), config, deriver.clone());
    (
        Ctx {
            deriver,
            app: app(state),
            db,
            dir,
            profile,
        },
        worker,
    )
}

pub async fn add_profile(db: &SqlitePool, name: &str) -> i64 {
    sqlx::query("INSERT INTO profiles (name) VALUES (?)")
        .bind(name)
        .execute(db)
        .await
        .unwrap()
        .last_insert_rowid()
}

/// A GPX run heading north at 3 m/s, one point every 5 s, optionally with a constant heart rate.
pub fn gpx(start: &str, minutes: u32, name: Option<&str>, hr: Option<u32>) -> Vec<u8> {
    gpx_at(start, minutes, 3.0, name, hr)
}

/// Like [`gpx`], at a chosen speed in m/s.
pub fn gpx_at(start: &str, minutes: u32, speed: f64, name: Option<&str>, hr: Option<u32>) -> Vec<u8> {
    let t0 = pipeline::time::parse_rfc3339(start).unwrap();
    let mut s =
        String::from("<?xml version=\"1.0\"?>\n<gpx version=\"1.1\" creator=\"test\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk>");
    if let Some(n) = name {
        s.push_str(&format!("<name>{n}</name>"));
    }
    s.push_str("<trkseg>");
    for i in 0..=(minutes * 12) {
        let t = pipeline::time::format_rfc3339(t0 + f64::from(i) * 5.0);
        let lat = 48.0 + f64::from(i) * speed * 5.0 / 111_194.93;
        s.push_str(&format!("<trkpt lat=\"{lat:.7}\" lon=\"2.0\"><ele>40</ele><time>{t}</time>"));
        if let Some(h) = hr {
            s.push_str(&format!("<extensions><hr>{h}</hr></extensions>"));
        }
        s.push_str("</trkpt>");
    }
    s.push_str("</trkseg></trk></gpx>");
    s.into_bytes()
}

/// A GPX run through waypoints given in metres east and north of (48 °N, 2 °E),
/// at a constant speed in m/s, one point every 5 s, flat.
pub fn gpx_path(start: &str, speed: f64, waypoints: &[(f64, f64)]) -> Vec<u8> {
    const M_PER_DEG: f64 = 111_194.93;
    let t0 = pipeline::time::parse_rfc3339(start).unwrap();
    let mut s = String::from(
        "<?xml version=\"1.0\"?>\n<gpx version=\"1.1\" creator=\"test\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><trkseg>",
    );
    let lengths: Vec<f64> = waypoints
        .windows(2)
        .map(|w| ((w[1].0 - w[0].0).powi(2) + (w[1].1 - w[0].1).powi(2)).sqrt())
        .collect();
    let total: f64 = lengths.iter().sum();
    let mut i = 0;
    loop {
        let d = (f64::from(i) * 5.0 * speed).min(total);
        // Find the leg holding distance `d`.
        let (mut leg, mut before) = (0, 0.0);
        while leg + 1 < lengths.len() && before + lengths[leg] < d {
            before += lengths[leg];
            leg += 1;
        }
        let f = if lengths[leg] > 0.0 { (d - before) / lengths[leg] } else { 0.0 };
        let (a, b) = (waypoints[leg], waypoints[leg + 1]);
        let (east, north) = (a.0 + (b.0 - a.0) * f, a.1 + (b.1 - a.1) * f);
        let (lat, lon) = (48.0 + north / M_PER_DEG, 2.0 + east / (M_PER_DEG * 48.0_f64.to_radians().cos()));
        let t = pipeline::time::format_rfc3339(t0 + f64::from(i) * 5.0);
        s.push_str(&format!(
            "<trkpt lat=\"{lat:.7}\" lon=\"{lon:.7}\"><ele>40</ele><time>{t}</time></trkpt>"
        ));
        if d >= total {
            break;
        }
        i += 1;
    }
    s.push_str("</trkseg></trk></gpx>");
    s.into_bytes()
}

/// Posts files as a multipart form to `path` and returns the JSON answer.
pub async fn upload(c: &Ctx, path: &str, files: &[(&str, &[u8])]) -> (StatusCode, Value) {
    let boundary = "----running-test-boundary";
    let mut body = Vec::new();
    for (name, bytes) in files {
        body.extend_from_slice(
            format!("--{boundary}\r\nContent-Disposition: form-data; name=\"files\"; filename=\"{name}\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .as_bytes(),
        );
        body.extend_from_slice(bytes);
        body.extend_from_slice(b"\r\n");
    }
    body.extend_from_slice(format!("--{boundary}--\r\n").as_bytes());
    let req = Request::post(path)
        .header(header::CONTENT_TYPE, format!("multipart/form-data; boundary={boundary}"))
        .header("x-requested-with", "running")
        .body(Body::from(body))
        .unwrap();
    let res = c.app.clone().oneshot(req).await.unwrap();
    let status = res.status();
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap_or(Value::Null))
}

/// Uploads files to a profile and returns the id of each imported activity.
pub async fn import(c: &Ctx, profile: i64, files: &[(&str, &[u8])]) -> Vec<i64> {
    let boundary = "----running-test-boundary";
    let mut body = Vec::new();
    for (name, bytes) in files {
        body.extend_from_slice(
            format!("--{boundary}\r\nContent-Disposition: form-data; name=\"files\"; filename=\"{name}\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .as_bytes(),
        );
        body.extend_from_slice(bytes);
        body.extend_from_slice(b"\r\n");
    }
    body.extend_from_slice(format!("--{boundary}--\r\n").as_bytes());
    let req = Request::post(format!("/api/v1/profiles/{profile}/imports"))
        .header(header::CONTENT_TYPE, format!("multipart/form-data; boundary={boundary}"))
        .header("x-requested-with", "running")
        .body(Body::from(body))
        .unwrap();
    let res = c.app.clone().oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let v: Value = serde_json::from_slice(&res.into_body().collect().await.unwrap().to_bytes()).unwrap();
    v["results"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|r| r["status"] == "imported")
        .map(|r| r["activity_id"].as_i64().unwrap())
        .collect()
}

/// Sends a request with an optional JSON body, as the web app would (same origin, custom header).
pub async fn send(c: &Ctx, method: Method, path: &str, body: Option<Value>) -> (StatusCode, Value) {
    let (status, _, bytes) = send_raw(c, method, path, body, &[("x-requested-with", "running")]).await;
    (status, serde_json::from_slice(&bytes).unwrap_or(Value::Null))
}

pub async fn send_raw(
    c: &Ctx,
    method: Method,
    path: &str,
    body: Option<Value>,
    headers: &[(&str, &str)],
) -> (StatusCode, HeaderMap, Vec<u8>) {
    let mut req = Request::builder().method(method).uri(path);
    for (k, v) in headers {
        req = req.header(*k, *v);
    }
    let req = match body {
        Some(v) => req
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(v.to_string()))
            .unwrap(),
        None => req.body(Body::empty()).unwrap(),
    };
    let res = c.app.clone().oneshot(req).await.unwrap();
    let (status, headers) = (res.status(), res.headers().clone());
    (status, headers, res.into_body().collect().await.unwrap().to_bytes().to_vec())
}

pub async fn get(c: &Ctx, path: &str) -> (StatusCode, Value) {
    send(c, Method::GET, path, None).await
}
