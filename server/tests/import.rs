//! Scenarios of the `activity-import` spec, through the HTTP API.
use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode, header},
};
use http_body_util::BodyExt;
use running_server::{AppState, app, config::Config, db, pipeline, store};
use serde_json::Value;
use sqlx::{Row, SqlitePool};
use tower::ServiceExt;

struct Ctx {
    app: Router,
    db: SqlitePool,
    dir: tempfile::TempDir,
    profile: i64,
}

async fn ctx_with(max_upload_bytes: Option<usize>) -> Ctx {
    let dir = tempfile::tempdir().unwrap();
    let mut config = Config::from_lookup(|_| None).unwrap();
    config.data_dir = dir.path().to_path_buf();
    if let Some(m) = max_upload_bytes {
        config.max_upload_bytes = m;
    }
    let db = db::open(&config.data_dir).await.unwrap();
    let profile = add_profile(&db, "Alex").await;
    Ctx {
        app: app(AppState::new(db.clone(), config)),
        db,
        dir,
        profile,
    }
}

async fn ctx() -> Ctx {
    ctx_with(None).await
}

async fn add_profile(db: &SqlitePool, name: &str) -> i64 {
    sqlx::query("INSERT INTO profiles (name) VALUES (?)")
        .bind(name)
        .execute(db)
        .await
        .unwrap()
        .last_insert_rowid()
}

/// A GPX run heading north at 3 m/s, one point every 5 s.
fn gpx(start: &str, minutes: u32, name: Option<&str>, with_hr: bool, creator: &str) -> Vec<u8> {
    let t0 = pipeline::time::parse_rfc3339(start).unwrap();
    let mut s =
        format!("<?xml version=\"1.0\"?>\n<gpx version=\"1.1\" creator=\"{creator}\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk>");
    if let Some(n) = name {
        s.push_str(&format!("<name>{n}</name>"));
    }
    s.push_str("<trkseg>");
    for i in 0..=(minutes * 12) {
        let t = pipeline::time::format_rfc3339(t0 + f64::from(i) * 5.0);
        let lat = 48.0 + f64::from(i) * 15.0 / 111_194.93;
        s.push_str(&format!("<trkpt lat=\"{lat:.7}\" lon=\"2.0\"><ele>40</ele><time>{t}</time>"));
        if with_hr {
            s.push_str("<extensions><hr>150</hr></extensions>");
        }
        s.push_str("</trkpt>");
    }
    s.push_str("</trkseg></trk></gpx>");
    s.into_bytes()
}

async fn upload(c: &Ctx, profile: i64, query: &str, files: &[(&str, &[u8])]) -> (StatusCode, Value) {
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
    let req = Request::post(format!("/api/v1/profiles/{profile}/imports{query}"))
        .header(header::CONTENT_TYPE, format!("multipart/form-data; boundary={boundary}"))
        .header("x-requested-with", "running")
        .body(Body::from(body))
        .unwrap();
    let res = c.app.clone().oneshot(req).await.unwrap();
    let status = res.status();
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap_or(Value::Null))
}

async fn get(c: &Ctx, path: &str) -> (StatusCode, axum::http::HeaderMap, Vec<u8>) {
    let res = c
        .app
        .clone()
        .oneshot(Request::get(path).body(Body::empty()).unwrap())
        .await
        .unwrap();
    let (status, headers) = (res.status(), res.headers().clone());
    (status, headers, res.into_body().collect().await.unwrap().to_bytes().to_vec())
}

async fn count(db: &SqlitePool) -> i64 {
    sqlx::query("SELECT COUNT(*) FROM activities").fetch_one(db).await.unwrap().get(0)
}

fn statuses(v: &Value) -> Vec<String> {
    v["results"]
        .as_array()
        .unwrap()
        .iter()
        .map(|r| r["status"].as_str().unwrap().to_string())
        .collect()
}

fn originals(c: &Ctx) -> usize {
    std::fs::read_dir(store::originals_dir(c.dir.path()))
        .map(|d| d.count())
        .unwrap_or(0)
}

#[tokio::test]
async fn mixed_batch_reports_one_outcome_per_file() {
    let c = ctx().await;
    let (a, b, d) = (
        gpx("2026-11-01T08:00:00Z", 30, None, true, "t"),
        gpx("2026-11-03T08:00:00Z", 30, None, false, "t"),
        gpx("2026-11-05T08:00:00Z", 30, None, false, "t"),
    );
    let (status, v) = upload(
        &c,
        c.profile,
        "",
        &[
            ("a.gpx", &a),
            ("b.gpx", &b),
            ("broken.fit", b"not an activity"),
            ("c.gpx", &d),
            ("a-again.gpx", &a),
        ],
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(statuses(&v), ["imported", "imported", "failed", "imported", "duplicate"]);
    let r = v["results"].as_array().unwrap();
    assert_eq!(r[2]["file"], "broken.fit");
    assert!(r[2]["reason"].as_str().unwrap().contains("not recognised"));
    assert_eq!(r[4]["activity_id"], r[0]["activity_id"]);
    assert_eq!(count(&c.db).await, 3);
    assert_eq!(originals(&c), 3);

    let row = sqlx::query("SELECT distance, avg_hr, effort, source_format, metrics_version FROM activities WHERE id = ?")
        .bind(r[0]["activity_id"].as_i64().unwrap())
        .fetch_one(&c.db)
        .await
        .unwrap();
    assert!((row.get::<f64, _>("distance") - 5400.0).abs() < 5.0);
    assert!((row.get::<f64, _>("avg_hr") - 150.0).abs() < 0.5);
    assert!(row.get::<Option<f64>, _>("effort").is_some());
    assert_eq!(row.get::<String, _>("source_format"), "gpx");
    let splits: i64 = sqlx::query("SELECT COUNT(*) FROM segments WHERE kind = 'split'")
        .fetch_one(&c.db)
        .await
        .unwrap()
        .get(0);
    assert_eq!(splits, 18);
}

#[tokio::test]
async fn unknown_profile_is_refused() {
    let c = ctx().await;
    let (status, v) = upload(&c, 9999, "", &[("a.gpx", &gpx("2026-11-01T08:00:00Z", 10, None, false, "t"))]).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    assert_eq!(v["error"], "not_found");
    assert_eq!(count(&c.db).await, 0);
}

#[tokio::test]
async fn format_is_detected_from_content_not_extension() {
    let c = ctx().await;
    let (_, v) = upload(
        &c,
        c.profile,
        "",
        &[("mislabelled.fit", &gpx("2026-11-01T08:00:00Z", 10, None, false, "t"))],
    )
    .await;
    assert_eq!(statuses(&v), ["imported"]);
}

#[tokio::test]
async fn unusable_files_are_rejected_with_a_reason() {
    let c = ctx_with(Some(2000)).await;
    let big = gpx("2026-11-01T08:00:00Z", 30, None, false, "t");
    assert!(big.len() > 2000);
    let route = br#"<gpx version="1.1"><trk><trkseg><trkpt lat="48.0" lon="2.0"/><trkpt lat="48.1" lon="2.0"/></trkseg></trk></gpx>"#;
    let (_, v) = upload(&c, c.profile, "", &[("big.gpx", &big), ("route.gpx", route)]).await;
    assert_eq!(statuses(&v), ["failed", "failed"]);
    assert!(v["results"][0]["reason"].as_str().unwrap().contains("limit"));
    assert!(v["results"][1]["reason"].as_str().unwrap().contains("route without timing"));
    assert_eq!(count(&c.db).await, 0);
    assert_eq!(originals(&c), 0);
}

#[tokio::test]
async fn the_same_run_from_another_export_is_a_duplicate() {
    let c = ctx().await;
    let first = gpx("2026-11-01T08:00:00Z", 30, None, true, "watch");
    // Same run, different bytes: starts 20 s later and lacks heart rate.
    let second = gpx("2026-11-01T08:00:20Z", 30, None, false, "phone");
    let (_, v1) = upload(&c, c.profile, "", &[("watch.gpx", &first)]).await;
    let (_, v2) = upload(&c, c.profile, "", &[("phone.gpx", &second)]).await;
    assert_eq!(statuses(&v2), ["duplicate"]);
    assert_eq!(v2["results"][0]["activity_id"], v1["results"][0]["activity_id"]);

    // A different run on the same morning is not a duplicate.
    let (_, v3) = upload(
        &c,
        c.profile,
        "",
        &[("short.gpx", &gpx("2026-11-01T08:00:30Z", 12, None, false, "t"))],
    )
    .await;
    assert_eq!(statuses(&v3), ["imported"]);
}

#[tokio::test]
async fn profiles_are_independent_and_share_the_original_file() {
    let c = ctx().await;
    let other = add_profile(&c.db, "Camille").await;
    let file = gpx("2026-11-01T08:00:00Z", 20, None, false, "t");
    let (_, v1) = upload(&c, c.profile, "", &[("run.gpx", &file)]).await;
    let (_, v2) = upload(&c, other, "", &[("run.gpx", &file)]).await;
    assert_eq!(statuses(&v2), ["imported"]);
    assert_eq!(originals(&c), 1);
    let (id1, id2) = (
        v1["results"][0]["activity_id"].as_i64().unwrap(),
        v2["results"][0]["activity_id"].as_i64().unwrap(),
    );
    let owner: i64 = sqlx::query("SELECT profile_id FROM activities WHERE id = ?")
        .bind(id2)
        .fetch_one(&c.db)
        .await
        .unwrap()
        .get(0);
    assert_eq!(owner, other);

    // The shared file survives the deletion of one activity and goes with the last one.
    assert!(store::delete_activity(&c.db, c.dir.path(), id1).await.unwrap());
    assert_eq!(originals(&c), 1);
    assert_eq!(get(&c, &format!("/api/v1/activities/{id2}/original")).await.0, StatusCode::OK);
    assert!(store::delete_activity(&c.db, c.dir.path(), id2).await.unwrap());
    assert_eq!(originals(&c), 0);
    assert!(!store::delete_activity(&c.db, c.dir.path(), id2).await.unwrap());

    // Once deleted, the same file imports again as a new activity.
    let (_, v3) = upload(&c, c.profile, "", &[("run.gpx", &file)]).await;
    assert_eq!(statuses(&v3), ["imported"]);
}

#[tokio::test]
async fn original_download_is_byte_identical_and_gpx_export_round_trips() {
    let c = ctx().await;
    let file = gpx("2026-11-01T08:00:00Z", 30, Some("Sortie canal"), true, "t");
    let (_, v) = upload(&c, c.profile, "", &[("run.gpx", &file)]).await;
    let id = v["results"][0]["activity_id"].as_i64().unwrap();

    let (status, headers, body) = get(&c, &format!("/api/v1/activities/{id}/original")).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(store::sha256_hex(&body), store::sha256_hex(&file));
    assert!(
        headers[header::CONTENT_DISPOSITION]
            .to_str()
            .unwrap()
            .contains("2026-11-01-sortie-canal.gpx")
    );

    let (status, headers, exported) = get(&c, &format!("/api/v1/activities/{id}/export.gpx")).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(headers[header::CONTENT_TYPE], "application/gpx+xml");
    let (_, acts) = pipeline::parse(&exported).unwrap();
    assert_eq!(acts[0].name.as_deref(), Some("Sortie canal"));
    assert_eq!(acts[0].samples[0].hr, Some(150.0));
    let back = pipeline::process(&acts[0], &pipeline::ZoneConfig::from_max(190.0), pipeline::Units::Metric).unwrap();
    assert_eq!(back.start_time, pipeline::time::parse_rfc3339("2026-11-01T08:00:00Z").unwrap());
    assert!((back.summary.whole.dist / 5400.0 - 1.0).abs() < 0.005);

    assert_eq!(get(&c, "/api/v1/activities/424242/export.gpx").await.0, StatusCode::NOT_FOUND);
    assert_eq!(get(&c, "/api/v1/activities/424242/original").await.0, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn untitled_runs_are_named_after_the_local_time_of_day() {
    let c = ctx().await;
    // 06:10 UTC is 07:10 in Paris in winter.
    let (_, v) = upload(
        &c,
        c.profile,
        "?tz_offset_minutes=60",
        &[("a.gpx", &gpx("2026-11-01T06:10:00Z", 10, None, false, "t"))],
    )
    .await;
    let (_, w) = upload(
        &c,
        c.profile,
        "?tz_offset_minutes=60",
        &[("b.gpx", &gpx("2026-11-02T18:30:00Z", 10, Some("Fractionné"), false, "t"))],
    )
    .await;
    let title = |id: i64| {
        let db = c.db.clone();
        async move {
            sqlx::query("SELECT title FROM activities WHERE id = ?")
                .bind(id)
                .fetch_one(&db)
                .await
                .unwrap()
                .get::<String, _>(0)
        }
    };
    assert_eq!(title(v["results"][0]["activity_id"].as_i64().unwrap()).await, "Sortie matinale");
    assert_eq!(title(w["results"][0]["activity_id"].as_i64().unwrap()).await, "Fractionné");
}

#[tokio::test]
async fn stream_blob_round_trips() {
    let (_, acts) = pipeline::parse(&gpx("2026-11-01T08:00:00Z", 30, None, true, "t")).unwrap();
    let p = pipeline::process(&acts[0], &pipeline::ZoneConfig::from_max(190.0), pipeline::Units::Metric).unwrap();
    let stored = store::StoredStreams {
        streams: p.streams.clone(),
        low_res: p.low_res.clone(),
    };
    let blob = store::encode_streams(&stored).unwrap();
    // JSON text keeps floats to within one unit in the last place, not bit for bit.
    let back = store::decode_streams(&blob).unwrap();
    assert_eq!(back.low_res, stored.low_res);
    assert_eq!(back.streams.len(), stored.streams.len());
    assert_eq!(back.streams.moving, stored.streams.moving);
    assert!(
        back.streams
            .dist
            .iter()
            .zip(&stored.streams.dist)
            .all(|(a, b)| (a - b).abs() < 1e-6)
    );
    assert!(back.streams.t.iter().zip(&stored.streams.t).all(|(a, b)| (a - b).abs() < 1e-9));
    assert!(blob.len() < serde_json::to_vec(&stored).unwrap().len() / 3);
}

#[tokio::test]
async fn migrations_apply_to_an_empty_database_and_are_idempotent() {
    let dir = tempfile::tempdir().unwrap();
    let pool = db::open(dir.path()).await.unwrap();
    let tables: Vec<String> = sqlx::query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
        .fetch_all(&pool)
        .await
        .unwrap()
        .iter()
        .map(|r| r.get(0))
        .collect();
    for t in ["activities", "activity_tags", "profiles", "segments", "tags"] {
        assert!(tables.iter().any(|n| n == t), "missing table {t}");
    }
    pool.close().await;
    // Reopening existing data runs no migration twice.
    db::open(dir.path()).await.unwrap();
}

/// Real exports are private and not committed; this runs only where they exist.
#[tokio::test]
async fn real_fit_exports_import_and_are_deduplicated() {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../testdata");
    let Ok(entries) = std::fs::read_dir(&dir) else { return };
    let files: Vec<(String, Vec<u8>)> = entries
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x.eq_ignore_ascii_case("fit")))
        .map(|p| (p.file_name().unwrap().to_string_lossy().into_owned(), std::fs::read(&p).unwrap()))
        .collect();
    if files.is_empty() {
        return;
    }
    let c = ctx().await;
    let refs: Vec<(&str, &[u8])> = files.iter().map(|(n, b)| (n.as_str(), b.as_slice())).collect();
    let started = std::time::Instant::now();
    let (_, v) = upload(&c, c.profile, "?tz_offset_minutes=60", &refs).await;
    eprintln!("imported {} real files in {:?}", files.len(), started.elapsed());
    assert!(statuses(&v).iter().all(|s| s == "imported"), "{v}");
    let (_, again) = upload(&c, c.profile, "", &refs).await;
    assert!(statuses(&again).iter().all(|s| s == "duplicate"));
    assert_eq!(count(&c.db).await, files.len() as i64);
    for r in sqlx::query("SELECT title, distance, length(streams) AS blob FROM activities ORDER BY start_time")
        .fetch_all(&c.db)
        .await
        .unwrap()
    {
        eprintln!(
            "  {} · {:.2} km · streams {} kB",
            r.get::<String, _>("title"),
            r.get::<f64, _>("distance") / 1000.0,
            r.get::<i64, _>("blob") / 1024
        );
    }
}
