//! Scenarios of the `athlete-profile` spec, copy/move between profiles, and trusted-network access.
mod common;

use axum::http::{Method, StatusCode};
use common::*;
use running_server::store;
use serde_json::{Value, json};

async fn profile(c: &Ctx, id: i64) -> Value {
    get(c, &format!("/api/v1/profiles/{id}")).await.1
}

async fn put(c: &Ctx, id: i64, body: Value) -> (StatusCode, Value) {
    send(c, Method::PUT, &format!("/api/v1/profiles/{id}"), Some(body)).await
}

async fn activity(c: &Ctx, id: i64) -> Value {
    get(c, &format!("/api/v1/activities/{id}")).await.1
}

fn originals(c: &Ctx) -> usize {
    std::fs::read_dir(store::originals_dir(c.dir.path()))
        .map(|d| d.count())
        .unwrap_or(0)
}

#[tokio::test]
async fn profiles_are_created_listed_and_named_uniquely() {
    let c = ctx().await;
    let (status, sarah) = send(&c, Method::POST, "/api/v1/profiles", Some(json!({ "name": " Sarah " }))).await;
    assert_eq!(status, StatusCode::CREATED);
    assert_eq!(
        (sarah["name"].as_str(), sarah["activity_count"].as_i64(), sarah["units"].as_str()),
        (Some("Sarah"), Some(0), Some("metric"))
    );
    assert_ne!(sarah["color"], profile(&c, c.profile).await["color"]);

    let (_, list) = get(&c, "/api/v1/profiles").await;
    let names: Vec<&str> = list["profiles"]
        .as_array()
        .unwrap()
        .iter()
        .map(|p| p["name"].as_str().unwrap())
        .collect();
    assert_eq!(names, ["Alex", "Sarah"]);

    let (status, v) = send(&c, Method::POST, "/api/v1/profiles", Some(json!({ "name": "sarah" }))).await;
    assert_eq!((status, v["error"].as_str()), (StatusCode::CONFLICT, Some("name_taken")));
    assert_eq!(
        send(&c, Method::POST, "/api/v1/profiles", Some(json!({ "name": "  " }))).await.0,
        StatusCode::UNPROCESSABLE_ENTITY
    );
    assert_eq!(
        put(&c, sarah["id"].as_i64().unwrap(), json!({ "name": "ALEX" })).await.0,
        StatusCode::CONFLICT
    );
    let (status, v) = put(&c, sarah["id"].as_i64().unwrap(), json!({ "name": "Sarah B.", "color": "#8fe0bb" })).await;
    assert_eq!(
        (status, v["name"].as_str(), v["color"].as_str()),
        (StatusCode::OK, Some("Sarah B."), Some("#8FE0BB"))
    );
    assert_eq!(get(&c, "/api/v1/profiles/999").await.0, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn heart_rate_settings_are_validated() {
    let c = ctx().await;
    let p = profile(&c, c.profile).await;
    assert_eq!(p["zones"]["uses_default_hr_max"], true);
    assert_eq!(p["zones"]["bounds"], json!([95.0, 114.0, 133.0, 152.0, 171.0, 190.0]));

    let (status, p) = put(&c, c.profile, json!({ "hr_max": 188, "hr_rest": 52 })).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!((p["hr_max"].as_i64(), p["hr_rest"].as_i64()), (Some(188), Some(52)));
    assert_eq!(p["zones"]["uses_default_hr_max"], false);
    assert_eq!(p["zones"]["bounds"][1], 113.0);

    for bad in [
        json!({ "hr_max": 300 }),
        json!({ "hr_max": 90 }),
        json!({ "hr_rest": 188 }),
        json!({ "hr_rest": 200 }),
        json!({ "units": "furlongs" }),
    ] {
        let (status, v) = put(&c, c.profile, bad.clone()).await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{bad}");
        assert!(!v["message"].as_str().unwrap().is_empty());
    }
    assert_eq!(profile(&c, c.profile).await["hr_max"], 188);
}

#[tokio::test]
async fn zones_default_from_max_and_accept_increasing_overrides() {
    let c = ctx().await;
    let (_, p) = put(&c, c.profile, json!({ "hr_max": 190 })).await;
    assert_eq!(
        (
            p["zones"]["bounds"][1].as_f64(),
            p["zones"]["bounds"][2].as_f64(),
            p["zones"]["bounds"][4].as_f64()
        ),
        (Some(114.0), Some(133.0), Some(171.0))
    );

    let (status, p) = put(&c, c.profile, json!({ "zone_bounds": [100, 120, 140, 155, 170, 185] })).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        (p["zones"]["custom"].as_bool(), p["zones"]["bounds"][3].as_f64()),
        (Some(true), Some(155.0))
    );

    for bad in [
        json!([100, 120, 110, 155, 170, 185]),
        json!([100, 120, 140]),
        json!([100, 120, 120, 155, 170, 185]),
    ] {
        assert_eq!(
            put(&c, c.profile, json!({ "zone_bounds": bad })).await.0,
            StatusCode::UNPROCESSABLE_ENTITY
        );
    }
    let (_, p) = put(&c, c.profile, json!({ "zone_bounds": null })).await;
    assert_eq!(
        (p["zones"]["custom"].as_bool(), p["zones"]["bounds"][1].as_f64()),
        (Some(false), Some(114.0))
    );
}

#[tokio::test]
async fn changing_settings_recomputes_existing_activities() {
    let c = ctx().await;
    // 30 min at a constant 150 bpm: zone 3 with the default maximum of 190.
    let id = import(&c, c.profile, &[("run.gpx", &gpx("2026-11-01T08:00:00Z", 30, None, Some(150)))]).await[0];
    let before = activity(&c, id).await;
    assert!(before["zones"]["seconds"][2].as_f64().unwrap() > 1790.0);
    assert_eq!(before["splits"].as_array().unwrap().len(), 6);

    // With a maximum of 170, 150 bpm falls in zone 4 (136–153): effort rises by a third.
    put(&c, c.profile, json!({ "hr_max": 170 })).await;
    let after = activity(&c, id).await;
    assert_eq!(after["zones"]["seconds"][2], 0.0);
    assert!(after["zones"]["seconds"][3].as_f64().unwrap() > 1790.0);
    assert!((after["effort"].as_f64().unwrap() / before["effort"].as_f64().unwrap() - 4.0 / 3.0).abs() < 0.01);
    assert_eq!(after["zones"]["uses_default_hr_max"], false);

    // 5.4 km is six kilometre splits but four mile splits.
    let (_, p) = put(&c, c.profile, json!({ "units": "imperial", "timezone": "Europe/Paris" })).await;
    assert_eq!(
        (p["units"].as_str(), p["timezone"].as_str()),
        (Some("imperial"), Some("Europe/Paris"))
    );
    let imperial = activity(&c, id).await;
    assert_eq!(imperial["splits"].as_array().unwrap().len(), 4);
    assert!((imperial["splits"][0]["distance"].as_f64().unwrap() - 1609.344).abs() < 0.1);
    assert_eq!(imperial["distance"], before["distance"]);
}

#[tokio::test]
async fn a_shared_run_is_copied_without_heart_rate_by_default() {
    let c = ctx().await;
    let sarah = add_profile(&c.db, "Sarah").await;
    put(&c, sarah, json!({ "hr_max": 170 })).await;
    let id = import(
        &c,
        c.profile,
        &[("run.gpx", &gpx("2026-11-01T08:00:00Z", 30, Some("Sortie à deux"), Some(150)))],
    )
    .await[0];
    send(
        &c,
        Method::PATCH,
        &format!("/api/v1/activities/{id}"),
        Some(json!({ "description": "Avec Sarah", "rpe": 6, "tags": ["Tempo"] })),
    )
    .await;
    // Load and best efforts are filled in the background: wait, so that "unchanged" below means unchanged.
    c.deriver.idle().await;
    let original = activity(&c, id).await;

    let (status, v) = send(
        &c,
        Method::POST,
        &format!("/api/v1/activities/{id}/copy"),
        Some(json!({ "profile_id": sarah })),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    let copy = activity(&c, v["activity_id"].as_i64().unwrap()).await;
    assert_eq!(copy["profile_id"], sarah);
    for key in ["title", "start_time", "distance", "moving_time", "elapsed_time", "speed", "gain"] {
        assert_eq!(copy[key], original[key], "{key}");
    }
    assert_eq!(copy["splits"].as_array().unwrap().len(), 6);
    assert_eq!(copy["splits"][0]["distance"], original["splits"][0]["distance"]);
    assert_eq!(
        (&copy["avg_hr"], &copy["max_hr"], &copy["zones"], &copy["effort"]),
        (&Value::Null, &Value::Null, &Value::Null, &Value::Null)
    );
    assert_eq!(copy["splits"][0]["avg_hr"], Value::Null);
    // The copy has its own, empty journal.
    assert_eq!(
        (copy["description"].as_str(), &copy["rpe"], &copy["tags"]),
        (Some(""), &Value::Null, &json!([]))
    );
    assert_eq!(copy["copied_from"], id);
    assert_eq!(
        get(&c, &format!("/api/v1/activities/{}/streams", copy["id"])).await.1["hr"],
        Value::Null
    );
    // The original is unchanged and both share one stored file.
    assert_eq!(activity(&c, id).await, original);
    assert_eq!(originals(&c), 1);

    // A second copy is refused and points at the existing one; importing the same file there is a duplicate.
    let (status, again) = send(
        &c,
        Method::POST,
        &format!("/api/v1/activities/{id}/copy"),
        Some(json!({ "profile_id": sarah, "keep_hr": true })),
    )
    .await;
    assert_eq!((status, again["error"].as_str()), (StatusCode::CONFLICT, Some("already_present")));
    assert_eq!(again["activity_id"], copy["id"]);
    assert!(
        import(
            &c,
            sarah,
            &[("run.gpx", &gpx("2026-11-01T08:00:00Z", 30, Some("Sortie à deux"), Some(150)))]
        )
        .await
        .is_empty()
    );

    // Copies are independent: editing and deleting the copy leaves the original alone.
    send(
        &c,
        Method::PATCH,
        &format!("/api/v1/activities/{}", copy["id"]),
        Some(json!({ "title": "Ma sortie", "rpe": 8 })),
    )
    .await;
    send(&c, Method::DELETE, &format!("/api/v1/activities/{}", copy["id"]), None).await;
    assert_eq!(activity(&c, id).await, original);
    assert_eq!(originals(&c), 1);

    // Keeping heart rate computes zones with the target profile's maximum (170: 150 bpm is zone 4).
    let (_, v) = send(
        &c,
        Method::POST,
        &format!("/api/v1/activities/{id}/copy"),
        Some(json!({ "profile_id": sarah, "keep_hr": true })),
    )
    .await;
    let kept = activity(&c, v["activity_id"].as_i64().unwrap()).await;
    assert!((kept["avg_hr"].as_f64().unwrap() - 150.0).abs() < 0.5);
    assert!(kept["zones"]["seconds"][3].as_f64().unwrap() > 1790.0);
    assert!(original["zones"]["seconds"][2].as_f64().unwrap() > 1790.0);

    for (target, want) in [(c.profile, StatusCode::UNPROCESSABLE_ENTITY), (999, StatusCode::NOT_FOUND)] {
        assert_eq!(
            send(
                &c,
                Method::POST,
                &format!("/api/v1/activities/{id}/copy"),
                Some(json!({ "profile_id": target }))
            )
            .await
            .0,
            want
        );
    }
    assert_eq!(
        send(
            &c,
            Method::POST,
            "/api/v1/activities/424242/copy",
            Some(json!({ "profile_id": sarah }))
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn moving_keeps_the_journal_and_recomputes_zones() {
    let c = ctx().await;
    let sarah = add_profile(&c.db, "Sarah").await;
    put(&c, sarah, json!({ "hr_max": 170 })).await;
    let id = import(&c, c.profile, &[("run.gpx", &gpx("2026-11-01T08:00:00Z", 30, None, Some(150)))]).await[0];
    send(
        &c,
        Method::PATCH,
        &format!("/api/v1/activities/{id}"),
        Some(json!({ "description": "Mauvais profil", "rpe": 4, "tags": ["Trail", "Fractionné"] })),
    )
    .await;

    let (status, _) = send(
        &c,
        Method::POST,
        &format!("/api/v1/activities/{id}/move"),
        Some(json!({ "profile_id": sarah })),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let moved = activity(&c, id).await;
    assert_eq!(
        (moved["profile_id"].as_i64(), moved["description"].as_str(), moved["rpe"].as_i64()),
        (Some(sarah), Some("Mauvais profil"), Some(4))
    );
    assert_eq!(moved["tags"], json!(["Fractionné", "Trail"]));
    assert!((moved["avg_hr"].as_f64().unwrap() - 150.0).abs() < 0.5);
    assert!(moved["zones"]["seconds"][3].as_f64().unwrap() > 1790.0);

    let list = |p: i64| {
        let c = &c;
        async move {
            get(c, &format!("/api/v1/profiles/{p}/activities")).await.1["items"]
                .as_array()
                .unwrap()
                .len()
        }
    };
    assert_eq!((list(c.profile).await, list(sarah).await), (0, 1));
    assert_eq!(
        get(&c, &format!("/api/v1/profiles/{sarah}/tags")).await.1["tags"]
            .as_array()
            .unwrap()
            .len(),
        4
    );
    assert_eq!(
        send(
            &c,
            Method::POST,
            &format!("/api/v1/activities/{id}/move"),
            Some(json!({ "profile_id": sarah }))
        )
        .await
        .0,
        StatusCode::UNPROCESSABLE_ENTITY
    );
}

#[tokio::test]
async fn deleting_a_profile_removes_its_activities_but_not_copies_elsewhere() {
    let c = ctx().await;
    assert_eq!(
        send(&c, Method::DELETE, &format!("/api/v1/profiles/{}", c.profile), None).await,
        (
            StatusCode::CONFLICT,
            json!({ "error": "last_profile", "message": "The last profile cannot be deleted." })
        )
    );

    let sarah = add_profile(&c.db, "Sarah").await;
    let ids = import(
        &c,
        c.profile,
        &[
            ("a.gpx", &gpx("2026-11-01T08:00:00Z", 10, None, None)),
            ("b.gpx", &gpx("2026-11-02T08:00:00Z", 10, None, None)),
        ],
    )
    .await;
    let (_, v) = send(
        &c,
        Method::POST,
        &format!("/api/v1/activities/{}/copy", ids[0]),
        Some(json!({ "profile_id": sarah })),
    )
    .await;
    let copy = v["activity_id"].as_i64().unwrap();
    assert_eq!(profile(&c, c.profile).await["activity_count"], 2);
    assert_eq!(originals(&c), 2);

    assert_eq!(
        send(&c, Method::DELETE, &format!("/api/v1/profiles/{}", c.profile), None).await.0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(get(&c, &format!("/api/v1/profiles/{}", c.profile)).await.0, StatusCode::NOT_FOUND);
    assert_eq!(get(&c, &format!("/api/v1/activities/{}", ids[1])).await.0, StatusCode::NOT_FOUND);
    // The copy survives with its shared original; the unshared file is gone.
    assert_eq!(get(&c, &format!("/api/v1/activities/{copy}")).await.0, StatusCode::OK);
    assert_eq!(
        send_raw(&c, Method::GET, &format!("/api/v1/activities/{copy}/original"), None, &[])
            .await
            .0,
        StatusCode::OK
    );
    assert_eq!(originals(&c), 1);
    assert_eq!(
        send(&c, Method::DELETE, "/api/v1/profiles/999", None).await.0,
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn writes_from_other_sites_are_refused_but_no_credential_is_needed() {
    let c = ctx().await;
    let id = import(&c, c.profile, &[("a.gpx", &gpx("2026-11-01T08:00:00Z", 10, None, None))]).await[0];
    let path = format!("/api/v1/activities/{id}");
    let del = |headers: &'static [(&'static str, &'static str)]| {
        let (c, path) = (&c, path.clone());
        async move { send_raw(c, Method::DELETE, &path, None, headers).await.0 }
    };

    // A page on another site making the browser send a delete.
    assert_eq!(
        del(&[
            ("origin", "https://evil.example"),
            ("host", "pi.local:8080"),
            ("x-requested-with", "x")
        ])
        .await,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        del(&[("sec-fetch-site", "cross-site"), ("x-requested-with", "x")]).await,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        del(&[("sec-fetch-site", "same-site"), ("x-requested-with", "x")]).await,
        StatusCode::FORBIDDEN
    );
    // A plain cross-site form post cannot add the custom header.
    assert_eq!(del(&[]).await, StatusCode::FORBIDDEN);
    assert_eq!(get(&c, &path).await.0, StatusCode::OK, "nothing was deleted");

    // Reads need nothing at all; there is no sign-in.
    assert_eq!(send_raw(&c, Method::GET, &path, None, &[]).await.0, StatusCode::OK);
    assert_eq!(
        send_raw(&c, Method::GET, "/api/v1/profiles", None, &[("origin", "https://evil.example")])
            .await
            .1
            .get("access-control-allow-origin"),
        None
    );

    // The web app itself (same origin) and a non-browser client without any credential are accepted.
    let same_origin: &[(&str, &str)] = &[
        ("origin", "http://pi.local:8080"),
        ("host", "pi.local:8080"),
        ("sec-fetch-site", "same-origin"),
        ("x-requested-with", "running"),
    ];
    assert_eq!(
        send_raw(&c, Method::PATCH, &path, Some(json!({ "rpe": 5 })), same_origin).await.0,
        StatusCode::OK
    );
    assert_eq!(
        import(&c, c.profile, &[("b.gpx", &gpx("2026-11-02T08:00:00Z", 10, None, None))])
            .await
            .len(),
        1
    );
    assert_eq!(del(&[("x-requested-with", "phone-app")]).await, StatusCode::NO_CONTENT);
}
