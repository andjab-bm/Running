//! Scenarios of the `personal-records` spec, through the API.
mod common;

use axum::http::{Method, StatusCode};
use common::*;
use running_server::pipeline::time::parse_rfc3339;
use serde_json::{Value, json};

/// Imports one run per `(start, minutes, speed)` and waits for the background computation.
async fn runs(c: &Ctx, profile: i64, list: &[(&str, u32, f64)]) -> Vec<i64> {
    let mut ids = Vec::new();
    for (start, minutes, speed) in list {
        ids.extend(import(c, profile, &[("run.gpx", &gpx_at(start, *minutes, *speed, None, None))]).await);
    }
    assert_eq!(ids.len(), list.len());
    c.deriver.idle().await;
    ids
}

fn km(records: &Value, metres: f64) -> &Value {
    records["distances"]
        .as_array()
        .unwrap()
        .iter()
        .find(|d| d["distance"] == metres)
        .unwrap()
}

fn time(v: &Value) -> f64 {
    v["moving_time"].as_f64().unwrap()
}

async fn records(c: &Ctx, profile: i64) -> Value {
    let (status, v) = get(c, &format!("/api/v1/profiles/{profile}/records")).await;
    assert_eq!(status, StatusCode::OK);
    v
}

/// Three ten-minute runs at 3.0, 3.2 and 2.8 m/s: the kilometre in 5:33, 5:12 and 5:57.
const THREE: [(&str, u32, f64); 3] = [
    ("2026-09-01T07:00:00Z", 10, 3.0),
    ("2026-09-08T07:00:00Z", 10, 3.2),
    ("2026-09-15T07:00:00Z", 10, 2.8),
];

#[tokio::test]
async fn records_page_ranks_runs_and_keeps_history() {
    let c = ctx().await;
    let ids = runs(&c, c.profile, &THREE).await;
    let r = records(&c, c.profile).await;
    assert_eq!(r["processing"], false);
    assert_eq!(r["distances"].as_array().unwrap().len(), 6);

    let one = km(&r, 1000.0);
    assert!((time(&one["record"]) - 312.5).abs() < 1.0, "{}", one["record"]);
    assert_eq!(one["record"]["activity_id"], ids[1]);
    assert!((one["record"]["speed"].as_f64().unwrap() - 3.2).abs() < 0.02);
    let next: Vec<i64> = one["next"]
        .as_array()
        .unwrap()
        .iter()
        .map(|e| e["activity_id"].as_i64().unwrap())
        .collect();
    assert_eq!(next, [ids[0], ids[2]]);
    // The record was set by the first run, then beaten once.
    let history: Vec<i64> = one["history"]
        .as_array()
        .unwrap()
        .iter()
        .map(|e| e["activity_id"].as_i64().unwrap())
        .collect();
    assert_eq!(history, [ids[0], ids[1]]);

    // Runs of 1.7 to 1.9 km: nothing at 5 km and beyond.
    for d in [5000.0, 10_000.0, 21_097.5, 42_195.0] {
        assert_eq!(km(&r, d)["record"], Value::Null, "{d}");
        assert_eq!(km(&r, d)["history"], json!([]));
    }
    let (status, _) = get(&c, "/api/v1/profiles/4242/records").await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}

/// The activity page carries the current rank, not the one at the time of the run.
#[tokio::test]
async fn activity_shows_its_current_rank() {
    let c = ctx().await;
    let first = runs(&c, c.profile, &THREE[..1]).await[0];
    let (_, a) = get(&c, &format!("/api/v1/activities/{first}")).await;
    let efforts = a["best_efforts"].as_array().unwrap();
    let distances: Vec<f64> = efforts.iter().map(|e| e["distance"].as_f64().unwrap()).collect();
    assert_eq!(distances, [400.0, 1000.0]);
    assert!(efforts.iter().all(|e| e["rank"] == 1));
    assert_eq!(a["exclude_from_records"], false);

    // A later run beats it: the earlier activity becomes second best.
    let second = runs(&c, c.profile, &THREE[1..2]).await[0];
    let (_, a) = get(&c, &format!("/api/v1/activities/{first}")).await;
    assert!(a["best_efforts"].as_array().unwrap().iter().all(|e| e["rank"] == 2));
    let (_, b) = get(&c, &format!("/api/v1/activities/{second}")).await;
    assert!(b["best_efforts"].as_array().unwrap().iter().all(|e| e["rank"] == 1));
}

#[tokio::test]
async fn records_follow_delete_copy_and_move() {
    let c = ctx().await;
    let other = add_profile(&c.db, "Sarah").await;
    let ids = runs(&c, c.profile, &THREE).await;

    // Copy: the run counts for the records of the other profile too.
    send(
        &c,
        Method::POST,
        &format!("/api/v1/activities/{}/copy", ids[1]),
        Some(json!({ "profile_id": other })),
    )
    .await;
    c.deriver.idle().await;
    let theirs = records(&c, other).await;
    assert!((time(&km(&theirs, 1000.0)["record"]) - 312.5).abs() < 1.0);
    assert_eq!(km(&records(&c, c.profile).await, 1000.0)["record"]["activity_id"], ids[1]);

    // Delete the record run: the previous second best is promoted.
    send(&c, Method::DELETE, &format!("/api/v1/activities/{}", ids[1]), None).await;
    c.deriver.idle().await;
    let r = records(&c, c.profile).await;
    assert_eq!(km(&r, 1000.0)["record"]["activity_id"], ids[0]);
    assert_eq!(km(&r, 1000.0)["next"].as_array().unwrap().len(), 1);

    // Move the new record away: it leaves this profile's records.
    send(
        &c,
        Method::POST,
        &format!("/api/v1/activities/{}/move", ids[0]),
        Some(json!({ "profile_id": other })),
    )
    .await;
    c.deriver.idle().await;
    assert_eq!(km(&records(&c, c.profile).await, 1000.0)["record"]["activity_id"], ids[2]);
    assert_eq!(km(&records(&c, other).await, 1000.0)["next"][0]["activity_id"], ids[0]);
}

/// A run with a faulty trace is excluded: its time disappears and the previous record is restored.
#[tokio::test]
async fn excluded_activity_leaves_the_records() {
    let c = ctx().await;
    let ids = runs(&c, c.profile, &THREE).await;
    let url = format!("/api/v1/activities/{}", ids[1]);
    let (status, a) = send(&c, Method::PATCH, &url, Some(json!({ "exclude_from_records": true }))).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(a["exclude_from_records"], true);
    // Its efforts are still shown, without a rank.
    assert!(a["best_efforts"].as_array().unwrap().iter().all(|e| e["rank"] == Value::Null));
    let r = records(&c, c.profile).await;
    assert_eq!(km(&r, 1000.0)["record"]["activity_id"], ids[0]);
    assert_eq!(km(&r, 1000.0)["history"].as_array().unwrap().len(), 1);

    let (_, a) = send(&c, Method::PATCH, &url, Some(json!({ "exclude_from_records": false }))).await;
    assert_eq!(a["best_efforts"][1]["rank"], 1);
    assert_eq!(km(&records(&c, c.profile).await, 1000.0)["record"]["activity_id"], ids[1]);
}

/// While imported history is still being processed, the page can say so.
#[tokio::test]
async fn records_say_when_history_is_still_processing() {
    let (c, worker) = ctx_held().await;
    import(&c, c.profile, &[("a.gpx", &gpx("2026-09-01T07:00:00Z", 10, None, None))]).await;
    let r = records(&c, c.profile).await;
    assert_eq!(r["processing"], true);
    assert_eq!(km(&r, 1000.0)["record"], Value::Null);
    tokio::spawn(worker.run(c.db.clone()));
    c.deriver.idle().await;
    let r = records(&c, c.profile).await;
    assert_eq!(r["processing"], false);
    assert!(km(&r, 1000.0)["record"].is_object());
}

fn prediction(v: &Value, metres: f64) -> &Value {
    v["predictions"]
        .as_array()
        .unwrap()
        .iter()
        .find(|p| p["distance"] == metres)
        .unwrap()
}

#[tokio::test]
async fn predictions_come_from_recent_efforts() {
    let c = ctx().await;
    // 5 km in about 28:00, on 1 November.
    let five = runs(&c, c.profile, &[("2026-11-01T07:00:00Z", 28, 2.98)]).await[0];
    let url = |t: &str| format!("/api/v1/profiles/{}/predictions?until={}", c.profile, parse_rfc3339(t).unwrap());

    let (status, p) = get(&c, &url("2026-11-16T12:00:00Z")).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(p["predictions"].as_array().unwrap().len(), 4);
    let ten = prediction(&p, 10_000.0);
    assert!((ten["time"].as_f64().unwrap() - 3500.0).abs() < 15.0, "{ten}");
    // It names the effort it is based on.
    assert_eq!(ten["basis"]["distance"], 5000.0);
    assert_eq!(ten["basis"]["activity_id"], five);
    assert_eq!(ten["indicative"], false);
    // No recent run comes close to a half-marathon or a marathon: indicative only.
    assert_eq!(prediction(&p, 21_097.5)["indicative"], true);
    assert_eq!(prediction(&p, 42_195.0)["indicative"], true);
    assert!((p["longest_recent_run"].as_f64().unwrap() - 5006.0).abs() < 15.0);

    // A 10 km in about 57:00 since then: the prediction is never slower than that result.
    runs(&c, c.profile, &[("2026-11-10T07:00:00Z", 57, 2.93)]).await;
    let (_, p) = get(&c, &url("2026-11-16T12:00:00Z")).await;
    let ten = prediction(&p, 10_000.0);
    assert!(ten["time"].as_f64().unwrap() <= 3420.0, "{ten}");
    assert_eq!(ten["basis"]["distance"], 10_000.0);
    // The marathon is now within four times the longest recent run... but only just beyond: still indicative.
    assert_eq!(prediction(&p, 21_097.5)["indicative"], false);
    assert_eq!(prediction(&p, 42_195.0)["indicative"], true);

    // Thirteen weeks after the last run, nothing qualifies any more.
    let (_, p) = get(&c, &url("2027-02-12T12:00:00Z")).await;
    assert_eq!(p["predictions"], json!([]));
    assert_eq!(p["longest_recent_run"], Value::Null);
}

#[tokio::test]
async fn excluded_runs_do_not_feed_predictions() {
    let c = ctx().await;
    let id = runs(&c, c.profile, &[("2026-11-01T07:00:00Z", 28, 2.98)]).await[0];
    send(
        &c,
        Method::PATCH,
        &format!("/api/v1/activities/{id}"),
        Some(json!({ "exclude_from_records": true })),
    )
    .await;
    let until = parse_rfc3339("2026-11-16T12:00:00Z").unwrap();
    let (_, p) = get(&c, &format!("/api/v1/profiles/{}/predictions?until={until}", c.profile)).await;
    assert_eq!(p["predictions"], json!([]));
}
