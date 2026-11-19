//! Contract between the Android recorder and the importer.
//!
//! `tests/fixtures/android-recorder*.fit` are written by the phone app's FIT encoder
//! (`android/core`, test `FitEncoderTest`) from scripted runs. The main one has three laps, one manual
//! pause of 60 s and one automatic pause of about 40 s. The `.json` next to each file holds
//! the figures the phone computed for the same run. Regenerate both with
//! `./gradlew :core:test -PupdateFixtures=true` in `android/`.
use running_server::pipeline::{
    Format,
    metrics::{Units, ZoneConfig, process},
    parse::parse,
};
use serde_json::Value;

fn load(fit: &[u8], json: &str) -> (running_server::pipeline::Processed, Value) {
    let (format, mut activities) = parse(fit).expect("the Android file parses");
    assert_eq!(format, Format::Fit);
    assert_eq!(activities.len(), 1);
    let parsed = activities.remove(0);
    assert_eq!(parsed.sport.as_deref(), Some("running"));
    let processed = process(&parsed, &ZoneConfig::from_max(190.0), Units::Metric).expect("the run is processed");
    (processed, serde_json::from_str(json).expect("the expected figures are valid JSON"))
}

fn within(actual: f64, expected: f64, ratio: f64, what: &str) {
    assert!(
        (actual - expected).abs() <= expected.abs() * ratio,
        "{what}: the server has {actual}, the phone {expected}"
    );
}

#[test]
fn a_run_recorded_on_the_phone_is_read_back_with_the_same_figures() {
    let (run, phone) = load(
        include_bytes!("fixtures/android-recorder.fit"),
        include_str!("fixtures/android-recorder.json"),
    );
    let whole = &run.summary.whole;

    assert_eq!(run.laps.len() as u64, phone["laps"].as_u64().unwrap(), "lap count");
    within(whole.dist, phone["distance_m"].as_f64().unwrap(), 0.01, "distance");

    // Both pauses are excluded: moving time matches the phone's and is about 100 s short of elapsed.
    let moving = phone["moving_s"].as_f64().unwrap();
    assert!(
        (whole.moving_time - moving).abs() <= 5.0,
        "moving time: the server has {}, the phone {moving}",
        whole.moving_time
    );
    assert!(whole.elapsed_time - whole.moving_time >= 90.0, "pauses are not excluded: {whole:?}");

    within(
        whole.avg_hr.expect("heart rate is read"),
        phone["avg_hr"].as_f64().unwrap(),
        0.01,
        "average heart rate",
    );
    within(
        whole.avg_cad.expect("cadence is read"),
        phone["avg_cadence"].as_f64().unwrap(),
        0.01,
        "average cadence",
    );
    assert!(run.zones.is_some(), "time in zones is computed");

    // Lap boundaries are the ones marked on the phone: 600 s, then 600 s of running across a pause.
    assert!((run.laps[0].moving_time - 600.0).abs() <= 2.0, "first lap: {:?}", run.laps[0]);
    assert!((run.laps[1].moving_time - 600.0).abs() <= 3.0, "second lap: {:?}", run.laps[1]);
}

#[test]
fn a_run_recorded_without_the_watch_has_no_heart_rate() {
    let (run, phone) = load(
        include_bytes!("fixtures/android-recorder-nohr.fit"),
        include_str!("fixtures/android-recorder-nohr.json"),
    );
    assert!(phone["avg_hr"].is_null());
    assert!(run.summary.whole.avg_hr.is_none());
    assert!(run.zones.is_none());
    within(run.summary.whole.dist, phone["distance_m"].as_f64().unwrap(), 0.01, "distance");
    assert_eq!(run.laps.len(), 3);
}

#[test]
fn a_run_stopped_and_resumed_on_the_phone_is_one_run_with_a_pause() {
    // `android-recorder-resumed`: stopped after 10 minutes, two minutes on the summary while walking
    // about 340 m, resumed for 5 minutes, stopped again.
    let (run, phone) = load(
        include_bytes!("fixtures/android-recorder-resumed.fit"),
        include_str!("fixtures/android-recorder-resumed.json"),
    );
    let whole = &run.summary.whole;
    assert_eq!(
        run.laps.len() as u64,
        phone["laps"].as_u64().unwrap(),
        "one lap, reopened by the resume"
    );
    within(
        whole.dist,
        phone["distance_m"].as_f64().unwrap(),
        0.01,
        "distance, without the walk while stopped",
    );
    let moving = phone["moving_s"].as_f64().unwrap();
    assert!(
        (whole.moving_time - moving).abs() <= 5.0,
        "moving time: the server has {}, the phone {moving}",
        whole.moving_time
    );
    assert!(
        whole.elapsed_time - whole.moving_time >= 115.0,
        "the stop reads as a pause: {whole:?}"
    );
}
