//! Scenarios of the `activity-library` spec and the activity API.
mod common;

use axum::http::{Method, StatusCode};
use common::*;
use running_server::{pipeline::time::parse_rfc3339, store};
use serde_json::{Value, json};

fn ids(v: &Value) -> Vec<i64> {
    v["items"].as_array().unwrap().iter().map(|i| i["id"].as_i64().unwrap()).collect()
}

/// Imports `n` runs on consecutive days of November 2026, oldest first.
async fn seed(c: &Ctx, n: u32) -> Vec<i64> {
    let files: Vec<(String, Vec<u8>)> = (1..=n)
        .map(|d| {
            (
                format!("run{d}.gpx"),
                gpx(&format!("2026-11-{d:02}T08:00:00Z"), 10, None, Some(150)),
            )
        })
        .collect();
    let refs: Vec<(&str, &[u8])> = files.iter().map(|(n, b)| (n.as_str(), b.as_slice())).collect();
    import(c, c.profile, &refs).await
}

#[tokio::test]
async fn list_is_most_recent_first_and_paged() {
    let c = ctx().await;
    let all = seed(&c, 7).await;
    let (status, page1) = get(&c, &format!("/api/v1/profiles/{}/activities?limit=3", c.profile)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(ids(&page1), [all[6], all[5], all[4]]);
    let item = &page1["items"][0];
    assert!((item["distance"].as_f64().unwrap() - 1800.0).abs() < 5.0);
    assert!(item["preview"].as_array().unwrap().len() >= 2);
    assert_eq!(item["tags"], json!([]));
    assert!(item["moving_time"].is_number() && item["speed"].is_number() && item["gain"].is_number());

    let cursor = page1["next_cursor"].as_str().unwrap();
    let (_, page2) = get(&c, &format!("/api/v1/profiles/{}/activities?limit=3&cursor={cursor}", c.profile)).await;
    assert_eq!(ids(&page2), [all[3], all[2], all[1]]);
    let cursor = page2["next_cursor"].as_str().unwrap();
    let (_, page3) = get(&c, &format!("/api/v1/profiles/{}/activities?limit=3&cursor={cursor}", c.profile)).await;
    assert_eq!(ids(&page3), [all[0]]);
    assert!(page3["next_cursor"].is_null());
}

#[tokio::test]
async fn list_is_scoped_to_the_profile_and_handles_empty_and_unknown() {
    let c = ctx().await;
    let other = add_profile(&c.db, "Camille").await;
    seed(&c, 2).await;
    let (status, v) = get(&c, &format!("/api/v1/profiles/{other}/activities")).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(v, json!({ "items": [], "next_cursor": null }));
    assert_eq!(get(&c, "/api/v1/profiles/999/activities").await.0, StatusCode::NOT_FOUND);
    assert_eq!(
        get(&c, &format!("/api/v1/profiles/{}/activities?cursor=nonsense", c.profile))
            .await
            .0,
        StatusCode::BAD_REQUEST
    );
}

#[tokio::test]
async fn list_filters_by_tag_and_date_range() {
    let c = ctx().await;
    let all = seed(&c, 5).await;
    for id in [all[1], all[3]] {
        let (s, _) = send(
            &c,
            Method::PATCH,
            &format!("/api/v1/activities/{id}"),
            Some(json!({ "tags": ["Fractionné"] })),
        )
        .await;
        assert_eq!(s, StatusCode::OK);
    }
    let (_, v) = get(&c, &format!("/api/v1/profiles/{}/activities?tag=Fractionn%C3%A9", c.profile)).await;
    assert_eq!(ids(&v), [all[3], all[1]]);

    let from = parse_rfc3339("2026-11-02T00:00:00Z").unwrap();
    let to = parse_rfc3339("2026-11-04T00:00:00Z").unwrap();
    let (_, v) = get(&c, &format!("/api/v1/profiles/{}/activities?from={from}&to={to}", c.profile)).await;
    assert_eq!(ids(&v), [all[2], all[1]]);
}

#[tokio::test]
async fn detail_carries_summary_splits_laps_and_zones() {
    let c = ctx().await;
    let id = import(
        &c,
        c.profile,
        &[("run.gpx", &gpx("2026-11-01T08:00:00Z", 30, Some("Sortie canal"), Some(150)))],
    )
    .await[0];
    let (status, v) = get(&c, &format!("/api/v1/activities/{id}")).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(v["title"], "Sortie canal");
    assert_eq!(v["source_format"], "gpx");
    assert_eq!(v["has_original"], true);
    assert_eq!(v["splits"].as_array().unwrap().len(), 6);
    assert_eq!(v["laps"].as_array().unwrap().len(), 1);
    assert!((v["splits"][0]["distance"].as_f64().unwrap() - 1000.0).abs() < 0.1);
    assert_eq!(v["zones"]["seconds"].as_array().unwrap().len(), 5);
    assert_eq!(v["zones"]["bounds"], json!([95.0, 114.0, 133.0, 152.0, 171.0, 190.0]));
    assert_eq!(v["zones"]["uses_default_hr_max"], true);
    assert!(v["effort"].as_f64().unwrap() > 0.0);
    assert_eq!(v["rpe"], Value::Null);

    let no_hr = import(&c, c.profile, &[("b.gpx", &gpx("2026-11-02T08:00:00Z", 10, None, None))]).await[0];
    let (_, v) = get(&c, &format!("/api/v1/activities/{no_hr}")).await;
    assert_eq!(
        (&v["zones"], &v["avg_hr"], &v["effort"]),
        (&Value::Null, &Value::Null, &Value::Null)
    );
    assert_eq!(get(&c, "/api/v1/activities/424242").await.0, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn streams_are_aligned_and_bounded() {
    let c = ctx().await;
    // 3 h at one point every 5 s: 2 161 samples, more than the low-resolution cap.
    let id = import(&c, c.profile, &[("long.gpx", &gpx("2026-11-01T06:00:00Z", 180, None, Some(140)))]).await[0];
    let (_, full) = get(&c, &format!("/api/v1/activities/{id}/streams?resolution=full")).await;
    assert_eq!(full["count"], 2161);
    let (status, low) = get(&c, &format!("/api/v1/activities/{id}/streams")).await;
    assert_eq!(status, StatusCode::OK);
    let n = low["count"].as_u64().unwrap() as usize;
    assert!(n <= 2000 && n > 100);
    for key in ["t", "dist", "lat", "lon", "speed", "moving", "ele", "hr"] {
        assert_eq!(low[key].as_array().unwrap().len(), n, "{key}");
    }
    assert_eq!(low["cad"], Value::Null);
    assert_eq!(low["t"][0], 0.0);
    assert_eq!(low["t"][n - 1], full["t"][2160]);
    assert_eq!(
        get(&c, &format!("/api/v1/activities/{id}/streams?resolution=huge")).await.0,
        StatusCode::BAD_REQUEST
    );
}

#[tokio::test]
async fn journal_is_saved_validated_and_cleared() {
    let c = ctx().await;
    let id = seed(&c, 1).await[0];
    let path = format!("/api/v1/activities/{id}");
    let (_, before) = get(&c, &path).await;

    let body = json!({ "title": "  Footing du mardi ", "description": "En aisance.", "rpe": 7, "tags": ["Endurance fondamentale", "Trail", "trail"] });
    let (status, v) = send(&c, Method::PATCH, &path, Some(body)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        (v["title"].as_str(), v["description"].as_str(), v["rpe"].as_i64()),
        (Some("Footing du mardi"), Some("En aisance."), Some(7))
    );
    assert_eq!(v["tags"], json!(["Endurance fondamentale", "Trail"]));
    // Recorded data and metrics are untouched.
    for key in ["distance", "moving_time", "avg_hr", "effort", "start_time"] {
        assert_eq!(v[key], before[key], "{key}");
    }

    for bad in [
        json!({ "rpe": 11 }),
        json!({ "rpe": 0 }),
        json!({ "title": "   " }),
        json!({ "tags": [""] }),
        json!({ "unknown": 1 }),
    ] {
        let (status, _) = send(&c, Method::PATCH, &path, Some(bad.clone())).await;
        assert!(status == StatusCode::UNPROCESSABLE_ENTITY, "{bad} gave {status}");
    }
    let (_, v) = get(&c, &path).await;
    assert_eq!((v["rpe"].as_i64(), v["title"].as_str()), (Some(7), Some("Footing du mardi")));

    // Leaving a field out keeps it; null clears the perceived effort.
    let (_, v) = send(&c, Method::PATCH, &path, Some(json!({ "rpe": null }))).await;
    assert_eq!(v["rpe"], Value::Null);
    assert_eq!(v["description"], "En aisance.");
    assert_eq!(
        send(&c, Method::PATCH, "/api/v1/activities/424242", Some(json!({ "rpe": 3 })))
            .await
            .0,
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn custom_tags_become_suggestions_for_the_profile_only() {
    let c = ctx().await;
    let other = add_profile(&c.db, "Camille").await;
    let id = seed(&c, 1).await[0];
    let (_, v) = get(&c, &format!("/api/v1/profiles/{}/tags", c.profile)).await;
    assert_eq!(v["tags"], json!(["Endurance fondamentale", "Fractionné", "Compétition"]));
    send(
        &c,
        Method::PATCH,
        &format!("/api/v1/activities/{id}"),
        Some(json!({ "tags": ["Trail", "Fractionné"] })),
    )
    .await;
    let (_, v) = get(&c, &format!("/api/v1/profiles/{}/tags", c.profile)).await;
    assert_eq!(v["tags"], json!(["Endurance fondamentale", "Fractionné", "Compétition", "Trail"]));
    let (_, v) = get(&c, &format!("/api/v1/profiles/{other}/tags")).await;
    assert_eq!(v["tags"].as_array().unwrap().len(), 3);
}

#[tokio::test]
async fn delete_removes_the_activity_and_its_original_file() {
    let c = ctx().await;
    let id = seed(&c, 1).await[0];
    let originals = || std::fs::read_dir(store::originals_dir(c.dir.path())).unwrap().count();
    assert_eq!(originals(), 1);
    let (status, _) = send(&c, Method::DELETE, &format!("/api/v1/activities/{id}"), None).await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    assert_eq!(originals(), 0);
    assert_eq!(get(&c, &format!("/api/v1/activities/{id}")).await.0, StatusCode::NOT_FOUND);
    assert_eq!(get(&c, &format!("/api/v1/activities/{id}/original")).await.0, StatusCode::NOT_FOUND);
    assert_eq!(
        ids(&get(&c, &format!("/api/v1/profiles/{}/activities", c.profile)).await.1),
        Vec::<i64>::new()
    );
    assert_eq!(
        send(&c, Method::DELETE, &format!("/api/v1/activities/{id}"), None).await.0,
        StatusCode::NOT_FOUND
    );
    let segments: i64 = sqlx::Row::get(&sqlx::query("SELECT COUNT(*) FROM segments").fetch_one(&c.db).await.unwrap(), 0);
    assert_eq!(segments, 0);
}
