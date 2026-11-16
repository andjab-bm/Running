//! Scenarios of the `training-statistics` spec and of the household activity list.
mod common;

use axum::http::{Method, StatusCode};
use common::*;
use running_server::pipeline::time::parse_rfc3339;
use serde_json::{Value, json};

/// Imports one run per `(start, minutes, heart rate)` and returns the ids in the same order.
async fn runs(c: &Ctx, profile: i64, list: &[(&str, u32, Option<u32>)]) -> Vec<i64> {
    let mut ids = Vec::new();
    for (start, minutes, hr) in list {
        ids.extend(import(c, profile, &[("run.gpx", &gpx(start, *minutes, None, *hr))]).await);
    }
    assert_eq!(ids.len(), list.len());
    ids
}

fn until(t: &str) -> f64 {
    parse_rfc3339(t).unwrap()
}

fn total(periods: &Value, key: &str) -> f64 {
    periods.as_array().unwrap().iter().map(|p| p[key].as_f64().unwrap()).sum()
}

#[tokio::test]
async fn calendar_adds_up_the_runs_of_a_day() {
    let c = ctx().await;
    let ids = runs(
        &c,
        c.profile,
        &[("2026-03-10T07:00:00Z", 10, None), ("2026-03-10T18:00:00Z", 20, None)],
    )
    .await;
    let (status, cal) = get(&c, &format!("/api/v1/profiles/{}/stats/calendar?year=2026", c.profile)).await;
    assert_eq!(status, StatusCode::OK);
    let days = cal["days"].as_array().unwrap();
    assert_eq!(days.len(), 1);
    assert_eq!(days[0]["date"], "2026-03-10");
    assert!((days[0]["distance"].as_f64().unwrap() - 5400.0).abs() < 10.0);
    let listed: Vec<i64> = days[0]["activities"]
        .as_array()
        .unwrap()
        .iter()
        .map(|a| a["id"].as_i64().unwrap())
        .collect();
    assert_eq!(listed, ids);
    assert_eq!(cal["totals"]["runs"], 2);
    assert_eq!(cal["totals"]["active_days"], 1);
    assert!((cal["totals"]["moving_time"].as_f64().unwrap() - 1800.0).abs() < 10.0);
}

#[tokio::test]
async fn a_year_without_runs_is_empty_not_an_error() {
    let c = ctx().await;
    runs(&c, c.profile, &[("2026-03-10T07:00:00Z", 10, None)]).await;
    let (status, cal) = get(&c, &format!("/api/v1/profiles/{}/stats/calendar?year=2024", c.profile)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(cal["days"], json!([]));
    assert_eq!(cal["totals"]["runs"], 0);
    assert_eq!(cal["totals"]["distance"], 0.0);
    let (status, _) = get(&c, "/api/v1/profiles/4242/stats/calendar").await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}

/// A run started on Sunday at 23:30 local time counts in the week that ends that Sunday.
#[tokio::test]
async fn weeks_are_cut_on_monday_in_local_time() {
    let c = ctx().await;
    runs(&c, c.profile, &[("2026-11-15T22:30:00Z", 10, None)]).await;
    let now = until("2026-11-18T12:00:00Z");
    let url = |tz: i32| {
        format!(
            "/api/v1/profiles/{}/stats/volume?count=2&until={now}&tz_offset_minutes={tz}",
            c.profile
        )
    };

    let (status, v) = get(&c, &url(60)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(v["unit"], "week");
    let p = v["periods"].as_array().unwrap();
    assert_eq!(
        (p[0]["start"].as_str(), p[1]["start"].as_str()),
        (Some("2026-11-09"), Some("2026-11-16"))
    );
    assert_eq!((p[0]["runs"].as_i64(), p[1]["runs"].as_i64()), (Some(1), Some(0)));
    assert_eq!((p[0]["current"].as_bool(), p[1]["current"].as_bool()), (Some(false), Some(true)));

    // Two hours east of UTC the same run started on Monday at 00:30.
    let (_, v) = get(&c, &url(120)).await;
    assert_eq!(v["periods"][0]["runs"], 0);
    assert_eq!(v["periods"][1]["runs"], 1);

    let (status, _) = get(&c, &format!("/api/v1/profiles/{}/stats/volume?unit=day", c.profile)).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn weeks_and_months_group_the_same_runs() {
    let c = ctx().await;
    runs(
        &c,
        c.profile,
        &[
            ("2026-09-20T08:00:00Z", 10, None),
            ("2026-10-05T08:00:00Z", 20, None),
            ("2026-11-15T08:00:00Z", 30, None),
        ],
    )
    .await;
    let now = until("2026-11-18T12:00:00Z");
    let (_, weeks) = get(&c, &format!("/api/v1/profiles/{}/stats/volume?until={now}", c.profile)).await;
    let w = weeks["periods"].as_array().unwrap();
    // Twelve bars, weeks without a run included.
    assert_eq!(w.len(), 12);
    assert_eq!(w.iter().filter(|p| p["runs"] == 0).count(), 9);
    assert!(w.iter().filter(|p| p["runs"] == 0).all(|p| p["distance"] == 0.0));

    let (_, months) = get(
        &c,
        &format!("/api/v1/profiles/{}/stats/volume?unit=month&count=3&until={now}", c.profile),
    )
    .await;
    let m = months["periods"].as_array().unwrap();
    let starts: Vec<&str> = m.iter().map(|p| p["start"].as_str().unwrap()).collect();
    assert_eq!(starts, ["2026-09-01", "2026-10-01", "2026-11-01"]);
    assert!(m.iter().all(|p| p["runs"] == 1));
    assert_eq!(m[2]["current"], true);
    for key in ["distance", "moving_time", "gain"] {
        assert!(
            (total(&weeks["periods"], key) - total(&months["periods"], key)).abs() < 1e-6,
            "{key}"
        );
    }
}

#[tokio::test]
async fn progress_leaves_gaps_and_filters_by_tag() {
    let c = ctx().await;
    let ids = runs(
        &c,
        c.profile,
        &[
            ("2026-09-20T08:00:00Z", 10, Some(150)),
            ("2026-09-27T08:00:00Z", 30, Some(160)),
            ("2026-10-05T08:00:00Z", 20, None),
        ],
    )
    .await;
    let now = until("2026-11-18T12:00:00Z");
    let url = format!("/api/v1/profiles/{}/stats/progress?months=4&until={now}", c.profile);
    let (status, p) = get(&c, &url).await;
    assert_eq!(status, StatusCode::OK);
    let m = p["months"].as_array().unwrap();
    let names: Vec<&str> = m.iter().map(|x| x["month"].as_str().unwrap()).collect();
    assert_eq!(names, ["2026-08", "2026-09", "2026-10", "2026-11"]);
    // A month without any run: every measure is a gap.
    assert_eq!(
        m[0],
        json!({ "month": "2026-08", "runs": 0, "avg_speed": null, "longest": null, "avg_hr": null })
    );
    // September: 3 m/s, longest 30 minutes, heart rate weighted by time (10 min at 150, 30 at 160).
    assert!((m[1]["avg_speed"].as_f64().unwrap() - 3.0).abs() < 0.02);
    assert!((m[1]["longest"].as_f64().unwrap() - 5400.0).abs() < 10.0);
    assert!((m[1]["avg_hr"].as_f64().unwrap() - 157.5).abs() < 0.2);
    // October has a run but no heart rate.
    assert_eq!(m[2]["runs"], 1);
    assert!(m[2]["avg_speed"].is_number());
    assert_eq!(m[2]["avg_hr"], Value::Null);

    // Only runs carrying a tag contribute.
    send(
        &c,
        Method::PATCH,
        &format!("/api/v1/activities/{}", ids[0]),
        Some(json!({ "tags": ["Trail"] })),
    )
    .await;
    let (_, p) = get(&c, &format!("{url}&tag=Trail")).await;
    assert_eq!(p["months"][1]["runs"], 1);
    assert!((p["months"][1]["longest"].as_f64().unwrap() - 1800.0).abs() < 10.0);
    assert_eq!(p["months"][2]["runs"], 0);
}

#[tokio::test]
async fn statistics_follow_the_library_and_stay_per_profile() {
    let c = ctx().await;
    let other = add_profile(&c.db, "Sarah").await;
    let ids = runs(
        &c,
        c.profile,
        &[
            ("2026-10-05T08:00:00Z", 10, None),
            ("2026-10-12T08:00:00Z", 20, None),
            ("2026-10-19T08:00:00Z", 30, None),
        ],
    )
    .await;
    let cal = |p: i64| format!("/api/v1/profiles/{p}/stats/calendar?year=2026");
    let dist = |v: &Value| v["totals"]["distance"].as_f64().unwrap();

    let (_, before) = get(&c, &cal(c.profile)).await;
    assert_eq!(before["totals"]["runs"], 3);
    let (_, empty) = get(&c, &cal(other)).await;
    assert_eq!(empty["totals"]["runs"], 0);

    // Copy: counts for the other profile too, nothing changes here.
    send(
        &c,
        Method::POST,
        &format!("/api/v1/activities/{}/copy", ids[0]),
        Some(json!({ "profile_id": other })),
    )
    .await;
    let (_, theirs) = get(&c, &cal(other)).await;
    assert_eq!(theirs["totals"]["runs"], 1);
    let (_, mine) = get(&c, &cal(c.profile)).await;
    assert_eq!(mine["totals"]["runs"], 3);

    // Move: leaves this profile, joins the other.
    send(
        &c,
        Method::POST,
        &format!("/api/v1/activities/{}/move", ids[1]),
        Some(json!({ "profile_id": other })),
    )
    .await;
    let (_, mine) = get(&c, &cal(c.profile)).await;
    assert_eq!(mine["totals"]["runs"], 2);
    let (_, theirs) = get(&c, &cal(other)).await;
    assert_eq!(theirs["totals"]["runs"], 2);

    // Delete: the totals drop by that run's distance.
    send(&c, Method::DELETE, &format!("/api/v1/activities/{}", ids[2]), None).await;
    let (_, after) = get(&c, &cal(c.profile)).await;
    assert_eq!(after["totals"]["runs"], 1);
    assert!((dist(&mine) - dist(&after) - 5400.0).abs() < 10.0);
}

fn ids(v: &Value) -> Vec<i64> {
    v["items"].as_array().unwrap().iter().map(|i| i["id"].as_i64().unwrap()).collect()
}

/// The household list shows every member's runs; a run copied between members appears once.
#[tokio::test]
async fn household_list_covers_every_profile() {
    let c = ctx().await;
    let sarah = add_profile(&c.db, "Sarah").await;
    let mine = runs(
        &c,
        c.profile,
        &[("2026-11-01T08:00:00Z", 10, None), ("2026-11-03T08:00:00Z", 10, None)],
    )
    .await;
    let hers = runs(&c, sarah, &[("2026-11-02T08:00:00Z", 10, None)]).await;
    let (status, body) = send(
        &c,
        Method::POST,
        &format!("/api/v1/activities/{}/copy", mine[1]),
        Some(json!({ "profile_id": sarah })),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    let copy = body["activity_id"].as_i64().unwrap();

    // Everyone: Sarah's run is there, and the shared run is listed once, under its original.
    let (status, all) = get(&c, "/api/v1/activities").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(ids(&all), [mine[1], hers[0], mine[0]]);
    assert_eq!(
        all["items"][0]["shared_with"],
        json!([{ "profile_id": sarah, "activity_id": copy }])
    );
    assert_eq!(all["items"][1]["profile_id"], sarah);
    assert_eq!(all["items"][1]["shared_with"], json!([]));

    // One member: her rows as they are, the copy included.
    let (_, one) = get(&c, &format!("/api/v1/activities?profile_id={sarah}")).await;
    assert_eq!(ids(&one), [copy, hers[0]]);
    assert!(one["items"][0].get("shared_with").is_none());

    // Pages.
    let (_, p1) = get(&c, "/api/v1/activities?limit=2").await;
    assert_eq!(ids(&p1), [mine[1], hers[0]]);
    let (_, p2) = get(
        &c,
        &format!("/api/v1/activities?limit=2&cursor={}", p1["next_cursor"].as_str().unwrap()),
    )
    .await;
    assert_eq!(ids(&p2), [mine[0]]);
    assert_eq!(p2["next_cursor"], Value::Null);

    // Once the original is gone, the copy stands for the run.
    send(&c, Method::DELETE, &format!("/api/v1/activities/{}", mine[1]), None).await;
    let (_, all) = get(&c, "/api/v1/activities").await;
    assert_eq!(ids(&all), [copy, hers[0], mine[0]]);

    let (status, _) = get(&c, "/api/v1/activities?profile_id=4242").await;
    assert_eq!(status, StatusCode::NOT_FOUND);

    // The per-profile list used by the phone is unchanged.
    let (_, own) = get(&c, &format!("/api/v1/profiles/{}/activities", c.profile)).await;
    assert_eq!(ids(&own), [mine[0]]);
    assert!(own["items"][0].get("shared_with").is_none());
}
