//! Scenarios of the `training-load` spec, through the API.
mod common;

use axum::http::{Method, StatusCode};
use common::*;
use running_server::pipeline::time::{format_rfc3339, parse_rfc3339};
use serde_json::{Value, json};

const DAY: f64 = 86_400.0;

fn at(t: &str) -> f64 {
    parse_rfc3339(t).unwrap()
}

/// Imports one run per `(start, minutes, heart rate)` and waits for the background computation.
async fn runs(c: &Ctx, list: &[(f64, u32, Option<u32>)]) -> Vec<i64> {
    let mut ids = Vec::new();
    for (start, minutes, hr) in list {
        ids.extend(import(c, c.profile, &[("run.gpx", &gpx(&format_rfc3339(*start), *minutes, None, *hr))]).await);
    }
    assert_eq!(ids.len(), list.len());
    c.deriver.idle().await;
    ids
}

async fn load(c: &Ctx, until: f64) -> Value {
    let (status, v) = get(c, &format!("/api/v1/profiles/{}/load?until={until}", c.profile)).await;
    assert_eq!(status, StatusCode::OK);
    v
}

async fn activity_load(c: &Ctx, id: i64) -> (f64, bool) {
    let (_, a) = get(c, &format!("/api/v1/activities/{id}")).await;
    (a["load"].as_f64().unwrap(), a["load_estimated"].as_bool().unwrap())
}

#[tokio::test]
async fn load_comes_from_effort_or_is_estimated() {
    let c = ctx().await;
    let t = at("2026-10-01T07:00:00Z");
    let ids = runs(&c, &[(t, 40, Some(150)), (t + DAY, 40, None), (t + 2.0 * DAY, 40, None)]).await;

    // With heart rate: the relative effort itself.
    let (_, a) = get(&c, &format!("/api/v1/activities/{}", ids[0])).await;
    assert_eq!(a["load"], a["effort"]);
    assert_eq!(a["load_estimated"], false);

    // Without heart rate or perceived effort: duration at a moderate intensity, marked estimated.
    let (plain, estimated) = activity_load(&c, ids[1]).await;
    assert!(estimated && (plain - 80.0).abs() < 0.5, "{plain}");

    // Perceived effort entered later updates the load; harder is higher.
    send(
        &c,
        Method::PATCH,
        &format!("/api/v1/activities/{}", ids[1]),
        Some(json!({ "rpe": 8 })),
    )
    .await;
    send(
        &c,
        Method::PATCH,
        &format!("/api/v1/activities/{}", ids[2]),
        Some(json!({ "rpe": 3 })),
    )
    .await;
    c.deriver.idle().await;
    let (hard, _) = activity_load(&c, ids[1]).await;
    let (easy, est) = activity_load(&c, ids[2]).await;
    assert!(est && hard > easy);
    assert!((hard - 112.0).abs() < 0.5 && (easy - 42.0).abs() < 0.5, "{hard} {easy}");
}

#[tokio::test]
async fn weekly_load_adds_up_with_rest_days_at_zero() {
    let c = ctx().await;
    // Monday 2 November 2026, then Wednesday and Saturday.
    let mon = at("2026-11-02T07:00:00Z");
    runs(&c, &[(mon, 30, None), (mon + 2.0 * DAY, 45, None), (mon + 5.0 * DAY, 20, None)]).await;
    let v = load(&c, mon + 6.5 * DAY).await;
    let days = v["days"].as_array().unwrap();
    assert_eq!(days.len(), 7);
    assert_eq!(days[0]["date"], "2026-11-02");
    let loads: Vec<f64> = days.iter().map(|d| d["load"].as_f64().unwrap().round()).collect();
    assert_eq!(loads, [60.0, 0.0, 90.0, 0.0, 0.0, 40.0, 0.0]);
    assert!(days[0]["estimated"] == true && days[1]["estimated"] == false);
    let weeks = v["weeks"].as_array().unwrap();
    assert_eq!(weeks.len(), 1);
    assert_eq!(weeks[0]["start"], "2026-11-02");
    assert!((weeks[0]["load"].as_f64().unwrap() - 190.0).abs() < 1.0);
    assert_eq!(weeks[0]["current"], true);
    // A week of history is not enough for a status.
    assert_eq!(v["status"]["code"], "not_enough_data");
    assert_eq!(v["status"]["ratio"], Value::Null);
}

#[tokio::test]
async fn steady_training_then_a_heavy_week() {
    let c = ctx().await;
    let t0 = at("2026-09-01T07:00:00Z");
    // A 30-minute run every other day for four weeks.
    let habit: Vec<(f64, u32, Option<u32>)> = (0..14).map(|i| (t0 + f64::from(i) * 2.0 * DAY, 30, None)).collect();
    runs(&c, &habit).await;
    let v = load(&c, t0 + 27.5 * DAY).await;
    assert_eq!(v["status"]["code"], "steady");
    assert!((v["status"]["ratio"].as_f64().unwrap() - 1.0).abs() < 0.2);
    assert_eq!(v["status"]["history_days"], 28);
    assert!((v["typical_load"].as_f64().unwrap() - 60.0).abs() < 0.5);

    // Then a week of two-hour runs: far above the habit.
    let heavy: Vec<(f64, u32, Option<u32>)> = (0..4).map(|i| (t0 + (28.0 + f64::from(i) * 2.0) * DAY, 120, None)).collect();
    runs(&c, &heavy).await;
    let v = load(&c, t0 + 34.5 * DAY).await;
    assert_eq!(v["status"]["code"], "too_much");
    let last = v["days"].as_array().unwrap().last().unwrap();
    assert!(last["short"].as_f64().unwrap() > last["long"].as_f64().unwrap() * 1.5);

    // Two weeks without running: both averages fall, the short one faster.
    let v = load(&c, t0 + 48.5 * DAY).await;
    let days = v["days"].as_array().unwrap();
    let (a, b) = (&days[days.len() - 14], &days[days.len() - 1]);
    let f = |d: &Value, k: &str| d[k].as_f64().unwrap();
    assert!(f(b, "short") < f(a, "short") && f(b, "long") < f(a, "long"));
    assert!(f(b, "short") / f(a, "short") < f(b, "long") / f(a, "long"));
}

#[tokio::test]
async fn recovery_follows_the_latest_run() {
    let c = ctx().await;
    let t0 = at("2026-10-01T07:00:00Z");
    // The habit: 30 minutes every third day.
    let habit: Vec<(f64, u32, Option<u32>)> = (0..6).map(|i| (t0 + f64::from(i) * 3.0 * DAY, 30, None)).collect();
    let ids = runs(&c, &habit).await;
    let last_end = t0 + 15.0 * DAY + 1800.0;

    // Ten hours after a usual run.
    let v = load(&c, last_end + 10.0 * 3600.0).await;
    let usual = &v["recovery"];
    assert_eq!(usual["activity_id"], ids[5]);
    let advised = usual["hours_advised"].as_f64().unwrap();
    assert!((6.0..=96.0).contains(&advised));
    assert!((usual["hours_remaining"].as_f64().unwrap() - (advised - 10.0)).abs() < 0.01);
    assert!((usual["recovered_at"].as_f64().unwrap() - (last_end + advised * 3600.0)).abs() < 1.0);

    // Once the advised time has passed, nothing remains.
    let v = load(&c, last_end + 5.0 * DAY).await;
    assert_eq!(v["recovery"]["hours_remaining"], 0.0);

    // A session twice as long, fully recovered beforehand: a longer recovery.
    let hard_start = t0 + 25.0 * DAY;
    runs(&c, &[(hard_start, 60, None)]).await;
    let v = load(&c, hard_start + 3600.0 + 10.0 * 3600.0).await;
    let hard = v["recovery"]["hours_advised"].as_f64().unwrap();
    assert!(hard > advised, "{hard} {advised}");

    // The same session again while 20 hours of recovery remain: longer still.
    let again = hard_start + 3600.0 + (hard - 20.0) * 3600.0;
    runs(&c, &[(again, 60, None)]).await;
    let v = load(&c, again + 3600.0 + 3600.0).await;
    let tired = v["recovery"]["hours_advised"].as_f64().unwrap();
    assert!(tired > hard, "{tired} {hard}");
}

#[tokio::test]
async fn load_follows_the_profile_settings_and_the_library() {
    let c = ctx().await;
    let t = at("2026-10-01T07:00:00Z");
    let ids = runs(&c, &[(t, 40, Some(150)), (t + 2.0 * DAY, 40, Some(150))]).await;
    let (before, _) = activity_load(&c, ids[0]).await;

    // 150 bpm is zone 3 of a 190 maximum, zone 5 of a 160 maximum.
    let (status, _) = send(
        &c,
        Method::PATCH,
        &format!("/api/v1/profiles/{}", c.profile),
        Some(json!({ "hr_max": 160 })),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    c.deriver.idle().await;
    let (after, estimated) = activity_load(&c, ids[0]).await;
    assert!(!estimated && after > before * 1.5, "{before} {after}");
    let v = load(&c, t + 2.5 * DAY).await;
    assert!((v["days"][0]["load"].as_f64().unwrap() - after).abs() < 1e-6);

    // Deleting a run removes its load from the series.
    send(&c, Method::DELETE, &format!("/api/v1/activities/{}", ids[1]), None).await;
    c.deriver.idle().await;
    let v = load(&c, t + 2.5 * DAY).await;
    assert_eq!(v["days"][2]["load"], 0.0);
    assert_eq!(v["recovery"]["activity_id"], ids[0]);
}

#[tokio::test]
async fn a_profile_without_runs_has_no_load() {
    let c = ctx().await;
    let v = load(&c, at("2026-10-01T07:00:00Z")).await;
    assert_eq!(v["days"], json!([]));
    assert_eq!(v["status"]["code"], "not_enough_data");
    assert_eq!(v["recovery"], Value::Null);
    let (status, _) = get(&c, "/api/v1/profiles/4242/load").await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}
