//! The second migration, and the background worker that derives figures after requests.
mod common;

use axum::http::{Method, StatusCode};
use common::*;
use running_server::derive::{DERIVED_VERSION, Job};
use serde_json::json;
use sqlx::{Row, SqlitePool, sqlite::SqlitePoolOptions};

const START: &str = "2026-05-01T07:00:00Z";

async fn version(db: &SqlitePool, id: i64) -> i64 {
    sqlx::query("SELECT derived_version FROM activities WHERE id = ?")
        .bind(id)
        .fetch_one(db)
        .await
        .unwrap()
        .get(0)
}

#[tokio::test]
async fn migrations_apply_to_an_empty_database() {
    let c = ctx().await;
    for table in [
        "best_efforts",
        "trace_cells",
        "courses",
        "course_efforts",
        "workouts",
        "routes",
        "plan_receipts",
        "plan_syncs",
    ] {
        // Table names come from the fixed list above.
        let n: i64 = sqlx::query(sqlx::AssertSqlSafe(format!("SELECT COUNT(*) FROM {table}")))
            .fetch_one(&c.db)
            .await
            .unwrap_or_else(|e| panic!("{table}: {e}"))
            .get(0);
        assert_eq!(n, 0, "{table}");
    }
}

/// A database created by the first version, holding an activity, accepts the second
/// migration: the activity is kept and waits for the derive worker.
#[tokio::test]
async fn second_migration_keeps_existing_activities() {
    let db = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::raw_sql(include_str!("../migrations/0001_init.sql"))
        .execute(&db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO profiles (name) VALUES ('Alex')")
        .execute(&db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO activities (profile_id, title, start_time, source_format, metrics_version, distance, elapsed_time, moving_time, speed, preview, streams)
         VALUES (1, 'Avant', 1700000000, 'gpx', 1, 5000, 1800, 1790, 2.8, '[]', x'00')",
    )
    .execute(&db)
    .await
    .unwrap();

    sqlx::raw_sql(include_str!("../migrations/0002_insights_and_coaching.sql"))
        .execute(&db)
        .await
        .unwrap();

    let row = sqlx::query(
        "SELECT title, distance, derived_version, load, load_estimated, exclude_from_records, workout_id, route_id, pacer FROM activities",
    )
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(row.get::<String, _>("title"), "Avant");
    assert_eq!(row.get::<f64, _>("distance"), 5000.0);
    assert_eq!(row.get::<i64, _>("derived_version"), 0);
    assert_eq!(row.get::<Option<f64>, _>("load"), None);
    assert_eq!(row.get::<i64, _>("load_estimated"), 0);
    assert_eq!(row.get::<i64, _>("exclude_from_records"), 0);
    assert_eq!(row.get::<Option<i64>, _>("workout_id"), None);
    assert_eq!(row.get::<Option<i64>, _>("route_id"), None);
    assert_eq!(row.get::<Option<String>, _>("pacer"), None);
}

/// The import answers while the background work has not even started.
#[tokio::test]
async fn import_does_not_wait_for_the_worker() {
    let (c, worker) = ctx_held().await;
    let ids = import(&c, c.profile, &[("a.gpx", &gpx(START, 20, None, None))]).await;
    assert_eq!(ids.len(), 1);
    assert_eq!(version(&c.db, ids[0]).await, 0);
    let (status, body) = get(&c, &format!("/api/v1/profiles/{}/derive-status", c.profile)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, json!({ "pending_activities": 1, "total_activities": 1, "busy": true }));
    // The activity is already usable.
    let (status, _) = get(&c, &format!("/api/v1/activities/{}", ids[0])).await;
    assert_eq!(status, StatusCode::OK);

    tokio::spawn(worker.run(c.db.clone()));
    c.deriver.idle().await;
    assert_eq!(version(&c.db, ids[0]).await, DERIVED_VERSION);
    let (_, body) = get(&c, &format!("/api/v1/profiles/{}/derive-status", c.profile)).await;
    assert_eq!(body, json!({ "pending_activities": 0, "total_activities": 1, "busy": false }));
}

#[tokio::test]
async fn status_of_an_unknown_profile_is_not_found() {
    let c = ctx().await;
    let (status, _) = get(&c, "/api/v1/profiles/4242/derive-status").await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}

/// Activities that predate the worker are caught up when the server starts.
#[tokio::test]
async fn outdated_activities_are_backfilled_at_startup() {
    let (c, worker) = ctx_held().await;
    let ids = import(
        &c,
        c.profile,
        &[
            ("a.gpx", &gpx(START, 20, None, None)),
            ("b.gpx", &gpx("2026-05-03T07:00:00Z", 20, None, None)),
        ],
    )
    .await;
    drop(worker); // those import jobs are lost, as if the server had stopped
    let state = running_server::AppState::new(c.db.clone(), running_server::config::Config::from_lookup(|_| None).unwrap());
    state.deriver.idle().await;
    assert_eq!(state.deriver.recent(), vec![Job::Backfill]);
    for id in ids {
        assert_eq!(version(&c.db, id).await, DERIVED_VERSION);
    }
}

/// Every operation that changes what a profile's figures are built from hands a job to the worker.
#[tokio::test]
async fn each_operation_enqueues_its_job() {
    let c = ctx().await;
    let other = add_profile(&c.db, "Sarah").await;
    c.deriver.idle().await;
    let done = |c: &Ctx| c.deriver.recent();

    let ids = import(
        &c,
        c.profile,
        &[
            ("a.gpx", &gpx(START, 20, None, Some(150))),
            ("b.gpx", &gpx("2026-05-03T07:00:00Z", 20, None, None)),
        ],
    )
    .await;
    c.deriver.idle().await;
    let (a, b) = (ids[0], ids[1]);
    assert!(done(&c).contains(&Job::Activity(a)) && done(&c).contains(&Job::Activity(b)));

    // Perceived effort feeds the load of a run without heart rate.
    let (status, _) = send(&c, Method::PATCH, &format!("/api/v1/activities/{b}"), Some(json!({ "rpe": 7 }))).await;
    assert_eq!(status, StatusCode::OK);
    c.deriver.idle().await;
    assert_eq!(done(&c).last(), Some(&Job::Activity(b)));
    assert_eq!(version(&c.db, b).await, DERIVED_VERSION);
    // A title alone changes nothing derived.
    let before = done(&c).len();
    send(
        &c,
        Method::PATCH,
        &format!("/api/v1/activities/{b}"),
        Some(json!({ "title": "Footing" })),
    )
    .await;
    c.deriver.idle().await;
    assert_eq!(done(&c).len(), before);

    // Copy: the new activity is derived for the target profile.
    let (status, body) = send(
        &c,
        Method::POST,
        &format!("/api/v1/activities/{a}/copy"),
        Some(json!({ "profile_id": other })),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    let copy = body["activity_id"].as_i64().unwrap();
    c.deriver.idle().await;
    assert_eq!(done(&c).last(), Some(&Job::Activity(copy)));
    assert_eq!(version(&c.db, copy).await, DERIVED_VERSION);

    // Move: the activity for its new profile, and the profile it left.
    let (status, _) = send(
        &c,
        Method::POST,
        &format!("/api/v1/activities/{b}/move"),
        Some(json!({ "profile_id": other })),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    c.deriver.idle().await;
    let recent = done(&c);
    assert!(recent.ends_with(&[Job::Activity(b), Job::Profile(c.profile)]), "{recent:?}");
    assert_eq!(version(&c.db, b).await, DERIVED_VERSION);

    // Settings that change zones: the whole profile.
    let (status, _) = send(
        &c,
        Method::PATCH,
        &format!("/api/v1/profiles/{}", c.profile),
        Some(json!({ "hr_max": 180 })),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    c.deriver.idle().await;
    assert_eq!(done(&c).last(), Some(&Job::Profile(c.profile)));
    assert_eq!(version(&c.db, a).await, DERIVED_VERSION);
    // A new name alone does not.
    let before = done(&c).len();
    send(
        &c,
        Method::PATCH,
        &format!("/api/v1/profiles/{}", c.profile),
        Some(json!({ "name": "Alexis" })),
    )
    .await;
    c.deriver.idle().await;
    assert_eq!(done(&c).len(), before);

    // Delete: the profile that lost the activity.
    let (status, _) = send(&c, Method::DELETE, &format!("/api/v1/activities/{a}"), None).await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    c.deriver.idle().await;
    assert_eq!(done(&c).last(), Some(&Job::Profile(c.profile)));
}

/// Repeated jobs waiting together are done once, and a profile job covers its activities.
#[tokio::test]
async fn waiting_jobs_are_coalesced() {
    let (c, worker) = ctx_held().await;
    let ids = import(&c, c.profile, &[("a.gpx", &gpx(START, 20, None, None))]).await;
    c.deriver.enqueue(Job::Activity(ids[0]));
    c.deriver.enqueue(Job::Profile(c.profile));
    c.deriver.enqueue(Job::Profile(c.profile));
    assert_eq!(c.deriver.queued(), 4);
    tokio::spawn(worker.run(c.db.clone()));
    c.deriver.idle().await;
    assert_eq!(c.deriver.recent(), vec![Job::Profile(c.profile)]);
    assert_eq!(version(&c.db, ids[0]).await, DERIVED_VERSION);
}
