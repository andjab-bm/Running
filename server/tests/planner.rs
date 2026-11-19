//! Scenarios of the `workout-library`, `route-library` and `plan-delivery` specs, and of the
//! session, route and pace target carried by an activity.
mod common;

use axum::http::{Method, StatusCode};
use common::*;
use serde_json::{Value, json};

fn step(kind: &str, end: Value, target: Value) -> Value {
    json!({ "type": "step", "kind": kind, "end": end, "target": target })
}

fn time(s: u32) -> Value {
    json!({ "type": "time", "value": s })
}

/// "6 × 400 m": warm-up, six efforts with recoveries, cool-down — fourteen steps.
fn intervals() -> Value {
    json!([
        step("warmup", time(600), json!({ "type": "hr_zone", "zone": 2 })),
        { "type": "repeat", "count": 6, "skip_last_recovery": false, "steps": [
            step("effort", json!({ "type": "distance", "value": 400 }), json!({ "type": "pace", "fast": 300, "slow": 315 })),
            step("recovery", time(90), json!({ "type": "none" })),
        ] },
        step("cooldown", time(600), json!({ "type": "none" })),
    ])
}

async fn new_workout(c: &Ctx, profile: i64, name: &str, steps: Value) -> Value {
    let (status, w) = send(
        c,
        Method::POST,
        &format!("/api/v1/profiles/{profile}/workouts"),
        Some(json!({ "name": name, "steps": steps })),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{w}");
    w
}

#[tokio::test]
async fn a_session_is_created_listed_and_unfolded() {
    let c = ctx().await;
    let w = new_workout(&c, c.profile, "  6 × 400 m ", intervals()).await;
    assert_eq!(w["name"], "6 × 400 m");
    assert_eq!(w["steps"].as_array().unwrap().len(), 3);
    assert_eq!(w["unfolded"].as_array().unwrap().len(), 14);
    assert_eq!((w["version"].as_i64(), w["on_phone"].as_bool()), (Some(1), Some(false)));
    assert_eq!(w["delivery"]["state"], "none");

    let (_, list) = get(&c, &format!("/api/v1/profiles/{}/workouts", c.profile)).await;
    assert_eq!(list["items"].as_array().unwrap().len(), 1);
    assert_eq!(list["last_phone_sync"], Value::Null);

    // Skipping the last recovery gives thirteen steps.
    let mut steps = intervals();
    steps[1]["skip_last_recovery"] = json!(true);
    let w2 = new_workout(&c, c.profile, "Sans dernière récup", steps).await;
    assert_eq!(w2["unfolded"].as_array().unwrap().len(), 13);

    // An open-ended recovery is saved as such.
    let w3 = new_workout(
        &c,
        c.profile,
        "Récup libre",
        json!([step("recovery", json!({ "type": "lap" }), json!({ "type": "none" }))]),
    )
    .await;
    assert_eq!(w3["steps"][0]["end"], json!({ "type": "lap" }));
}

#[tokio::test]
async fn invalid_sessions_are_refused() {
    let c = ctx().await;
    let url = format!("/api/v1/profiles/{}/workouts", c.profile);
    let refused = |body: Value| {
        let (c, url) = (&c, url.clone());
        async move { send(c, Method::POST, &url, Some(body)).await.0 }
    };
    let ok_step = step("effort", time(60), json!({ "type": "none" }));
    // No step, no name.
    assert_eq!(
        refused(json!({ "name": "Vide", "steps": [] })).await,
        StatusCode::UNPROCESSABLE_ENTITY
    );
    assert_eq!(
        refused(json!({ "name": " ", "steps": [ok_step] })).await,
        StatusCode::UNPROCESSABLE_ENTITY
    );
    // A distance of zero.
    let zero = step("effort", json!({ "type": "distance", "value": 0 }), json!({ "type": "none" }));
    assert_eq!(
        refused(json!({ "name": "Zéro", "steps": [zero] })).await,
        StatusCode::UNPROCESSABLE_ENTITY
    );
    // An inverted pace range is never saved.
    let inverted = step("effort", time(60), json!({ "type": "pace", "fast": 315, "slow": 300 }));
    assert_eq!(
        refused(json!({ "name": "Inversée", "steps": [inverted] })).await,
        StatusCode::UNPROCESSABLE_ENTITY
    );
    // Nested blocks.
    let nested = json!({ "type": "repeat", "count": 2, "steps": [{ "type": "repeat", "count": 2, "steps": [ok_step] }] });
    assert_eq!(
        refused(json!({ "name": "Imbriquée", "steps": [nested] })).await,
        StatusCode::UNPROCESSABLE_ENTITY
    );
    let (_, list) = get(&c, &url).await;
    assert_eq!(list["items"], json!([]));
    let (status, _) = send(
        &c,
        Method::POST,
        "/api/v1/profiles/4242/workouts",
        Some(json!({ "name": "X", "steps": [ok_step] })),
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn sessions_are_edited_duplicated_copied_and_deleted() {
    let c = ctx().await;
    let sarah = add_profile(&c.db, "Sarah").await;
    let id = new_workout(&c, c.profile, "6 × 400 m", intervals()).await["id"].as_i64().unwrap();
    let url = format!("/api/v1/workouts/{id}");

    // Duplicate, then change the repeats in the copy: the original is unchanged.
    let (status, dup) = send(
        &c,
        Method::POST,
        &format!("{url}/copy"),
        Some(json!({ "profile_id": c.profile, "name": "8 × 400 m" })),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    let mut steps = intervals();
    steps[1]["count"] = json!(8);
    let (status, changed) = send(
        &c,
        Method::PUT,
        &format!("/api/v1/workouts/{}", dup["id"]),
        Some(json!({ "steps": steps })),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        (changed["unfolded"].as_array().unwrap().len(), changed["version"].as_i64()),
        (18, Some(2))
    );
    let (_, original) = get(&c, &url).await;
    assert_eq!(
        (original["unfolded"].as_array().unwrap().len(), original["version"].as_i64()),
        (14, Some(1))
    );

    // An invalid edit changes nothing.
    let (status, _) = send(&c, Method::PUT, &url, Some(json!({ "steps": [] }))).await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(get(&c, &url).await.1["version"], 1);

    // Copy to a partner: per profile, and independent.
    let (_, theirs) = send(&c, Method::POST, &format!("{url}/copy"), Some(json!({ "profile_id": sarah }))).await;
    assert_eq!(theirs["profile_id"], sarah);
    assert_eq!(theirs["steps"][0]["target"], json!({ "type": "hr_zone", "zone": 2 }));
    let (_, hers) = get(&c, &format!("/api/v1/profiles/{sarah}/workouts")).await;
    assert_eq!(hers["items"].as_array().unwrap().len(), 1);
    let (_, mine) = get(&c, &format!("/api/v1/profiles/{}/workouts", c.profile)).await;
    assert_eq!(mine["items"].as_array().unwrap().len(), 2);

    let (status, _) = send(&c, Method::DELETE, &url, None).await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    assert_eq!(get(&c, &url).await.0, StatusCode::NOT_FOUND);
    assert_eq!(get(&c, &format!("/api/v1/workouts/{}", theirs["id"])).await.0, StatusCode::OK);
}

/// A GPX route (no timestamps) through waypoints in metres, optionally climbing steadily.
fn gpx_route(name: Option<&str>, waypoints: &[(f64, f64)], climb_per_km: Option<f64>) -> Vec<u8> {
    const M_PER_DEG: f64 = 111_194.93;
    let mut s =
        String::from("<?xml version=\"1.0\"?>\n<gpx version=\"1.1\" creator=\"test\" xmlns=\"http://www.topografix.com/GPX/1/1\"><rte>");
    if let Some(n) = name {
        s.push_str(&format!("<name>{n}</name>"));
    }
    let mut d = 0.0;
    for w in waypoints.windows(2) {
        let len = (w[1].0 - w[0].0).hypot(w[1].1 - w[0].1);
        let steps = (len / 20.0).round() as usize;
        for k in 0..=steps {
            let f = k as f64 / steps as f64;
            let (e, n) = (w[0].0 + (w[1].0 - w[0].0) * f, w[0].1 + (w[1].1 - w[0].1) * f);
            let (lat, lon) = (48.0 + n / M_PER_DEG, 2.0 + e / (M_PER_DEG * 48.0_f64.to_radians().cos()));
            s.push_str(&format!("<rtept lat=\"{lat:.7}\" lon=\"{lon:.7}\">"));
            if let Some(c) = climb_per_km {
                s.push_str(&format!("<ele>{:.1}</ele>", 40.0 + (d + len * f) / 1000.0 * c));
            }
            s.push_str("</rtept>");
        }
        d += len;
    }
    s.push_str("</rte></gpx>");
    s.into_bytes()
}

const CANAL: [(f64, f64); 5] = [(0.0, 0.0), (3000.0, 0.0), (3000.0, 2000.0), (600.0, 2000.0), (600.0, 0.0)];

async fn import_route(c: &Ctx, profile: i64, file: &str, bytes: &[u8]) -> Value {
    let (status, v) = upload(c, &format!("/api/v1/profiles/{profile}/routes"), &[(file, bytes)]).await;
    assert_eq!(status, StatusCode::OK, "{v}");
    v
}

#[tokio::test]
async fn a_gpx_route_without_timestamps_is_imported() {
    let c = ctx().await;
    let v = import_route(&c, c.profile, "canal.gpx", &gpx_route(Some("Boucle du canal"), &CANAL, None)).await;
    assert_eq!(v["results"][0]["status"], "imported");
    let id = v["results"][0]["route_id"].as_i64().unwrap();

    let (status, r) = get(&c, &format!("/api/v1/routes/{id}")).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(r["name"], "Boucle du canal");
    assert!((r["distance"].as_f64().unwrap() - 9400.0).abs() < 30.0, "{}", r["distance"]);
    // No elevation in the file: gain and loss unavailable.
    assert_eq!(
        (&r["gain"], &r["loss"], r["has_elevation"].as_bool()),
        (&Value::Null, &Value::Null, Some(false))
    );
    assert_eq!(r["is_loop"], false);
    assert_eq!(r["activities"], json!([]));
    assert!(r["preview"].as_array().unwrap().len() >= 2);

    let (_, p) = get(&c, &format!("/api/v1/routes/{id}/points")).await;
    let n = p["count"].as_u64().unwrap() as usize;
    assert!(n >= 5);
    assert_eq!(p["lat"].as_array().unwrap().len(), n);
    assert_eq!((&p["ele"], p["dist"][0].as_f64()), (&Value::Null, Some(0.0)));

    // A file without a route name takes the file's name; several routes give several entries.
    let two = format!(
        "{}{}",
        String::from_utf8(gpx_route(None, &CANAL[..2], None)).unwrap().replace("</gpx>", ""),
        String::from_utf8(gpx_route(Some("Retour"), &CANAL[2..4], None))
            .unwrap()
            .split_once("<rte>")
            .map(|(_, rest)| format!("<rte>{rest}"))
            .unwrap()
    );
    let v = import_route(&c, c.profile, "deux.gpx", two.as_bytes()).await;
    assert_eq!(v["results"].as_array().unwrap().len(), 2);
    let (_, list) = get(&c, &format!("/api/v1/profiles/{}/routes", c.profile)).await;
    let mut names: Vec<&str> = list["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|r| r["name"].as_str().unwrap())
        .collect();
    names.sort_unstable();
    assert_eq!(names, ["Boucle du canal", "Retour", "deux"]);
}

#[tokio::test]
async fn unusable_files_give_a_reason() {
    let c = ctx().await;
    let v = import_route(&c, c.profile, "notes.txt", b"not a route").await;
    assert_eq!(v["results"][0]["status"], "failed");
    assert!(v["results"][0]["reason"].as_str().unwrap().contains("GPX"));
    let one = b"<gpx><rte><rtept lat=\"48.0\" lon=\"2.0\"/></rte></gpx>";
    let v = import_route(&c, c.profile, "un-point.gpx", one).await;
    assert_eq!(v["results"][0]["status"], "failed");
    let (_, list) = get(&c, &format!("/api/v1/profiles/{}/routes", c.profile)).await;
    assert_eq!(list["items"], json!([]));

    // Submitted as an activity, a file without timestamps is refused with a pointer to the routes.
    let (_, v) = upload(
        &c,
        &format!("/api/v1/profiles/{}/imports", c.profile),
        &[("canal.gpx", &gpx_route(Some("Boucle"), &CANAL, None))],
    )
    .await;
    assert_eq!(v["results"][0]["status"], "failed");
    assert_eq!(v["results"][0]["usable_as_route"], true);
}

#[tokio::test]
async fn routes_are_reversed_exported_copied_and_deleted() {
    let c = ctx().await;
    let sarah = add_profile(&c.db, "Sarah").await;
    // 3 km climbing 40 m per kilometre.
    let v = import_route(
        &c,
        c.profile,
        "montee.gpx",
        &gpx_route(Some("Montée"), &[(0.0, 0.0), (3000.0, 0.0)], Some(40.0)),
    )
    .await;
    let id = v["results"][0]["route_id"].as_i64().unwrap();
    let (_, r) = get(&c, &format!("/api/v1/routes/{id}")).await;
    let gain = r["gain"].as_f64().unwrap();
    assert!((gain - 120.0).abs() < 8.0 && r["loss"].as_f64().unwrap() < 5.0, "{r}");

    // Reverse: same distance, climbs and descents swapped.
    let (status, back) = send(&c, Method::POST, &format!("/api/v1/routes/{id}/reverse"), None).await;
    assert_eq!(status, StatusCode::CREATED);
    assert_eq!(back["name"], "Montée (inverse)");
    assert_eq!(back["distance"], r["distance"]);
    assert_eq!(
        (back["loss"].as_f64(), back["gain"].as_f64()),
        (r["gain"].as_f64(), r["loss"].as_f64())
    );

    // Export, then import again: same distance within 0.5 %.
    let (status, headers, body) = send_raw(&c, Method::GET, &format!("/api/v1/routes/{id}/export.gpx"), None, &[]).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(headers["content-type"], "application/gpx+xml");
    let again = import_route(&c, c.profile, "retour.gpx", &body).await;
    let (_, r2) = get(&c, &format!("/api/v1/routes/{}", again["results"][0]["route_id"])).await;
    assert_eq!(r2["name"], "Montée");
    let (d1, d2) = (r["distance"].as_f64().unwrap(), r2["distance"].as_f64().unwrap());
    assert!((d1 - d2).abs() / d1 < 0.005, "{d1} {d2}");
    assert!((r2["gain"].as_f64().unwrap() - gain).abs() < 8.0);

    // Rename bumps the version; an empty name is refused.
    let (_, renamed) = send(
        &c,
        Method::PATCH,
        &format!("/api/v1/routes/{id}"),
        Some(json!({ "name": "Belvédère" })),
    )
    .await;
    assert_eq!(
        (renamed["name"].as_str(), renamed["version"].as_i64()),
        (Some("Belvédère"), Some(2))
    );
    let (status, _) = send(&c, Method::PATCH, &format!("/api/v1/routes/{id}"), Some(json!({ "name": "" }))).await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);

    // Copy to a partner, then delete the original: the copy stays.
    let (status, theirs) = send(
        &c,
        Method::POST,
        &format!("/api/v1/routes/{id}/copy"),
        Some(json!({ "profile_id": sarah })),
    )
    .await;
    assert_eq!((status, theirs["profile_id"].as_i64()), (StatusCode::CREATED, Some(sarah)));
    assert_eq!(
        send(&c, Method::DELETE, &format!("/api/v1/routes/{id}"), None).await.0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(get(&c, &format!("/api/v1/routes/{id}")).await.0, StatusCode::NOT_FOUND);
    assert_eq!(get(&c, &format!("/api/v1/routes/{}", theirs["id"])).await.0, StatusCode::OK);
}

#[tokio::test]
async fn a_route_is_made_from_a_run() {
    let c = ctx().await;
    // A loop of 2 km.
    let square = [(0.0, 0.0), (500.0, 0.0), (500.0, 500.0), (0.0, 500.0), (0.0, 20.0)];
    let run = import(&c, c.profile, &[("run.gpx", &gpx_path("2026-09-01T07:00:00Z", 3.0, &square))]).await[0];

    let (status, r) = send(&c, Method::POST, &format!("/api/v1/activities/{run}/route"), Some(json!({}))).await;
    assert_eq!(status, StatusCode::CREATED, "{r}");
    assert_eq!(r["profile_id"], c.profile);
    assert!((r["distance"].as_f64().unwrap() - 1980.0).abs() < 30.0, "{}", r["distance"]);
    assert_eq!(r["is_loop"], true);
    // Only the path and elevations: nothing recorded.
    let (_, p) = get(&c, &format!("/api/v1/routes/{}/points", r["id"])).await;
    let keys: Vec<&String> = p.as_object().unwrap().keys().collect();
    assert_eq!(keys, ["count", "dist", "ele", "id", "lat", "lon", "version"]);

    // A portion of the run.
    let (_, part) = send(
        &c,
        Method::POST,
        &format!("/api/v1/activities/{run}/route"),
        Some(json!({ "name": "Premier côté", "from_m": 0, "to_m": 500 })),
    )
    .await;
    assert_eq!(part["name"], "Premier côté");
    assert!((part["distance"].as_f64().unwrap() - 500.0).abs() < 20.0);
    assert_eq!(part["is_loop"], false);

    let (status, _) = send(
        &c,
        Method::POST,
        &format!("/api/v1/activities/{run}/route"),
        Some(json!({ "from_m": 900, "to_m": 100 })),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        send(&c, Method::POST, "/api/v1/activities/4242/route", Some(json!({}))).await.0,
        StatusCode::NOT_FOUND
    );
}

/// Marking, the phone's fetch and receipt, and what the web then shows.
#[tokio::test]
async fn sessions_and_routes_are_delivered_to_the_phone() {
    let c = ctx().await;
    let w = new_workout(&c, c.profile, "6 × 400 m", intervals()).await["id"].as_i64().unwrap();
    let other = new_workout(
        &c,
        c.profile,
        "Footing",
        json!([step("effort", time(1800), json!({ "type": "none" }))]),
    )
    .await["id"]
        .as_i64()
        .unwrap();
    let r = import_route(&c, c.profile, "canal.gpx", &gpx_route(Some("Boucle du canal"), &CANAL, None)).await["results"][0]["route_id"]
        .as_i64()
        .unwrap();
    let plan_url = format!("/api/v1/profiles/{}/plan", c.profile);

    // Nothing marked: an empty plan, with the map style for the phone's map screen.
    let (status, plan) = get(&c, &plan_url).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!((&plan["workouts"], &plan["routes"]), (&json!([]), &json!([])));
    assert!(plan["map_style_url"].is_string() && plan["map_style_url_dark"].is_string());

    // Marked: waiting for the phone.
    let (_, marked) = send(&c, Method::PUT, &format!("/api/v1/workouts/{w}"), Some(json!({ "on_phone": true }))).await;
    assert_eq!(
        (marked["delivery"]["state"].as_str(), marked["version"].as_i64()),
        (Some("waiting"), Some(1))
    );
    send(&c, Method::PATCH, &format!("/api/v1/routes/{r}"), Some(json!({ "on_phone": true }))).await;

    // The phone fetches the plan: the session whole, the route with its figures.
    let (_, plan) = get(&c, &plan_url).await;
    assert_eq!(plan["workouts"].as_array().unwrap().len(), 1);
    assert_eq!(
        (plan["workouts"][0]["id"].as_i64(), plan["workouts"][0]["version"].as_i64()),
        (Some(w), Some(1))
    );
    let sent = &plan["workouts"][0]["steps"];
    assert_eq!(sent.as_array().unwrap().len(), 3);
    assert_eq!(
        (sent[1]["count"].as_i64(), sent[1]["steps"][0]["end"]["value"].as_f64()),
        (Some(6), Some(400.0))
    );
    assert_eq!(sent[1]["steps"][0]["target"]["type"], "pace");
    assert_eq!(
        (plan["routes"][0]["id"].as_i64(), plan["routes"][0]["name"].as_str()),
        (Some(r), Some("Boucle du canal"))
    );
    assert!(plan["routes"][0].get("points").is_none());

    // ...and says what it holds.
    let receipt = |items: Value| json!({ "device": "pixel-8", "items": items });
    let (status, _) = send(
        &c,
        Method::PUT,
        &format!("{plan_url}/receipt"),
        Some(receipt(
            json!([{ "kind": "workout", "id": w, "version": 1 }, { "kind": "route", "id": r, "version": 1 }]),
        )),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (_, delivered) = get(&c, &format!("/api/v1/workouts/{w}")).await;
    assert_eq!(delivered["delivery"]["state"], "delivered");
    assert!(delivered["delivery"]["delivered_at"].is_number());
    assert_eq!(get(&c, &format!("/api/v1/routes/{r}")).await.1["delivery"]["state"], "delivered");
    assert_eq!(get(&c, &format!("/api/v1/workouts/{other}")).await.1["delivery"]["state"], "none");
    let (_, list) = get(&c, &format!("/api/v1/profiles/{}/workouts", c.profile)).await;
    assert!(list["last_phone_sync"].is_number());

    // Edited since delivery: waiting again until the phone has the new version.
    let mut steps = intervals();
    steps[1]["count"] = json!(8);
    let (_, edited) = send(&c, Method::PUT, &format!("/api/v1/workouts/{w}"), Some(json!({ "steps": steps }))).await;
    assert_eq!(
        (edited["version"].as_i64(), edited["delivery"]["state"].as_str()),
        (Some(2), Some("waiting"))
    );
    assert_eq!(get(&c, &plan_url).await.1["workouts"][0]["version"], 2);
    send(
        &c,
        Method::PUT,
        &format!("{plan_url}/receipt"),
        Some(receipt(
            json!([{ "kind": "workout", "id": w, "version": 2 }, { "kind": "route", "id": r, "version": 1 }]),
        )),
    )
    .await;
    assert_eq!(get(&c, &format!("/api/v1/workouts/{w}")).await.1["delivery"]["state"], "delivered");

    // Unmarked: it leaves the plan, and the phone's next statement no longer lists it.
    send(
        &c,
        Method::PATCH,
        &format!("/api/v1/routes/{r}"),
        Some(json!({ "on_phone": false })),
    )
    .await;
    assert_eq!(get(&c, &plan_url).await.1["routes"], json!([]));
    assert_eq!(get(&c, &format!("/api/v1/routes/{r}")).await.1["delivery"]["state"], "none");

    // A malformed receipt is refused; an unknown profile is not found.
    let (status, _) = send(
        &c,
        Method::PUT,
        &format!("{plan_url}/receipt"),
        Some(receipt(json!([{ "kind": "song", "id": 1, "version": 1 }]))),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(get(&c, "/api/v1/profiles/4242/plan").await.0, StatusCode::NOT_FOUND);
}

/// The session, route and pace target sent by the phone are kept with the activity.
#[tokio::test]
async fn an_activity_keeps_what_it_was_run_with() {
    let c = ctx().await;
    let w = new_workout(
        &c,
        c.profile,
        "3 × 2 min",
        json!([
            step("warmup", time(300), json!({ "type": "none" })),
            { "type": "repeat", "count": 3, "skip_last_recovery": true, "steps": [
                step("effort", time(120), json!({ "type": "pace", "fast": 300, "slow": 315 })),
                step("recovery", time(60), json!({ "type": "none" })),
            ] },
        ]),
    )
    .await;
    let route = import_route(&c, c.profile, "canal.gpx", &gpx_route(Some("Boucle du canal"), &CANAL, None)).await["results"][0]["route_id"]
        .as_i64()
        .unwrap();
    // A run with a single lap: the session was cut short after its first step.
    let run = import(&c, c.profile, &[("run.gpx", &gpx("2026-09-01T07:00:00Z", 10, None, None))]).await[0];
    let url = format!("/api/v1/activities/{run}");

    let (status, a) = send(
        &c,
        Method::PATCH,
        &url,
        Some(json!({
            "workout": { "id": w["id"], "name": "3 × 2 min", "steps": w["steps"] },
            "route": { "id": route, "name": "Boucle du canal" },
            "pacer": { "target": "time", "value": 3480, "strategy": "grade", "final_gap": 35 },
        })),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{a}");
    assert_eq!(
        (a["workout"]["name"].as_str(), a["workout"]["in_library"].as_bool()),
        (Some("3 × 2 min"), Some(true))
    );
    // Six steps planned, one lap run: the first lap carries its step, the rest was not done.
    assert_eq!(
        (
            a["workout"]["unfolded"].as_array().unwrap().len(),
            a["workout"]["steps_done"].as_u64()
        ),
        (6, Some(1))
    );
    assert_eq!(a["laps"][0]["step"]["kind"], "warmup");
    assert_eq!(a["route"], json!({ "id": route, "name": "Boucle du canal", "in_library": true }));
    assert_eq!(
        a["pacer"],
        json!({ "target": "time", "value": 3480.0, "strategy": "grade", "final_gap": 35.0 })
    );
    // The route page lists the run.
    assert_eq!(get(&c, &format!("/api/v1/routes/{route}")).await.1["activities"][0]["id"], run);

    // Deleting the session and the route later does not rewrite the activity.
    send(&c, Method::DELETE, &format!("/api/v1/workouts/{}", w["id"]), None).await;
    send(&c, Method::DELETE, &format!("/api/v1/routes/{route}"), None).await;
    let (_, a) = get(&c, &url).await;
    assert_eq!(
        (a["workout"]["name"].as_str(), a["workout"]["in_library"].as_bool()),
        (Some("3 × 2 min"), Some(false))
    );
    assert_eq!(a["workout"]["unfolded"].as_array().unwrap().len(), 6);
    assert_eq!(
        (a["route"]["name"].as_str(), a["route"]["in_library"].as_bool()),
        (Some("Boucle du canal"), Some(false))
    );

    // Invalid links are refused and change nothing; null removes a link.
    let (status, _) = send(
        &c,
        Method::PATCH,
        &url,
        Some(json!({ "workout": { "id": null, "name": "X", "steps": [] } })),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    let (status, _) = send(
        &c,
        Method::PATCH,
        &url,
        Some(json!({ "pacer": { "target": "speed", "value": 3, "strategy": "even", "final_gap": 0 } })),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    let (_, a) = send(&c, Method::PATCH, &url, Some(json!({ "pacer": null, "route": null }))).await;
    assert_eq!((&a["pacer"], &a["route"]), (&Value::Null, &Value::Null));
    assert_eq!(a["workout"]["name"], "3 × 2 min");
    // An activity run freely has none of them, and its laps carry no step.
    let free = import(&c, c.profile, &[("free.gpx", &gpx("2026-09-02T07:00:00Z", 10, None, None))]).await[0];
    let (_, f) = get(&c, &format!("/api/v1/activities/{free}")).await;
    assert_eq!(
        (&f["workout"], &f["route"], &f["pacer"]),
        (&Value::Null, &Value::Null, &Value::Null)
    );
    assert!(f["laps"][0].get("step").is_none());
}

/// `tests/fixtures/android-recorder-guided.fit` is "6 x 400 m" (`fixtures/workouts/valid/intervals.json`)
/// recorded by the phone's coach: the server reads fourteen laps and labels each with its step.
#[tokio::test]
async fn a_guided_run_from_the_phone_has_fourteen_labelled_laps() {
    let c = ctx().await;
    let run = import(
        &c,
        c.profile,
        &[("guided.fit", include_bytes!("fixtures/android-recorder-guided.fit"))],
    )
    .await[0];
    let doc: Value = serde_json::from_str(include_str!("fixtures/workouts/valid/intervals.json")).unwrap();
    let url = format!("/api/v1/activities/{run}");
    let (status, a) = send(
        &c,
        Method::PATCH,
        &url,
        Some(json!({ "workout": { "id": null, "name": doc["name"], "steps": doc["steps"] } })),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{a}");
    let laps = a["laps"].as_array().unwrap();
    assert_eq!(laps.len(), 14);
    assert_eq!(a["workout"]["steps_done"].as_u64(), Some(14));
    assert!(laps.iter().all(|l| l["step"].is_object()), "every lap is labelled");
    assert_eq!(laps[0]["step"]["kind"], "warmup");
    assert_eq!(laps[13]["step"]["kind"], "cooldown");
    for effort in laps.iter().skip(1).step_by(2).take(6) {
        assert_eq!(effort["step"]["kind"], "effort");
        let d = effort["distance"].as_f64().unwrap();
        assert!((d - 400.0).abs() < 8.0, "an effort of {d} m");
    }
}
