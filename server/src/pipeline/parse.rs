//! Format detection and FIT decoding.
use std::collections::BTreeMap;

use fitparser::{FitDataRecord, Value, profile::MesgNum};

use super::{Format, ParseError, Parsed, Sample, gpx};

/// Decides the format from the content, never from the file name.
pub fn sniff(bytes: &[u8]) -> Option<Format> {
    if bytes.len() >= 12 && &bytes[8..12] == b".FIT" {
        return Some(Format::Fit);
    }
    let head = &bytes[..bytes.len().min(4096)];
    let head = head.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(head);
    let trimmed = head
        .iter()
        .position(|b| !b.is_ascii_whitespace())
        .map_or(&head[..0], |i| &head[i..]);
    if trimmed.first() == Some(&b'<') && head.windows(4).any(|w| w.eq_ignore_ascii_case(b"<gpx")) {
        return Some(Format::Gpx);
    }
    None
}

/// Decodes a file into one activity per recorded activity it contains.
pub fn parse(bytes: &[u8]) -> Result<(Format, Vec<Parsed>), ParseError> {
    match sniff(bytes) {
        Some(Format::Gpx) => Ok((Format::Gpx, gpx::read(bytes)?)),
        Some(Format::Fit) => Ok((Format::Fit, vec![read_fit(bytes)?])),
        None => Err(ParseError::Unrecognised),
    }
}

fn num(v: &Value) -> Option<f64> {
    let f: f64 = v.clone().try_into().ok()?;
    f.is_finite().then_some(f)
}

fn unix(v: &Value) -> Option<f64> {
    match v {
        Value::Timestamp(t) => Some(t.timestamp() as f64 + f64::from(t.timestamp_subsec_millis()) / 1000.0),
        _ => None,
    }
}

fn field<'a>(r: &'a FitDataRecord, name: &str) -> Option<&'a Value> {
    r.fields().iter().find(|f| f.name() == name).map(|f| f.value())
}

const SEMICIRCLE: f64 = 180.0 / 2_147_483_648.0;

fn read_fit(bytes: &[u8]) -> Result<Parsed, ParseError> {
    let records = fitparser::from_bytes(bytes).map_err(|e| ParseError::Corrupt(e.to_string()))?;
    let mut parsed = Parsed::default();
    // Some writers (Strava on Android) emit several partial records for the same
    // instant: one with the position, one with the distance, one with the cadence.
    // They are merged per timestamp, keeping the last value seen for each channel.
    let mut by_time: BTreeMap<i64, Sample> = BTreeMap::new();
    let mut timer: Vec<(f64, bool)> = Vec::new();
    let mut saw_timer = false;

    for r in &records {
        match r.kind() {
            MesgNum::Record => {
                let Some(t) = field(r, "timestamp").and_then(unix) else { continue };
                let s = by_time.entry((t * 1000.0).round() as i64).or_insert_with(|| Sample {
                    time: t,
                    ..Sample::default()
                });
                for f in r.fields() {
                    let v = num(f.value());
                    match f.name() {
                        "position_lat" => s.lat = v.map(|x| x * SEMICIRCLE).or(s.lat),
                        "position_long" => s.lon = v.map(|x| x * SEMICIRCLE).or(s.lon),
                        "enhanced_altitude" => s.ele = v.or(s.ele),
                        "altitude" if s.ele.is_none() => s.ele = v,
                        "distance" => s.dist = v.or(s.dist),
                        "heart_rate" => s.hr = v.filter(|h| *h > 0.0).or(s.hr),
                        "cadence" => s.cad = v.or(s.cad),
                        _ => {}
                    }
                }
            }
            MesgNum::Event => {
                let is = |name: &str, want: &[&str]| field(r, name).is_some_and(|v| want.contains(&v.to_string().as_str()));
                if is("event", &["timer"])
                    && let Some(t) = field(r, "timestamp").and_then(unix)
                {
                    saw_timer = true;
                    if is("event_type", &["start"]) {
                        timer.push((t, true));
                    } else if is("event_type", &["stop", "stop_all", "stop_disable", "stop_disable_all"]) {
                        timer.push((t, false));
                    }
                }
            }
            MesgNum::Lap => {
                if let Some(t) = field(r, "start_time").and_then(unix) {
                    parsed.lap_starts.push(t);
                }
            }
            MesgNum::Session if parsed.sport.is_none() => {
                parsed.sport = field(r, "sport").map(|v| v.to_string());
            }
            _ => {}
        }
    }

    parsed.samples = by_time.into_values().collect();
    if parsed.samples.is_empty() {
        return Err(ParseError::Empty);
    }
    if saw_timer {
        timer.sort_by(|a, b| a.0.total_cmp(&b.0));
        let mut pauses = Vec::new();
        let mut stopped_at: Option<f64> = None;
        for (t, start) in timer {
            match (start, stopped_at) {
                (false, None) => stopped_at = Some(t),
                (true, Some(from)) => {
                    if t > from {
                        pauses.push((from, t));
                    }
                    stopped_at = None;
                }
                _ => {}
            }
        }
        // A final stop with no restart is the end of the activity, not a pause.
        parsed.pauses = Some(pauses);
    }
    parsed.lap_starts.sort_by(f64::total_cmp);
    parsed.lap_starts.dedup();
    Ok(parsed)
}
