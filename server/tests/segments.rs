//! Scenarios of the `personal-segments` spec, through the API.
mod common;

use axum::http::{Method, StatusCode};
use common::*;
use serde_json::{Value, json};

type Path = &'static [(f64, f64)];

/// Three outings that share the 1.2 km riverside path, eastbound, and nothing else.
const RIVER: [Path; 3] = [
    &[(0.0, -400.0), (0.0, 0.0), (1200.0, 0.0), (1200.0, 400.0)],
    &[(-300.0, 300.0), (0.0, 0.0), (1200.0, 0.0), (1200.0, -400.0)],
    &[(0.0, 400.0), (0.0, 0.0), (1200.0, 0.0), (1500.0, 300.0)],
];

/// Imports one run per `(day of September 2026, speed, path)` and waits for the background work.
async fn runs(c: &Ctx, profile: i64, list: &[(u32, f64, Path)]) -> Vec<i64> {
    let mut ids = Vec::new();
    for (day, speed, path) in list {
        let bytes = gpx_path(&format!("2026-09-{day:02}T07:00:00Z"), *speed, path);
        ids.extend(import(c, profile, &[("run.gpx", &bytes)]).await);
    }
    assert_eq!(ids.len(), list.len());
    c.deriver.idle().await;
    ids
}

async fn segments(c: &Ctx, profile: i64, query: &str) -> Value {
    let (status, v) = get(c, &format!("/api/v1/profiles/{profile}/segments{query}")).await;
    assert_eq!(status, StatusCode::OK);
    v
}

fn items(v: &Value) -> &Vec<Value> {
    v["items"].as_array().unwrap()
}

async fn river(c: &Ctx) -> (Vec<i64>, i64) {
    let ids = runs(c, c.profile, &[(1, 3.0, RIVER[0]), (3, 3.3, RIVER[1]), (5, 2.8, RIVER[2])]).await;
    let list = segments(c, c.profile, "").await;
    assert_eq!(items(&list).len(), 1, "{list}");
    (ids, items(&list)[0]["id"].as_i64().unwrap())
}

#[tokio::test]
async fn the_third_passage_creates_the_segment_with_its_history() {
    let c = ctx().await;
    // Twice is not enough.
    runs(&c, c.profile, &[(1, 3.0, RIVER[0]), (3, 3.3, RIVER[1])]).await;
    let list = segments(&c, c.profile, "").await;
    assert_eq!(items(&list).len(), 0);
    assert_eq!(list["processing"], false);

    let third = runs(&c, c.profile, &[(5, 2.8, RIVER[2])]).await[0];
    let list = segments(&c, c.profile, "").await;
    let seg = &items(&list)[0];
    assert_eq!(items(&list).len(), 1);
    // About 1.2 km, heading east, with the two earlier runs matched too.
    let distance = seg["distance"].as_f64().unwrap();
    assert!((1050.0..=1300.0).contains(&distance), "{distance}");
    assert!(seg["name"].as_str().unwrap().ends_with("· vers l'est"), "{}", seg["name"]);
    assert_eq!(seg["efforts"], 3);
    assert!(seg["preview"].as_array().unwrap().len() >= 2);
    // The fastest run (3.3 m/s) holds the best time.
    assert!((seg["best_time"].as_f64().unwrap() - distance / 3.3).abs() < 12.0);

    let (_, a) = get(&c, &format!("/api/v1/activities/{third}")).await;
    let efforts = a["segment_efforts"].as_array().unwrap();
    assert_eq!(efforts.len(), 1);
    assert_eq!((efforts[0]["rank"].as_i64(), efforts[0]["efforts"].as_i64()), (Some(3), Some(3)));
    assert!(efforts[0]["gap_to_best"].as_f64().unwrap() > 30.0);
}

#[tokio::test]
async fn segment_page_ranks_every_effort() {
    let c = ctx().await;
    let (ids, seg) = river(&c).await;
    let (status, s) = get(&c, &format!("/api/v1/segments/{seg}")).await;
    assert_eq!(status, StatusCode::OK);
    let efforts = s["efforts"].as_array().unwrap();
    let order: Vec<i64> = efforts.iter().map(|e| e["activity_id"].as_i64().unwrap()).collect();
    assert_eq!(order, [ids[1], ids[0], ids[2]]);
    assert_eq!(efforts[0]["gap_to_best"], 0.0);
    assert!(efforts[1]["gap_to_best"].as_f64().unwrap() > 0.0);
    assert_eq!((efforts[0]["rank"].as_i64(), efforts[2]["rank"].as_i64()), (Some(1), Some(3)));
    assert!((efforts[0]["speed"].as_f64().unwrap() - 3.3).abs() < 0.15);
    // The trace and its (flat) profile.
    let n = s["points"]["lat"].as_array().unwrap().len();
    assert!(n > 50);
    assert_eq!(s["points"]["ele"].as_array().unwrap().len(), n);
    assert_eq!(s["points"]["dist"][0], 0.0);

    // The best time is marked on its activity.
    let (_, a) = get(&c, &format!("/api/v1/activities/{}", ids[1])).await;
    assert_eq!(a["segment_efforts"][0]["rank"], 1);
    assert_eq!(a["segment_efforts"][0]["gap_to_best"], 0.0);

    let (status, _) = get(&c, "/api/v1/segments/4242").await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn later_runs_are_timed_and_partial_ones_are_not() {
    let c = ctx().await;
    let (_, seg) = river(&c).await;
    let more = runs(
        &c,
        c.profile,
        &[
            // Over the whole segment, ten metres to the side: an effort.
            (10, 3.5, &[(-200.0, 10.0), (1400.0, 10.0)]),
            // Turns back at its midpoint: none.
            (11, 3.0, &[(0.0, 0.0), (600.0, 0.0), (0.0, 0.0)]),
            // The other way round: none.
            (12, 3.0, &[(1300.0, 0.0), (-100.0, 0.0)]),
            // Twice in one run: two efforts.
            (
                13,
                3.0,
                &[
                    (0.0, 0.0),
                    (1200.0, 0.0),
                    (1200.0, 200.0),
                    (0.0, 200.0),
                    (0.0, 0.0),
                    (1200.0, 0.0),
                    (1200.0, 100.0),
                ],
            ),
        ],
    )
    .await;
    let count = |a: &Value| {
        a["segment_efforts"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|e| e["segment_id"] == seg)
            .count()
    };
    let mut found = Vec::new();
    for id in &more {
        found.push(count(&get(&c, &format!("/api/v1/activities/{id}")).await.1));
    }
    assert_eq!(found, [1, 0, 0, 2]);
    // The new fastest passage takes the best time.
    let (_, a) = get(&c, &format!("/api/v1/activities/{}", more[0])).await;
    assert_eq!(a["segment_efforts"][0]["rank"], 1);
    let (_, s) = get(&c, &format!("/api/v1/segments/{seg}")).await;
    assert_eq!(s["efforts"].as_array().unwrap().len(), 6);
}

#[tokio::test]
async fn each_direction_is_its_own_segment() {
    let c = ctx().await;
    let east: Path = &[(0.0, 0.0), (1000.0, 0.0)];
    let west: Path = &[(1000.0, 0.0), (0.0, 0.0)];
    runs(
        &c,
        c.profile,
        &[
            (1, 3.0, east),
            (2, 3.0, west),
            (3, 3.1, east),
            (4, 3.1, west),
            (5, 3.2, east),
            (6, 3.2, west),
        ],
    )
    .await;
    let list = segments(&c, c.profile, "").await;
    let mut names: Vec<&str> = items(&list).iter().map(|s| s["name"].as_str().unwrap()).collect();
    names.sort_unstable();
    assert_eq!(names.len(), 2, "{list}");
    assert!(names[0].ends_with("vers l'est") && names[1].ends_with("vers l'ouest"), "{names:?}");
    assert!(items(&list).iter().all(|s| s["efforts"] == 3));
}

#[tokio::test]
async fn segments_are_per_profile_and_follow_their_activities() {
    let c = ctx().await;
    let sarah = add_profile(&c.db, "Sarah").await;
    let (ids, seg) = river(&c).await;
    assert_eq!(items(&segments(&c, sarah, "").await).len(), 0);

    // Copied to another profile, a run counts there, independently.
    for id in &ids {
        send(
            &c,
            Method::POST,
            &format!("/api/v1/activities/{id}/copy"),
            Some(json!({ "profile_id": sarah })),
        )
        .await;
    }
    c.deriver.idle().await;
    let theirs = segments(&c, sarah, "").await;
    assert_eq!(items(&theirs).len(), 1);
    assert_ne!(items(&theirs)[0]["id"], seg);
    assert_eq!(items(&segments(&c, c.profile, "").await)[0]["efforts"], 3);

    // Deleting the run that holds the best time promotes the next one; the segment stays.
    send(&c, Method::DELETE, &format!("/api/v1/activities/{}", ids[1]), None).await;
    c.deriver.idle().await;
    let (_, s) = get(&c, &format!("/api/v1/segments/{seg}")).await;
    let efforts = s["efforts"].as_array().unwrap();
    assert_eq!(efforts.len(), 2);
    assert_eq!(efforts[0]["activity_id"], ids[0]);
    assert_eq!(efforts[0]["gap_to_best"], 0.0);

    // Moving a run away removes its effort here.
    send(
        &c,
        Method::POST,
        &format!("/api/v1/activities/{}/move", ids[0]),
        Some(json!({ "profile_id": sarah })),
    )
    .await;
    c.deriver.idle().await;
    let (_, s) = get(&c, &format!("/api/v1/segments/{seg}")).await;
    assert_eq!(s["efforts"].as_array().unwrap().len(), 1);
}

#[tokio::test]
async fn a_segment_can_be_renamed_hidden_and_restored() {
    let c = ctx().await;
    let (_, seg) = river(&c).await;
    let url = format!("/api/v1/segments/{seg}");

    let (status, s) = send(&c, Method::PATCH, &url, Some(json!({ "name": "  Quai de Seine  " }))).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(s["name"], "Quai de Seine");
    let (status, _) = send(&c, Method::PATCH, &url, Some(json!({ "name": " " }))).await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);

    // Hidden: gone from the list and from activity pages...
    send(&c, Method::PATCH, &url, Some(json!({ "hidden": true }))).await;
    let list = segments(&c, c.profile, "").await;
    assert_eq!((items(&list).len(), list["hidden_count"].as_i64()), (0, Some(1)));
    assert_eq!(items(&segments(&c, c.profile, "?hidden=true").await)[0]["name"], "Quai de Seine");

    // ...and it does not come back after the next run, which is still timed on it.
    let next = runs(&c, c.profile, &[(20, 3.6, &[(-100.0, 0.0), (1300.0, 0.0)])]).await[0];
    assert_eq!(items(&segments(&c, c.profile, "").await).len(), 0);
    let (_, a) = get(&c, &format!("/api/v1/activities/{next}")).await;
    assert_eq!(a["segment_efforts"], json!([]));

    // Restored, it has all its efforts, the one run while hidden included.
    let (_, s) = send(&c, Method::PATCH, &url, Some(json!({ "hidden": false }))).await;
    assert_eq!(s["efforts"].as_array().unwrap().len(), 4);
    assert_eq!(s["efforts"][0]["activity_id"], next);
    let (_, a) = get(&c, &format!("/api/v1/activities/{next}")).await;
    assert_eq!(a["segment_efforts"][0]["rank"], 1);
}

#[tokio::test]
async fn list_sorts_by_count_or_by_latest_effort() {
    let c = ctx().await;
    let river: Path = &[(0.0, 0.0), (1000.0, 0.0)];
    let hill: Path = &[(5000.0, 0.0), (5000.0, 800.0)];
    // The river four times early in the month, the hill three times later.
    runs(
        &c,
        c.profile,
        &[
            (1, 3.0, river),
            (2, 3.0, river),
            (3, 3.0, river),
            (4, 3.0, river),
            (10, 3.0, hill),
            (11, 3.0, hill),
            (12, 3.0, hill),
        ],
    )
    .await;
    let name = |v: &Value, i: usize| items(v)[i]["name"].as_str().unwrap().to_string();
    let by_count = segments(&c, c.profile, "").await;
    assert_eq!(items(&by_count).len(), 2, "{by_count}");
    assert!(name(&by_count, 0).ends_with("vers l'est"));
    let by_date = segments(&c, c.profile, "?sort=recent").await;
    assert!(name(&by_date, 0).ends_with("vers le nord"));
    let (status, _) = get(&c, &format!("/api/v1/profiles/{}/segments?sort=name", c.profile)).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
}

/// A one-hour run over a route already run twice is indexed, matched and turned into
/// segments well within the minute the spec allows.
#[tokio::test]
async fn a_one_hour_run_is_processed_within_a_minute() {
    let c = ctx().await;
    // A 10.8 km out-and-back with a dog-leg: one hour at 3 m/s.
    let route: Path = &[(0.0, 0.0), (2700.0, 0.0), (2700.0, 2700.0), (2700.0, 0.0), (0.0, 0.0)];
    runs(&c, c.profile, &[(1, 3.0, route), (2, 3.0, route)]).await;
    let bytes = gpx_path("2026-09-03T07:00:00Z", 3.0, route);
    let started = std::time::Instant::now();
    let id = import(&c, c.profile, &[("run.gpx", &bytes)]).await[0];
    c.deriver.idle().await;
    let took = started.elapsed();
    assert!(took.as_secs() < 60, "{took:?}");
    let (_, a) = get(&c, &format!("/api/v1/activities/{id}")).await;
    assert!(!a["segment_efforts"].as_array().unwrap().is_empty());
    assert!((a["elapsed_time"].as_f64().unwrap() - 3600.0).abs() < 10.0);
    println!(
        "one-hour run processed in {took:?}; {} segments",
        items(&segments(&c, c.profile, "").await).len()
    );
}

/// On the owner's real exports, when present (`testdata/` is private and not committed):
/// detection must run to completion. The figures are printed, never the places.
#[tokio::test]
async fn real_exports_are_processed() {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../testdata");
    let Ok(entries) = std::fs::read_dir(&dir) else {
        return;
    };
    let c = ctx().await;
    let mut imported = 0;
    for e in entries.flatten() {
        let name = e.file_name().to_string_lossy().to_string();
        if name.ends_with(".fit") || name.ends_with(".gpx") {
            let bytes = std::fs::read(e.path()).unwrap();
            imported += import(&c, c.profile, &[(name.as_str(), &bytes)]).await.len();
        }
    }
    c.deriver.idle().await;
    let list = segments(&c, c.profile, "").await;
    assert_eq!(list["processing"], false);
    println!("real exports: {imported} activities, {} segments", items(&list).len());
    for s in items(&list) {
        println!(
            "  {:.0} m, {} efforts, best {:.0} s",
            s["distance"].as_f64().unwrap(),
            s["efforts"],
            s["best_time"].as_f64().unwrap_or(0.0)
        );
        assert!(s["efforts"].as_i64().unwrap() >= 3);
        assert!(s["distance"].as_f64().unwrap() >= 300.0);
    }
}

/// Import answers before detection has run; the list says it is still in progress.
#[tokio::test]
async fn detection_does_not_delay_the_import() {
    let (c, worker) = ctx_held().await;
    for (day, path) in RIVER.iter().enumerate() {
        let bytes = gpx_path(&format!("2026-09-{:02}T07:00:00Z", day + 1), 3.0, path);
        assert_eq!(import(&c, c.profile, &[("run.gpx", &bytes)]).await.len(), 1);
    }
    let list = segments(&c, c.profile, "").await;
    assert_eq!((items(&list).len(), list["processing"].as_bool()), (0, Some(true)));
    tokio::spawn(worker.run(c.db.clone()));
    c.deriver.idle().await;
    let list = segments(&c, c.profile, "").await;
    assert_eq!((items(&list).len(), list["processing"].as_bool()), (1, Some(false)));
}
