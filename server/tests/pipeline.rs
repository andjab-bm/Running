//! Scenarios of the `activity-metrics` and `activity-import` specs, on synthetic data,
//! plus a consistency check on real exports when `testdata/` is present (it is not committed).
use running_server::pipeline::{
    Format, ParseError, Parsed, Sample,
    gpx::{self, ExportPoint},
    metrics::{Units, ZoneConfig, process},
    parse::{parse, sniff},
};

const T0: f64 = 1_794_127_330.0;
const M_PER_DEG: f64 = 111_194.93;

/// One sample per second heading north. `f(second)` returns (speed m/s, elevation, heart rate, cadence).
fn track(seconds: usize, f: impl Fn(usize) -> (f64, Option<f64>, Option<f64>, Option<f64>)) -> Parsed {
    let mut north = 0.0;
    let mut samples = Vec::new();
    for s in 0..=seconds {
        let (v, ele, hr, cad) = f(s);
        if s > 0 {
            north += v;
        }
        samples.push(Sample {
            time: T0 + s as f64,
            lat: Some(48.0 + north / M_PER_DEG),
            lon: Some(2.0),
            ele,
            dist: None,
            hr,
            cad,
        });
    }
    Parsed {
        samples,
        ..Parsed::default()
    }
}

fn zones() -> ZoneConfig {
    ZoneConfig::from_max(190.0)
}

fn run(p: &Parsed) -> running_server::pipeline::Processed {
    process(p, &zones(), Units::Metric).unwrap()
}

fn close(a: f64, b: f64, tol: f64) -> bool {
    (a - b).abs() <= tol
}

#[test]
fn recorded_pause_is_excluded_from_moving_time() {
    let mut p = track(2400, |_| (3.0, None, None, None));
    // No samples are written while the timer is stopped.
    p.samples.retain(|s| !(s.time > T0 + 1200.0 && s.time < T0 + 1380.0));
    p.pauses = Some(vec![(T0 + 1200.0, T0 + 1380.0)]);
    let r = run(&p);
    assert!(close(r.summary.whole.elapsed_time, 2400.0, 0.1));
    assert!(
        close(r.summary.whole.moving_time, 2220.0, 1.5),
        "moving {}",
        r.summary.whole.moving_time
    );
}

#[test]
fn standing_still_is_detected_as_a_pause() {
    let p = track(1320, |s| (if (600..720).contains(&s) { 0.0 } else { 3.0 }, None, None, None));
    let r = run(&p);
    assert!(close(r.summary.whole.elapsed_time - r.summary.whole.moving_time, 120.0, 12.0));
}

#[test]
fn brief_slowdown_still_counts_as_moving() {
    let p = track(600, |s| (if (300..304).contains(&s) { 0.0 } else { 3.0 }, None, None, None));
    let r = run(&p);
    assert!(close(r.summary.whole.moving_time, r.summary.whole.elapsed_time, 0.5));
}

#[test]
fn noisy_altitude_on_flat_ground_does_not_accumulate() {
    let mut seed = 12345u32;
    let noise: Vec<f64> = (0..3400)
        .map(|_| {
            seed = seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            f64::from(seed >> 8) / f64::from(1u32 << 24) * 2.0 - 1.0
        })
        .collect();
    let r = run(&track(3334, |s| (3.0, Some(50.0 + noise[s]), None, None)));
    assert!(r.summary.whole.dist > 9_990.0);
    assert!(r.summary.whole.gain.unwrap() < 5.0, "gain {:?}", r.summary.whole.gain);
    assert!(r.summary.whole.loss.unwrap() < 5.0);
}

#[test]
fn a_real_climb_is_measured() {
    // 500 m flat, 2 km rising 100 m, 500 m flat, at 3 m/s.
    let ele = |s: usize| {
        let d = s as f64 * 3.0;
        Some(100.0 + ((d - 500.0) / 2000.0).clamp(0.0, 1.0) * 100.0)
    };
    let r = run(&track(1000, |s| (3.0, ele(s), None, None)));
    let gain = r.summary.whole.gain.unwrap();
    assert!((90.0..=110.0).contains(&gain), "gain {gain}");
    assert!(r.summary.whole.loss.unwrap() < 1.0);
}

#[test]
fn grade_adjusted_pace_is_faster_uphill_and_equal_on_flat() {
    let v = 1000.0 / 360.0; // 6:00 min/km
    let up = run(&track(1200, |s| (v, Some(s as f64 * v * 0.05), None, None)));
    let split = &up.splits[1];
    assert!(
        split.gap_speed.unwrap() > split.speed * 1.15,
        "gap {:?} speed {}",
        split.gap_speed,
        split.speed
    );

    let flat = run(&track(1200, |_| (v, Some(40.0), None, None)));
    let split = &flat.splits[1];
    assert!(close(1000.0 / split.gap_speed.unwrap(), 1000.0 / split.speed, 2.0));

    let none = run(&track(600, |_| (v, None, None, None)));
    assert!(none.summary.whole.gap_speed.is_none() && none.summary.whole.gain.is_none());
}

#[test]
fn splits_cover_the_distance_with_a_partial_last_one() {
    let r = run(&track(1800, |_| (3.0, None, None, None))); // 5.4 km
    assert_eq!(r.splits.len(), 6);
    assert!(close(r.splits[5].dist, 400.0, 5.0), "last {}", r.splits[5].dist);
    assert!(close(r.splits[0].dist, 1000.0, 0.01));
    let sum: f64 = r.splits.iter().map(|s| s.dist).sum();
    assert!(close(sum, r.summary.whole.dist, 0.01));
    assert!(close(r.splits[2].start_t, 2000.0 / 3.0, 1.0));

    let imperial = process(&track(1800, |_| (3.0, None, None, None)), &zones(), Units::Imperial).unwrap();
    assert_eq!(imperial.splits.len(), 4);
    assert!(close(imperial.splits[0].dist, 1609.344, 0.01));
}

#[test]
fn a_pause_inside_a_split_is_excluded_from_its_time() {
    // Pause of 200 s at t = 800 s, i.e. 2.4 km into the run.
    let mut p = track(2000, |s| (if (800..1000).contains(&s) { 0.0 } else { 3.0 }, None, None, None));
    p.samples.retain(|s| !(s.time > T0 + 800.0 && s.time < T0 + 1000.0));
    p.pauses = Some(vec![(T0 + 800.0, T0 + 1000.0)]);
    let r = run(&p);
    let third = &r.splits[2];
    assert!(close(third.moving_time, 1000.0 / 3.0, 2.0), "moving {}", third.moving_time);
    assert!(close(third.elapsed_time - third.moving_time, 200.0, 2.0));
    assert!(close(third.speed, 3.0, 0.02));
}

#[test]
fn laps_are_preserved_and_default_to_one() {
    let mut p = track(1200, |_| (3.0, None, Some(150.0), None));
    p.lap_starts = (0..12).map(|i| T0 + f64::from(i) * 100.0).collect();
    let r = run(&p);
    assert_eq!(r.laps.len(), 12);
    assert!(close(r.laps[3].start_t, 300.0, 0.01));
    assert!(close(r.laps[3].dist, 300.0, 0.5));
    assert!(close(r.laps[3].avg_hr.unwrap(), 150.0, 0.01));

    let single = run(&track(1200, |_| (3.0, None, None, None)));
    assert_eq!(single.laps.len(), 1);
    assert!(close(single.laps[0].dist, single.summary.whole.dist, 0.01));
    assert!(close(single.laps[0].moving_time, single.summary.whole.moving_time, 0.01));
}

#[test]
fn zone_times_sum_to_moving_time_with_heart_rate() {
    let hr = |s: usize| Some([100.0, 120.0, 140.0, 160.0, 180.0][s * 5 / 3001]);
    let r = run(&track(3000, |s| (3.0, None, hr(s), None)));
    let z = r.zones.unwrap();
    assert!(close(z.percent.iter().sum::<f64>(), 100.0, 1e-6));
    assert!(close(z.seconds.iter().sum::<f64>(), r.summary.whole.moving_time, 0.5));
    assert!(z.seconds.iter().all(|s| close(*s, 600.0, 2.0)), "{:?}", z.seconds);
    assert!(close(r.summary.max_hr.unwrap(), 180.0, 0.01));

    let none = run(&track(600, |_| (3.0, None, None, None)));
    assert!(none.zones.is_none());
    assert!(none.summary.whole.avg_hr.is_none() && none.summary.max_hr.is_none());
    assert!(none.summary.whole.dist > 1700.0);
}

#[test]
fn relative_effort_grows_with_duration_and_intensity() {
    let effort = |seconds: usize, hr: f64| run(&track(seconds, |_| (3.0, None, Some(hr), None))).zones.unwrap().effort;
    assert!(close(effort(3600, 150.0) / effort(1800, 150.0), 2.0, 0.01));
    assert!(effort(1800, 175.0) > effort(1800, 140.0));
}

#[test]
fn default_zones_follow_the_maximum_heart_rate() {
    let z = ZoneConfig::from_max(190.0);
    assert_eq!((z.bounds[1], z.bounds[2], z.bounds[4]), (114.0, 133.0, 171.0));
    assert_eq!(z.zone_of(90.0), 0);
    assert_eq!(z.zone_of(133.0), 2);
    assert_eq!(z.zone_of(200.0), 4);
}

#[test]
fn per_leg_cadence_is_doubled() {
    let per_leg = run(&track(600, |_| (3.0, None, None, Some(84.0))));
    assert!(close(per_leg.summary.whole.avg_cad.unwrap(), 168.0, 0.01));
    let both = run(&track(600, |_| (3.0, None, None, Some(170.0))));
    assert!(close(both.summary.whole.avg_cad.unwrap(), 170.0, 0.01));
}

#[test]
fn low_resolution_streams_are_bounded_and_keep_the_ends() {
    let r = run(&track(4 * 3600, |s| (2.5 + (s as f64 / 97.0).sin() * 0.5, None, None, None)));
    assert_eq!(r.streams.len(), 14_401);
    assert!(r.low_res.len() <= 2000, "{}", r.low_res.len());
    assert_eq!(r.low_res.first(), Some(&0));
    assert_eq!(r.low_res.last(), Some(&14_400));
    assert!(r.low_res.windows(2).all(|w| w[0] < w[1]));
}

#[test]
fn partial_records_with_the_same_timestamp_are_merged() {
    let mut p = Parsed::default();
    for s in 0..=600 {
        let time = T0 + f64::from(s);
        p.samples.push(Sample {
            time,
            lat: Some(48.0 + f64::from(s) * 3.0 / M_PER_DEG),
            lon: Some(2.0),
            ..Sample::default()
        });
        p.samples.push(Sample {
            time,
            dist: Some(f64::from(s) * 3.0),
            ..Sample::default()
        });
        p.samples.push(Sample {
            time,
            cad: Some(85.0),
            ..Sample::default()
        });
    }
    let r = run(&p);
    assert_eq!(r.streams.len(), 601);
    assert!(close(r.summary.whole.dist, 1800.0, 0.01));
    assert!(close(r.summary.whole.avg_cad.unwrap(), 170.0, 0.01));
}

const GPX: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1" xmlns:ns3="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
 <metadata><name>Doc name</name></metadata>
 <trk><name>Footing &amp; c&#244;tes</name><type>running</type><trkseg>
  <trkpt lat="48.0000000" lon="2.0"><ele>35.2</ele><time>2026-11-08T08:42:10Z</time><extensions><ns3:TrackPointExtension><ns3:hr>120</ns3:hr><ns3:cad>84</ns3:cad></ns3:TrackPointExtension></extensions></trkpt>
  <trkpt lat="48.0001000" lon="2.0"><ele>35.4</ele><time>2026-11-08T08:42:14Z</time><extensions><ns3:TrackPointExtension><ns3:hr>124</ns3:hr><ns3:cad>85</ns3:cad></ns3:TrackPointExtension></extensions></trkpt>
  <trkpt lat="48.0002000" lon="2.0"><ele>35.5</ele><time>2026-11-08T08:42:18Z</time></trkpt>
 </trkseg></trk>
</gpx>"#;

#[test]
fn gpx_with_extensions_is_read() {
    let (format, acts) = parse(GPX.as_bytes()).unwrap();
    assert_eq!(format, Format::Gpx);
    assert_eq!(acts.len(), 1);
    let a = &acts[0];
    assert_eq!(a.name.as_deref(), Some("Footing & côtes"));
    assert_eq!(a.samples.len(), 3);
    assert_eq!(a.samples[0].time, T0);
    assert_eq!(
        (a.samples[1].hr, a.samples[1].cad, a.samples[1].ele),
        (Some(124.0), Some(85.0), Some(35.4))
    );
    assert_eq!(a.samples[2].hr, None);
    assert!(a.pauses.is_none() && a.lap_starts.is_empty());
}

#[test]
fn format_comes_from_content_and_bad_files_are_rejected() {
    assert_eq!(sniff(GPX.as_bytes()), Some(Format::Gpx));
    assert_eq!(sniff(b"\x0e\x20\x00\x00\x00\x00\x00\x00.FIT\x00\x00"), Some(Format::Fit));
    assert_eq!(parse(b"this is not an activity file").unwrap_err(), ParseError::Unrecognised);
    assert_eq!(parse(b"<html><body>nope</body></html>").unwrap_err(), ParseError::Unrecognised);
    assert!(matches!(
        parse(b"\x0e\x20\x00\x00\xff\x00\x00\x00.FIT\x00\x00garbage").unwrap_err(),
        ParseError::Corrupt(_)
    ));

    let route = r#"<gpx version="1.1"><trk><trkseg><trkpt lat="48.0" lon="2.0"><ele>3</ele></trkpt><trkpt lat="48.1" lon="2.0"/></trkseg></trk></gpx>"#;
    let err = parse(route.as_bytes()).unwrap_err();
    assert_eq!(err, ParseError::NoTiming);
    assert!(err.to_string().contains("route without timing"));
    assert_eq!(parse(b"<gpx version=\"1.1\"></gpx>").unwrap_err(), ParseError::Empty);
}

#[test]
fn gpx_export_round_trips() {
    let original = run(&track(1200, |s| (3.0, Some(40.0 + s as f64 * 0.01), Some(150.0), Some(84.0))));
    let s = &original.streams;
    let points: Vec<ExportPoint> = (0..s.len())
        .map(|i| ExportPoint {
            time: original.start_time + s.t[i],
            lat: s.lat[i].unwrap(),
            lon: s.lon[i].unwrap(),
            ele: s.ele.as_ref().map(|e| e[i]),
            hr: s.hr.as_ref().and_then(|h| h[i]),
            cad: s.cad.as_ref().and_then(|c| c[i]),
        })
        .collect();
    let xml = gpx::write("Sortie <test> & co", &points);
    assert!(xml.contains("version=\"1.1\"") && xml.contains("&lt;test&gt; &amp; co"));

    let (_, acts) = parse(xml.as_bytes()).unwrap();
    assert_eq!(acts[0].name.as_deref(), Some("Sortie <test> & co"));
    let back = run(&acts[0]);
    assert_eq!(back.start_time, original.start_time);
    assert!(close(back.summary.whole.dist / original.summary.whole.dist, 1.0, 0.005));
    assert!(close(back.summary.whole.avg_hr.unwrap(), 150.0, 0.5));
    assert!(close(back.summary.whole.avg_cad.unwrap(), 168.0, 0.5));
}

/// Real exports are private and not committed; this check runs only where they exist.
#[test]
fn real_fit_exports_match_their_own_session_totals() {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../testdata");
    let Ok(entries) = std::fs::read_dir(&dir) else {
        eprintln!("testdata/ not present, skipping");
        return;
    };
    let mut checked = 0;
    for path in entries
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x.eq_ignore_ascii_case("fit")))
    {
        let bytes = std::fs::read(&path).unwrap();
        let session = fitparser::from_bytes(&bytes)
            .unwrap()
            .into_iter()
            .find(|r| r.kind() == fitparser::profile::MesgNum::Session)
            .unwrap();
        let get = |name: &str| -> f64 {
            session
                .fields()
                .iter()
                .find(|f| f.name() == name)
                .unwrap()
                .value()
                .clone()
                .try_into()
                .unwrap()
        };
        let (want_dist, want_timer) = (get("total_distance"), get("total_timer_time"));

        let (format, acts) = parse(&bytes).unwrap();
        assert_eq!(format, Format::Fit);
        let r = run(&acts[0]);
        let w = &r.summary.whole;
        eprintln!(
            "{}: {:.2} km (file {:.2}), moving {:.0} s (file {:.0}), elapsed {:.0} s, {} samples, {} splits, cadence {:?}, gain {:?}",
            path.file_name().unwrap().to_string_lossy(),
            w.dist / 1000.0,
            want_dist / 1000.0,
            w.moving_time,
            want_timer,
            w.elapsed_time,
            r.streams.len(),
            r.splits.len(),
            w.avg_cad.map(f64::round),
            w.gain.map(f64::round)
        );
        assert!(close(w.dist / want_dist, 1.0, 0.015), "distance");
        assert!(close(w.moving_time / want_timer, 1.0, 0.02), "moving time");
        assert!(acts[0].pauses.is_some());
        assert!(r.low_res.len() <= 2000);
        checked += 1;
    }
    eprintln!("checked {checked} real exports");
}
