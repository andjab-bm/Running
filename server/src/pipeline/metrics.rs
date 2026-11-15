//! Derives streams, summary, splits, laps, zones and effort from parsed samples.
//!
//! All tunable constants are gathered at the top and documented in `docs/metrics.md`.
use serde::{Deserialize, Serialize};

use super::{ParseError, Parsed, Sample};

/// A gap longer than this between two samples counts as a pause (seconds).
pub const GAP_PAUSE_S: f64 = 30.0;
/// Below this speed the runner is considered stopped (m/s, i.e. 1.5 km/h).
pub const STOP_SPEED_MPS: f64 = 1.5 / 3.6;
/// A stop must last at least this long to be excluded from moving time (seconds).
pub const STOP_MIN_S: f64 = 10.0;
/// Half-width of the time window used to smooth speed (seconds).
pub const SPEED_WINDOW_S: f64 = 5.0;
/// Half-width of the distance window used to smooth elevation (metres).
pub const ELE_WINDOW_M: f64 = 30.0;
/// Elevation must change by this much before it counts as gain or loss (metres).
pub const ELE_HYSTERESIS_M: f64 = 2.0;
/// Half-width of the distance window used to measure slope (metres).
pub const GRADE_WINDOW_M: f64 = 15.0;
/// Slopes are clamped to this absolute value before grade adjustment.
pub const GRADE_CLAMP: f64 = 0.30;
/// Share of samples that must carry a channel for it to be used.
pub const MIN_ELE_COVERAGE: f64 = 0.5;
pub const MIN_DEVICE_DIST_COVERAGE: f64 = 0.8;
/// A device distance that stays flat for up to this long is interpolated (seconds).
pub const DIST_PLATEAU_MAX_S: f64 = 8.0;
/// Cadence medians below this are per-leg values and are doubled (steps/min).
pub const PER_LEG_CADENCE_BELOW: f64 = 120.0;
/// Largest number of points in the reduced-resolution streams.
pub const LOW_RES_POINTS: usize = 2000;
/// A trailing split shorter than this is dropped (metres).
pub const MIN_SPLIT_M: f64 = 1.0;
const EARTH_RADIUS_M: f64 = 6_371_000.0;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Units {
    Metric,
    Imperial,
}

impl Units {
    pub fn split_len(self) -> f64 {
        match self {
            Units::Metric => 1000.0,
            Units::Imperial => 1609.344,
        }
    }
}

/// Five contiguous heart-rate zones: `bounds[z]..bounds[z + 1]` is zone `z + 1`.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ZoneConfig {
    pub bounds: [f64; 6],
}

impl ZoneConfig {
    /// Default zones at 50–60–70–80–90–100 % of the maximum heart rate.
    pub fn from_max(hr_max: f64) -> Self {
        Self {
            bounds: [0.5, 0.6, 0.7, 0.8, 0.9, 1.0].map(|f| (f * hr_max).round()),
        }
    }

    /// Zone index 0..=4. Values under zone 1 count as zone 1, values over the maximum as zone 5.
    pub fn zone_of(&self, hr: f64) -> usize {
        (1..5).rev().find(|z| hr >= self.bounds[*z]).unwrap_or(0)
    }
}

/// Index-aligned series for one activity. Interval `i` is the stretch between samples `i - 1` and `i`.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Streams {
    /// Seconds since the first sample.
    pub t: Vec<f64>,
    /// Cumulative distance in metres.
    pub dist: Vec<f64>,
    pub lat: Vec<Option<f64>>,
    pub lon: Vec<Option<f64>>,
    /// Smoothed elevation in metres, when the file has elevation.
    pub ele: Option<Vec<f64>>,
    /// Smoothed speed in m/s.
    pub speed: Vec<f64>,
    pub hr: Option<Vec<Option<f64>>>,
    /// Cadence in steps per minute (both legs).
    pub cad: Option<Vec<Option<f64>>>,
    /// Whether interval `i` counts as moving. `moving[0]` is always false.
    pub moving: Vec<bool>,
    /// Slope of interval `i` as a ratio (0.05 = 5 %), 0 without elevation.
    pub grade: Vec<f64>,
    /// Elevation gained and lost in interval `i`, after hysteresis.
    pub gain: Vec<f64>,
    pub loss: Vec<f64>,
}

impl Streams {
    pub fn len(&self) -> usize {
        self.t.len()
    }
    pub fn is_empty(&self) -> bool {
        self.t.is_empty()
    }
    pub fn total_dist(&self) -> f64 {
        self.dist.last().copied().unwrap_or(0.0)
    }
    pub fn elapsed(&self) -> f64 {
        self.t.last().copied().unwrap_or(0.0)
    }
}

/// Metrics of one stretch of the activity: a split, a lap, or the whole run.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Segment {
    /// Seconds from the start of the activity to the start of the segment.
    pub start_t: f64,
    pub dist: f64,
    pub moving_time: f64,
    pub elapsed_time: f64,
    /// Average speed over moving time, m/s.
    pub speed: f64,
    /// Equivalent flat-ground speed, m/s.
    pub gap_speed: Option<f64>,
    pub gain: Option<f64>,
    pub loss: Option<f64>,
    pub avg_hr: Option<f64>,
    pub avg_cad: Option<f64>,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Summary {
    pub whole: Segment,
    pub max_hr: Option<f64>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ZoneDistribution {
    pub seconds: [f64; 5],
    pub percent: [f64; 5],
    /// Σ minutes in zone × zone number.
    pub effort: f64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Processed {
    /// Unix time of the first sample.
    pub start_time: f64,
    pub name: Option<String>,
    pub sport: Option<String>,
    pub streams: Streams,
    pub summary: Summary,
    pub splits: Vec<Segment>,
    pub laps: Vec<Segment>,
    pub zones: Option<ZoneDistribution>,
    /// Indices of the reduced-resolution streams.
    pub low_res: Vec<usize>,
}

fn haversine(a: (f64, f64), b: (f64, f64)) -> f64 {
    let (la1, la2) = (a.0.to_radians(), b.0.to_radians());
    let (dla, dlo) = (la2 - la1, (b.1 - a.1).to_radians());
    let h = (dla / 2.0).sin().powi(2) + la1.cos() * la2.cos() * (dlo / 2.0).sin().powi(2);
    2.0 * EARTH_RADIUS_M * h.sqrt().min(1.0).asin()
}

/// Energy cost of running on a slope relative to flat ground (Minetti et al., 2002).
pub fn grade_factor(grade: f64) -> f64 {
    let g = grade.clamp(-GRADE_CLAMP, GRADE_CLAMP);
    (155.4 * g.powi(5) - 30.4 * g.powi(4) - 43.3 * g.powi(3) + 46.3 * g * g + 19.5 * g + 3.6) / 3.6
}

fn valid_fix(s: &Sample) -> Option<(f64, f64)> {
    match (s.lat, s.lon) {
        (Some(la), Some(lo)) if la.abs() <= 90.0 && lo.abs() <= 180.0 && !(la == 0.0 && lo == 0.0) => Some((la, lo)),
        _ => None,
    }
}

/// Normalises samples and derives every per-sample series.
pub fn build_streams(parsed: &Parsed) -> Result<Streams, ParseError> {
    // Sort and merge samples sharing a timestamp.
    let mut samples: Vec<Sample> = parsed.samples.iter().filter(|s| s.time.is_finite()).cloned().collect();
    samples.sort_by(|a, b| a.time.total_cmp(&b.time));
    let mut merged: Vec<Sample> = Vec::with_capacity(samples.len());
    for s in samples {
        match merged.last_mut() {
            Some(last) if (s.time - last.time).abs() < 1e-3 => {
                last.lat = s.lat.or(last.lat);
                last.lon = s.lon.or(last.lon);
                last.ele = s.ele.or(last.ele);
                last.dist = s.dist.or(last.dist);
                last.hr = s.hr.or(last.hr);
                last.cad = s.cad.or(last.cad);
            }
            _ => merged.push(s),
        }
    }
    let n = merged.len();
    if n < 2 {
        return Err(ParseError::Empty);
    }
    let t0 = merged[0].time;
    let t: Vec<f64> = merged.iter().map(|s| s.time - t0).collect();
    let fixes: Vec<Option<(f64, f64)>> = merged.iter().map(valid_fix).collect();

    // Distance: trust the device when it reports one, otherwise integrate positions.
    let with_dist = merged.iter().filter(|s| s.dist.is_some()).count();
    let mut dist = vec![0.0; n];
    if with_dist as f64 >= MIN_DEVICE_DIST_COVERAGE * n as f64 {
        let base = merged.iter().find_map(|s| s.dist).unwrap_or(0.0);
        let mut cur: f64 = 0.0;
        for i in 0..n {
            if let Some(d) = merged[i].dist {
                cur = cur.max(d - base);
            }
            dist[i] = cur;
        }
        // Some devices refresh the distance only every few seconds, leaving it flat in
        // between. Spread each step over the samples it covers, unless the plateau is
        // long enough to be a real stop.
        let mut k0 = 0;
        for k1 in 1..n {
            if dist[k1] > dist[k0] {
                if k1 - k0 > 1 && t[k1] - t[k0] <= DIST_PLATEAU_MAX_S {
                    let (d0, d1, t0, t1) = (dist[k0], dist[k1], t[k0], t[k1]);
                    for i in k0 + 1..k1 {
                        dist[i] = d0 + (d1 - d0) * (t[i] - t0) / (t1 - t0);
                    }
                }
                k0 = k1;
            }
        }
    } else {
        let mut last: Option<(f64, f64)> = None;
        let mut cur = 0.0;
        for i in 0..n {
            if let Some(f) = fixes[i] {
                if let Some(prev) = last {
                    cur += haversine(prev, f);
                }
                last = Some(f);
            }
            dist[i] = cur;
        }
        if last.is_none() {
            return Err(ParseError::Empty);
        }
    }

    // Moving mask.
    let mut moving = vec![false; n];
    match &parsed.pauses {
        Some(pauses) => {
            for i in 1..n {
                let (a, b) = (merged[i - 1].time, merged[i].time);
                let paused: f64 = pauses.iter().map(|(p0, p1)| (b.min(*p1) - a.max(*p0)).max(0.0)).sum();
                moving[i] = paused <= 0.5 * (b - a);
            }
        }
        None => {
            for i in 1..n {
                moving[i] = t[i] - t[i - 1] <= GAP_PAUSE_S;
            }
            let slow = |i: usize| (dist[i] - dist[i - 1]) / (t[i] - t[i - 1]) < STOP_SPEED_MPS;
            let mut i = 1;
            while i < n {
                if moving[i] && slow(i) {
                    let start = i;
                    while i < n && moving[i] && slow(i) {
                        i += 1;
                    }
                    if t[i - 1] - t[start - 1] >= STOP_MIN_S {
                        moving[start..i].iter_mut().for_each(|m| *m = false);
                    }
                } else {
                    i += 1;
                }
            }
        }
    }

    // Smoothed speed: distance over moving time in a sliding time window.
    let mut moving_cum = vec![0.0; n];
    for i in 1..n {
        moving_cum[i] = moving_cum[i - 1] + if moving[i] { t[i] - t[i - 1] } else { 0.0 };
    }
    let mut speed = vec![0.0; n];
    let (mut lo, mut hi) = (0usize, 0usize);
    for i in 0..n {
        while t[lo] < t[i] - SPEED_WINDOW_S {
            lo += 1;
        }
        while hi + 1 < n && t[hi + 1] <= t[i] + SPEED_WINDOW_S {
            hi += 1;
        }
        let dt = moving_cum[hi] - moving_cum[lo];
        speed[i] = if dt > 0.0 { (dist[hi] - dist[lo]) / dt } else { 0.0 };
    }

    // Elevation: fill gaps, median of five, then a mean over a distance window.
    let with_ele = merged.iter().filter(|s| s.ele.is_some()).count();
    let (mut grade, mut gain, mut loss) = (vec![0.0; n], vec![0.0; n], vec![0.0; n]);
    let ele = if with_ele as f64 >= MIN_ELE_COVERAGE * n as f64 {
        let first = merged.iter().find_map(|s| s.ele).unwrap_or(0.0);
        let mut raw = Vec::with_capacity(n);
        let mut cur = first;
        for s in &merged {
            cur = s.ele.unwrap_or(cur);
            raw.push(cur);
        }
        let med: Vec<f64> = (0..n)
            .map(|i| {
                let mut w: Vec<f64> = raw[i.saturating_sub(2)..(i + 3).min(n)].to_vec();
                w.sort_by(f64::total_cmp);
                w[w.len() / 2]
            })
            .collect();
        let mut prefix = vec![0.0; n + 1];
        for i in 0..n {
            prefix[i + 1] = prefix[i] + med[i];
        }
        let window = |half: f64| {
            let (mut lo, mut hi) = (0usize, 0usize);
            (0..n)
                .map(|i| {
                    while dist[lo] < dist[i] - half {
                        lo += 1;
                    }
                    hi = hi.max(i);
                    while hi + 1 < n && dist[hi + 1] <= dist[i] + half {
                        hi += 1;
                    }
                    (lo, hi)
                })
                .collect::<Vec<_>>()
        };
        let smooth: Vec<f64> = window(ELE_WINDOW_M)
            .into_iter()
            .map(|(lo, hi)| (prefix[hi + 1] - prefix[lo]) / (hi + 1 - lo) as f64)
            .collect();
        let mut anchor = smooth[0];
        for i in 1..n {
            let d = smooth[i] - anchor;
            if d >= ELE_HYSTERESIS_M {
                gain[i] = d;
                anchor = smooth[i];
            } else if d <= -ELE_HYSTERESIS_M {
                loss[i] = -d;
                anchor = smooth[i];
            }
        }
        for (i, (lo, hi)) in window(GRADE_WINDOW_M).into_iter().enumerate() {
            let dd = dist[hi] - dist[lo];
            grade[i] = if dd >= 1.0 {
                ((smooth[hi] - smooth[lo]) / dd).clamp(-GRADE_CLAMP, GRADE_CLAMP)
            } else {
                0.0
            };
        }
        Some(smooth)
    } else {
        None
    };

    let hr = merged
        .iter()
        .any(|s| s.hr.is_some())
        .then(|| merged.iter().map(|s| s.hr).collect::<Vec<_>>());
    let cad = merged.iter().any(|s| s.cad.is_some_and(|c| c > 0.0)).then(|| {
        let mut nonzero: Vec<f64> = merged.iter().filter_map(|s| s.cad).filter(|c| *c > 0.0).collect();
        nonzero.sort_by(f64::total_cmp);
        let factor = if nonzero[nonzero.len() / 2] < PER_LEG_CADENCE_BELOW {
            2.0
        } else {
            1.0
        };
        merged.iter().map(|s| s.cad.map(|c| c * factor)).collect::<Vec<_>>()
    });

    Ok(Streams {
        t,
        dist,
        lat: fixes.iter().map(|f| f.map(|p| p.0)).collect(),
        lon: fixes.iter().map(|f| f.map(|p| p.1)).collect(),
        ele,
        speed,
        hr,
        cad,
        moving,
        grade,
        gain,
        loss,
    })
}

#[derive(Clone, Copy)]
pub enum Axis {
    Dist,
    Time,
}

/// Metrics over `[a, b)` measured along `axis`, splitting boundary intervals proportionally.
pub fn segment(s: &Streams, axis: Axis, a: f64, b: f64) -> Segment {
    let x = match axis {
        Axis::Dist => &s.dist,
        Axis::Time => &s.t,
    };
    let end = x.last().copied().unwrap_or(0.0);
    let mut seg = Segment::default();
    let (mut gap_dist, mut hr_sum, mut hr_t, mut cad_sum, mut cad_t, mut gain, mut loss) = (0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    let mut started = false;
    for i in 1..s.len() {
        let (x0, x1) = (x[i - 1], x[i]);
        let f = if x1 > x0 {
            ((x1.min(b) - x0.max(a)).max(0.0)) / (x1 - x0)
        } else if x0 >= a && (x0 < b || (b >= end && x0 <= b)) {
            1.0
        } else {
            0.0
        };
        if f <= 0.0 {
            continue;
        }
        let dt = (s.t[i] - s.t[i - 1]) * f;
        let dd = (s.dist[i] - s.dist[i - 1]) * f;
        if !started {
            started = true;
            seg.start_t = s.t[i - 1] + (s.t[i] - s.t[i - 1]) * if x1 > x0 { ((a - x0) / (x1 - x0)).clamp(0.0, 1.0) } else { 0.0 };
        }
        seg.elapsed_time += dt;
        seg.dist += dd;
        gain += s.gain[i] * f;
        loss += s.loss[i] * f;
        if s.moving[i] {
            seg.moving_time += dt;
            gap_dist += dd * grade_factor(s.grade[i]);
            if let Some(h) = s.hr.as_ref().and_then(|h| h[i]) {
                hr_sum += h * dt;
                hr_t += dt;
            }
            if let Some(c) = s.cad.as_ref().and_then(|c| c[i]).filter(|c| *c > 0.0) {
                cad_sum += c * dt;
                cad_t += dt;
            }
        }
    }
    if seg.moving_time > 0.0 {
        seg.speed = seg.dist / seg.moving_time;
    }
    if s.ele.is_some() {
        seg.gain = Some(gain);
        seg.loss = Some(loss);
        seg.gap_speed = (seg.moving_time > 0.0).then(|| gap_dist / seg.moving_time);
    }
    seg.avg_hr = (hr_t > 0.0).then(|| hr_sum / hr_t);
    seg.avg_cad = (cad_t > 0.0).then(|| cad_sum / cad_t);
    seg
}

/// Consecutive splits of one distance unit, with a final partial split.
pub fn splits(s: &Streams, units: Units) -> Vec<Segment> {
    let (len, total) = (units.split_len(), s.total_dist());
    let mut out = Vec::new();
    let mut k = 0.0;
    while k * len < total - MIN_SPLIT_M || (k == 0.0 && total > 0.0) {
        out.push(segment(s, Axis::Dist, k * len, ((k + 1.0) * len).min(total)));
        k += 1.0;
    }
    out
}

/// Laps as recorded in the source; a single lap covering everything when there are none.
pub fn laps(s: &Streams, lap_starts: &[f64], start_time: f64) -> Vec<Segment> {
    let end = s.elapsed();
    let mut starts: Vec<f64> = lap_starts
        .iter()
        .map(|t| (t - start_time).clamp(0.0, end))
        .filter(|t| *t < end)
        .collect();
    starts.sort_by(f64::total_cmp);
    starts.dedup_by(|a, b| (*a - *b).abs() < 0.5);
    if starts.first().is_none_or(|f| *f > 1.0) {
        starts.insert(0, 0.0);
    } else {
        starts[0] = 0.0;
    }
    (0..starts.len())
        .map(|i| segment(s, Axis::Time, starts[i], starts.get(i + 1).copied().unwrap_or(end)))
        .collect()
}

/// Moving time per heart-rate zone and the relative effort score.
pub fn zones(s: &Streams, cfg: &ZoneConfig) -> Option<ZoneDistribution> {
    let hr = s.hr.as_ref()?;
    let mut seconds = [0.0; 5];
    for i in 1..s.len() {
        if s.moving[i]
            && let Some(h) = hr[i]
        {
            seconds[cfg.zone_of(h)] += s.t[i] - s.t[i - 1];
        }
    }
    let total: f64 = seconds.iter().sum();
    if total <= 0.0 {
        return None;
    }
    let effort = seconds.iter().enumerate().map(|(z, sec)| sec / 60.0 * (z + 1) as f64).sum();
    Some(ZoneDistribution {
        seconds,
        percent: seconds.map(|sec| sec / total * 100.0),
        effort,
    })
}

/// Picks at most `max` evenly spaced indices, always keeping the first and last sample.
///
/// Even spacing is deliberate: shape-preserving methods favour local extremes,
/// which exaggerates GPS noise on a pace curve.
pub fn downsample(n: usize, max: usize) -> Vec<usize> {
    if n <= max || max < 2 {
        return (0..n).collect();
    }
    let step = (n - 1) as f64 / (max - 1) as f64;
    let mut out: Vec<usize> = (0..max).map(|k| (k as f64 * step).round() as usize).collect();
    if let Some(last) = out.last_mut() {
        *last = n - 1;
    }
    out.dedup();
    out
}

/// Runs the whole pipeline on one parsed activity.
pub fn process(parsed: &Parsed, zone_cfg: &ZoneConfig, units: Units) -> Result<Processed, ParseError> {
    let streams = build_streams(parsed)?;
    let start_time = parsed
        .samples
        .iter()
        .map(|s| s.time)
        .filter(|t| t.is_finite())
        .fold(f64::INFINITY, f64::min);
    let whole = segment(&streams, Axis::Time, 0.0, streams.elapsed());
    let max_hr = streams.hr.as_ref().and_then(|h| h.iter().flatten().copied().reduce(f64::max));
    let low_res = downsample(streams.len(), LOW_RES_POINTS);
    Ok(Processed {
        start_time,
        name: parsed.name.clone(),
        sport: parsed.sport.clone(),
        splits: splits(&streams, units),
        laps: laps(&streams, &parsed.lap_starts, start_time),
        zones: zones(&streams, zone_cfg),
        summary: Summary { whole, max_hr },
        low_res,
        streams,
    })
}
