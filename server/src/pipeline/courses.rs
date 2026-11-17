//! Recurring portions of route ("segments" for the runner; `courses` in the code, because
//! `segments` already names splits and laps): geometry, matching and detection.
//!
//! A trace is resampled every [`RESAMPLE_M`] along its distance. Two points are the same
//! place when they lie within [`TOLERANCE_M`] of each other and head the same way.
use std::collections::HashSet;

use serde::{Deserialize, Serialize};

use super::metrics::Streams;

/// Spacing of the resampled trace, in metres.
pub const RESAMPLE_M: f64 = 20.0;
/// Two passages this close are on the same path: phone GPS, or the other side of a road.
pub const TOLERANCE_M: f64 = 25.0;
/// Side of the grid cells used to find who else passed somewhere.
pub const CELL_M: f64 = 25.0;
/// Headings further apart than this are different directions of travel.
pub const MAX_HEADING_DIFF_DEG: f64 = 60.0;
/// A portion becomes a segment once covered in this many activities...
pub const MIN_TRAVERSALS: usize = 3;
/// ...over at least this length.
pub const MIN_COURSE_M: f64 = 300.0;
/// Share of a segment's points an activity must pass, in order, to have covered it.
pub const MATCH_COVERAGE: f64 = 0.9;
/// How far ahead along the segment the next point of the activity may land (in points).
const LOOKAHEAD: usize = 5;
/// Consecutive points of the activity allowed off the segment before the passage is abandoned.
const MAX_MISSES: usize = 4;
/// Holes this short (in points) in a shared stretch are bridged.
const BRIDGE_POINTS: usize = 2;
/// Window (in points) over which the runs sharing a stretch are compared to find where habits diverge.
const HABIT_WINDOW: usize = 5;
/// Points dropped at each end of a new segment: where paths join and part, headings are
/// still turning and passages would not be recognised.
const TRIM_POINTS: usize = 2;
/// A stretch that comes back onto a point it passed at least this many points earlier
/// has closed a lap, and is cut there.
const LAP_GAP_POINTS: usize = 10;
/// A segment ending this close to its start is a loop.
pub const LOOP_M: f64 = 100.0;

const EARTH_RADIUS_M: f64 = 6_371_000.0;
const M_PER_DEG: f64 = EARTH_RADIUS_M * std::f64::consts::PI / 180.0;
/// Width of a row of cells in the cell number: neighbours are ±1 and ±`CELL_ROW`.
pub const CELL_ROW: i64 = 1 << 32;
const CELL_OFFSET: i64 = 1 << 30;

/// One point of a resampled trace.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct TracePoint {
    pub lat: f64,
    pub lon: f64,
    /// Metres from the start of the trace it was taken from.
    pub dist: f64,
    /// Direction of travel, degrees clockwise from north.
    pub heading: f64,
}

impl TracePoint {
    /// Number of the grid cell the point falls in.
    pub fn cell(&self) -> i64 {
        let y = (self.lat * M_PER_DEG / CELL_M).floor() as i64 + CELL_OFFSET;
        let x = (self.lon * M_PER_DEG * self.lat.to_radians().cos() / CELL_M).floor() as i64 + CELL_OFFSET;
        y * CELL_ROW + x
    }

    /// Direction of travel as one of eight sectors, 0 being north.
    pub fn sector(&self) -> i64 {
        ((self.heading + 22.5).rem_euclid(360.0) / 45.0) as i64
    }
}

/// Whether two sector numbers are the same direction of travel, give or take one sector.
pub fn sectors_agree(a: i64, b: i64) -> bool {
    matches!((a - b).rem_euclid(8), 0 | 1 | 7)
}

pub fn metres_between(a: (f64, f64), b: (f64, f64)) -> f64 {
    let (dlat, dlon) = ((b.0 - a.0).to_radians(), (b.1 - a.1).to_radians());
    let h = (dlat / 2.0).sin().powi(2) + a.0.to_radians().cos() * b.0.to_radians().cos() * (dlon / 2.0).sin().powi(2);
    2.0 * EARTH_RADIUS_M * h.sqrt().asin()
}

fn heading(a: (f64, f64), b: (f64, f64)) -> f64 {
    let north = b.0 - a.0;
    let east = (b.1 - a.1) * a.0.to_radians().cos();
    east.atan2(north).to_degrees().rem_euclid(360.0)
}

fn same_place(a: &TracePoint, b: &TracePoint) -> bool {
    // Called for very many pairs: reject on the north-south gap first, and measure on a
    // flat projection, which is exact to the centimetre over a few tens of metres.
    let north = (a.lat - b.lat) * M_PER_DEG;
    if north.abs() > TOLERANCE_M {
        return false;
    }
    let turn = (a.heading - b.heading).rem_euclid(360.0);
    if turn.min(360.0 - turn) > MAX_HEADING_DIFF_DEG {
        return false;
    }
    let east = (a.lon - b.lon) * M_PER_DEG * a.lat.to_radians().cos();
    north * north + east * east <= TOLERANCE_M * TOLERANCE_M
}

/// The trace of an activity, one point every [`RESAMPLE_M`] of its distance.
pub fn resample(s: &Streams) -> Vec<TracePoint> {
    let fixes: Vec<(f64, f64, f64)> = (0..s.len()).filter_map(|i| Some((s.dist[i], s.lat[i]?, s.lon[i]?))).collect();
    let mut out: Vec<TracePoint> = Vec::new();
    let Some(first) = fixes.first() else {
        return out;
    };
    let mut target = (first.0 / RESAMPLE_M).ceil() * RESAMPLE_M;
    for w in fixes.windows(2) {
        let (a, b) = (w[0], w[1]);
        while target <= b.0 {
            let f = if b.0 > a.0 { (target - a.0) / (b.0 - a.0) } else { 0.0 };
            out.push(TracePoint {
                lat: a.1 + (b.1 - a.1) * f,
                lon: a.2 + (b.2 - a.2) * f,
                dist: target,
                heading: 0.0,
            });
            target += RESAMPLE_M;
        }
    }
    set_headings(&mut out);
    out
}

/// Gives each point the direction from the point before it to the point after it.
pub fn set_headings(points: &mut [TracePoint]) {
    let n = points.len();
    for i in 0..n {
        let (a, b) = (points[i.saturating_sub(1)], points[(i + 1).min(n - 1)]);
        points[i].heading = heading((a.lat, a.lon), (b.lat, b.lon));
    }
}

/// One complete passage of an activity along a segment, as distances in the activity.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Passage {
    pub start_dist: f64,
    pub end_dist: f64,
}

/// Every complete passage of `activity` along `course`, in the segment's direction.
///
/// A passage starts where the activity reaches the first point of the segment, then must
/// meet at least [`MATCH_COVERAGE`] of its points in order and reach its last point.
/// Leaving the segment, or covering it the other way round, gives no passage.
pub fn passages(activity: &[TracePoint], course: &[TracePoint]) -> Vec<Passage> {
    let mut out = Vec::new();
    let (Some(first), true) = (course.first(), course.len() >= 2) else {
        return out;
    };
    let last = course.len() - 1;
    let mut i = 0;
    while i < activity.len() {
        if !same_place(&activity[i], first) {
            i += 1;
            continue;
        }
        // Start from the point of the activity closest to the start of the segment.
        let gap = |p: &TracePoint| metres_between((p.lat, p.lon), (first.lat, first.lon));
        while i + 1 < activity.len() && same_place(&activity[i + 1], first) && gap(&activity[i + 1]) < gap(&activity[i]) {
            i += 1;
        }
        let mut met = vec![false; course.len()];
        met[0] = true;
        let (mut at, mut misses, mut end) = (0, 0, None);
        for (j, p) in activity.iter().enumerate().skip(i + 1) {
            // The nearest point of the segment ahead, not the first one within reach:
            // otherwise progress along the segment would lag one point behind the runner.
            let hit = (at..=(at + LOOKAHEAD).min(last))
                .filter(|k| same_place(p, &course[*k]))
                .min_by(|a, b| {
                    let d = |k: &usize| metres_between((p.lat, p.lon), (course[*k].lat, course[*k].lon));
                    d(a).total_cmp(&d(b))
                });
            match hit {
                Some(k) => {
                    met[k] = true;
                    at = k;
                    misses = 0;
                    if k == last {
                        end = Some(j);
                        break;
                    }
                }
                None => {
                    misses += 1;
                    if misses > MAX_MISSES {
                        break;
                    }
                }
            }
        }
        match end {
            Some(j) if met.iter().filter(|m| **m).count() as f64 >= MATCH_COVERAGE * course.len() as f64 => {
                out.push(Passage {
                    start_dist: activity[i].dist,
                    end_dist: activity[j].dist,
                });
                i = j;
            }
            _ => i += 1,
        }
    }
    out
}

/// Which points of `activity` already belong to one of the known segments.
pub fn covered(activity: &[TracePoint], courses: &[Vec<TracePoint>]) -> Vec<bool> {
    activity
        .iter()
        .map(|p| courses.iter().any(|c| c.iter().any(|q| same_place(p, q))))
        .collect()
}

/// Stretches of a new activity that should become segments, as inclusive index ranges.
///
/// `sharing[i]` holds the other activities of the profile that pass point `i` in the same
/// direction. A stretch qualifies where at least `MIN_TRAVERSALS - 1` others do, where no
/// known segment already runs, and over at least [`MIN_COURSE_M`]. It is cut where the
/// runs sharing it change by more than half: a junction where habits diverge. Each stretch
/// is returned without its first and last [`TRIM_POINTS`] points.
pub fn candidates(activity: &[TracePoint], sharing: &[HashSet<i64>], covered: &[bool]) -> Vec<(usize, usize)> {
    let n = activity.len();
    let mut ok: Vec<bool> = (0..n).map(|i| !covered[i] && sharing[i].len() >= MIN_TRAVERSALS - 1).collect();
    // Bridge short holes left by position noise, never across a known segment.
    let mut i = 0;
    while i < n {
        if ok[i] {
            i += 1;
            continue;
        }
        let hole_end = (i..n).find(|k| ok[*k]).unwrap_or(n);
        if i > 0 && hole_end < n && hole_end - i <= BRIDGE_POINTS && !covered[i..hole_end].iter().any(|c| *c) {
            ok[i..hole_end].iter_mut().for_each(|v| *v = true);
        }
        i = hole_end;
    }

    let union = |a: usize, b: usize| -> HashSet<i64> { sharing[a..b].iter().flatten().copied().collect() };
    let mut out = Vec::new();
    let mut push = |a: usize, b: usize| {
        if b < a + 2 * TRIM_POINTS {
            return;
        }
        let (a, b) = (a + TRIM_POINTS, b - TRIM_POINTS);
        if activity[b].dist - activity[a].dist >= MIN_COURSE_M {
            out.push((a, b));
        }
    };
    let mut i = 0;
    while i < n {
        if !ok[i] {
            i += 1;
            continue;
        }
        let end = (i..n).find(|k| !ok[*k]).unwrap_or(n); // exclusive
        let mut start = i;
        let mut k = i + 1;
        while k < end {
            // Back on its own path (laps of a loop): the segment is one lap, not all of them.
            let lapped = k >= start + LAP_GAP_POINTS && activity[start..=k - LAP_GAP_POINTS].iter().any(|p| same_place(p, &activity[k]));
            let diverged = k >= start + HABIT_WINDOW && k + HABIT_WINDOW <= end && {
                let (before, after) = (union(k - HABIT_WINDOW, k), union(k, k + HABIT_WINDOW));
                (before.intersection(&after).count() as f64) < 0.5 * before.len().max(after.len()) as f64
            };
            if lapped || diverged {
                push(start, k - 1);
                start = k;
                k += if diverged { HABIT_WINDOW } else { 1 };
            } else {
                k += 1;
            }
        }
        push(start, end - 1);
        i = end;
    }
    out
}

/// A name for a new segment, from its length and where it heads: "1,2 km · vers l'est".
pub fn default_name(points: &[TracePoint]) -> String {
    let (Some(a), Some(b)) = (points.first(), points.last()) else {
        return "Segment".into();
    };
    let metres = b.dist - a.dist;
    let length = if metres >= 1000.0 {
        format!("{:.1} km", metres / 1000.0).replace('.', ",")
    } else {
        format!("{:.0} m", (metres / 10.0).round() * 10.0)
    };
    if metres_between((a.lat, a.lon), (b.lat, b.lon)) <= LOOP_M {
        return format!("{length} · boucle");
    }
    let towards = [
        "vers le nord",
        "vers le nord-est",
        "vers l'est",
        "vers le sud-est",
        "vers le sud",
        "vers le sud-ouest",
        "vers l'ouest",
        "vers le nord-ouest",
    ];
    let sector = ((heading((a.lat, a.lon), (b.lat, b.lon)) + 22.5).rem_euclid(360.0) / 45.0) as usize;
    format!("{length} · {}", towards[sector % 8])
}

#[cfg(test)]
mod tests {
    use super::*;

    const LAT0: f64 = 48.0;
    const LON0: f64 = 2.0;

    /// A resampled trace through waypoints given in metres east and north of an origin.
    fn trace(waypoints: &[(f64, f64)]) -> Vec<TracePoint> {
        let to_deg = |(e, n): (f64, f64)| (LAT0 + n / M_PER_DEG, LON0 + e / (M_PER_DEG * LAT0.to_radians().cos()));
        let mut s = Streams::default();
        let mut d = 0.0;
        for w in waypoints.windows(2) {
            let len = ((w[1].0 - w[0].0).powi(2) + (w[1].1 - w[0].1).powi(2)).sqrt();
            let steps = (len / 3.0).ceil() as usize;
            for k in 0..steps {
                let f = k as f64 / steps as f64;
                let (lat, lon) = to_deg((w[0].0 + (w[1].0 - w[0].0) * f, w[0].1 + (w[1].1 - w[0].1) * f));
                s.lat.push(Some(lat));
                s.lon.push(Some(lon));
                s.dist.push(d + len * f);
                s.t.push(s.t.len() as f64);
            }
            d += len;
        }
        resample(&s)
    }

    fn river() -> Vec<TracePoint> {
        trace(&[(0.0, 0.0), (1200.0, 0.0)])
    }

    #[test]
    fn resampling_is_regular_and_headed() {
        let t = river();
        assert_eq!(t.len(), 60);
        assert!((t[10].dist - 200.0).abs() < 1e-9);
        assert!(t.iter().all(|p| (p.heading - 90.0).abs() < 1.0), "east");
        assert_eq!(t[5].sector(), 2);
        // Neighbouring points fall in the same or an adjacent cell.
        assert!(t.windows(2).all(|w| (w[1].cell() - w[0].cell()).abs() <= 1));
    }

    #[test]
    fn a_run_over_the_segment_is_a_passage() {
        let run = trace(&[(0.0, -400.0), (0.0, 0.0), (1200.0, 0.0), (1200.0, 400.0)]);
        let found = passages(&run, &river());
        assert_eq!(found.len(), 1);
        assert!((found[0].start_dist - 400.0).abs() <= 30.0, "{:?}", found[0]);
        assert!((found[0].end_dist - found[0].start_dist - 1180.0).abs() <= 40.0, "{:?}", found[0]);
    }

    #[test]
    fn turning_back_halfway_is_not_a_passage() {
        let run = trace(&[(0.0, 0.0), (600.0, 0.0), (0.0, 0.0)]);
        assert_eq!(passages(&run, &river()), []);
    }

    #[test]
    fn the_other_direction_is_not_a_passage() {
        let run = trace(&[(1200.0, 0.0), (0.0, 0.0)]);
        assert_eq!(passages(&run, &river()), []);
    }

    #[test]
    fn the_other_side_of_the_road_is_the_same_segment() {
        let run = trace(&[(-100.0, 10.0), (1300.0, 10.0)]);
        assert_eq!(passages(&run, &river()).len(), 1);
        // Forty metres to the side is another path.
        let far = trace(&[(-100.0, 40.0), (1300.0, 40.0)]);
        assert_eq!(passages(&far, &river()), []);
    }

    #[test]
    fn two_laps_are_two_passages() {
        let run = trace(&[(0.0, 0.0), (1200.0, 0.0), (1200.0, 200.0), (0.0, 200.0), (0.0, 0.0), (1200.0, 0.0)]);
        let found = passages(&run, &river());
        assert_eq!(found.len(), 2, "{found:?} last={:?} n={}", run.last(), run.len());
        assert!(found[1].start_dist > found[0].end_dist);
    }

    #[test]
    fn a_shortcut_through_the_segment_is_not_a_passage() {
        // Leaves the path for its middle third.
        let run = trace(&[
            (0.0, 0.0),
            (400.0, 0.0),
            (400.0, 150.0),
            (800.0, 150.0),
            (800.0, 0.0),
            (1200.0, 0.0),
        ]);
        assert_eq!(passages(&run, &river()), []);
    }

    fn shared(n: usize, ranges: &[(usize, usize, &[i64])]) -> Vec<HashSet<i64>> {
        let mut v = vec![HashSet::new(); n];
        for (a, b, ids) in ranges {
            for set in &mut v[*a..*b] {
                set.extend(ids.iter().copied());
            }
        }
        v
    }

    #[test]
    fn a_stretch_shared_with_two_other_runs_is_a_candidate() {
        let run = trace(&[(0.0, -400.0), (0.0, 0.0), (1200.0, 0.0), (1200.0, 400.0)]);
        let n = run.len();
        // Points 20 to 80 (400 m to 1600 m) are shared with runs 1 and 2.
        let found = candidates(&run, &shared(n, &[(20, 80, &[1, 2])]), &vec![false; n]);
        assert_eq!(found, [(22, 77)]);
        // Shared with one other run only: not yet.
        assert_eq!(candidates(&run, &shared(n, &[(20, 80, &[1])]), &vec![false; n]), []);
        // Too short.
        assert_eq!(candidates(&run, &shared(n, &[(20, 30, &[1, 2])]), &vec![false; n]), []);
    }

    #[test]
    fn holes_are_bridged_and_known_segments_are_left_out() {
        let run = trace(&[(0.0, 0.0), (2000.0, 0.0)]);
        let n = run.len();
        let mut sharing = shared(n, &[(0, n, &[1, 2])]);
        sharing[30].clear();
        sharing[31].clear();
        assert_eq!(candidates(&run, &sharing, &vec![false; n]), [(2, n - 3)]);
        // A known segment over points 40 to 60 splits what is left around it.
        let mut known = vec![false; n];
        known[40..60].iter_mut().for_each(|c| *c = true);
        assert_eq!(candidates(&run, &sharing, &known), [(2, 37), (62, n - 3)]);
    }

    #[test]
    fn a_stretch_is_cut_where_habits_diverge() {
        let run = trace(&[(0.0, 0.0), (2000.0, 0.0)]);
        let n = run.len();
        // Runs 1 and 2 share the first kilometre, runs 3 and 4 the second.
        let sharing = shared(n, &[(0, 50, &[1, 2]), (50, n, &[3, 4])]);
        assert_eq!(candidates(&run, &sharing, &vec![false; n]), [(2, 47), (52, n - 3)]);
    }

    #[test]
    fn laps_of_a_loop_are_cut_into_single_laps() {
        // Three laps of a 1 600 m square, all shared with two other runs.
        let square = [(0.0, 0.0), (400.0, 0.0), (400.0, 400.0), (0.0, 400.0)];
        let mut way: Vec<(f64, f64)> = square.iter().cycle().take(12).copied().collect();
        way.push((0.0, 0.0));
        let run = trace(&way);
        let n = run.len();
        let found = candidates(&run, &shared(n, &[(0, n, &[1, 2])]), &vec![false; n]);
        assert_eq!(found.len(), 3, "{found:?}");
        for (a, b) in found {
            let lap = run[b].dist - run[a].dist;
            assert!((1450.0..=1600.0).contains(&lap), "{lap}");
        }
    }

    #[test]
    fn names_tell_length_and_direction() {
        assert_eq!(default_name(&river()), "1,2 km · vers l'est");
        assert_eq!(default_name(&trace(&[(0.0, 0.0), (0.0, -650.0)])), "640 m · vers le sud");
        let lap = trace(&[(0.0, 0.0), (500.0, 0.0), (500.0, 500.0), (0.0, 500.0), (0.0, 0.0)]);
        assert!(default_name(&lap).ends_with("· boucle"), "{}", default_name(&lap));
    }
}
