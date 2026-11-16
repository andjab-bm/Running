//! Best efforts over standard distances inside one run, and race-time predictions.
use serde::Serialize;

use super::metrics::Streams;

/// 400 m, 1 km, 5 km, 10 km, half-marathon, marathon — in metres.
pub const STANDARD_DISTANCES: [f64; 6] = [400.0, 1000.0, 5000.0, 10_000.0, 21_097.5, 42_195.0];
/// World-record times for those distances, rounded down (seconds). An effort faster
/// than this is a position error, not a performance, and is discarded.
pub const WORLD_RECORD_S: [f64; 6] = [43.0, 131.0, 755.0, 1571.0, 3420.0, 7200.0];
/// An interval faster than this (a sprinter's top speed) is a position jump:
/// it adds neither distance nor time to an effort.
pub const MAX_EFFORT_SPEED_MPS: f64 = 12.5;
/// Tolerance for floating-point rounding in the summed distance, in metres.
const ROUNDING_M: f64 = 1e-6;
/// Exponent of Riegel's formula `T2 = T1 × (D2 / D1)^k`.
pub const RIEGEL_EXPONENT: f64 = 1.06;
/// Only efforts of the last weeks, at least this long, feed a prediction.
pub const PREDICTION_WINDOW_WEEKS: f64 = 12.0;
pub const PREDICTION_MIN_EFFORT_M: f64 = 1000.0;
/// A target more than this many times the longest recent run is only indicative.
pub const INDICATIVE_DISTANCE_RATIO: f64 = 4.0;
/// Race distances a prediction is given for.
pub const PREDICTED_DISTANCES: [f64; 4] = [5000.0, 10_000.0, 21_097.5, 42_195.0];

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct BestEffort {
    /// The standard distance, in metres.
    pub distance: f64,
    /// Moving time over the fastest stretch of that distance: pauses do not count.
    pub moving_s: f64,
    /// Where the stretch starts, in metres from the start of the run.
    pub start_offset_m: f64,
}

/// Linear interpolation of `ys` at `x` inside interval `j` (between samples `j - 1` and `j`) of `xs`.
fn lerp(xs: &[f64], ys: &[f64], j: usize, x: f64) -> f64 {
    let span = xs[j] - xs[j - 1];
    if span <= 0.0 {
        ys[j - 1]
    } else {
        ys[j - 1] + (x - xs[j - 1]) / span * (ys[j] - ys[j - 1])
    }
}

/// The fastest stretch of each standard distance, wherever it lies in the run.
///
/// Distance and time are both counted over moving intervals only, so a pause adds
/// no time and ground covered while the timer was stopped adds no distance.
pub fn best_efforts(s: &Streams) -> Vec<BestEffort> {
    let n = s.len();
    if n < 2 {
        return Vec::new();
    }
    // Cumulative moving distance and moving time at each sample.
    let (mut md, mut mt) = (vec![0.0; n], vec![0.0; n]);
    for i in 1..n {
        let (dd, dt) = (s.dist[i] - s.dist[i - 1], s.t[i] - s.t[i - 1]);
        let counts = s.moving[i] && dt > 0.0 && dd >= 0.0 && dd / dt <= MAX_EFFORT_SPEED_MPS;
        md[i] = md[i - 1] + if counts { dd } else { 0.0 };
        mt[i] = mt[i - 1] + if counts { dt } else { 0.0 };
    }

    let mut out = Vec::new();
    for (k, &target) in STANDARD_DISTANCES.iter().enumerate() {
        // A hair of rounding in the summed distance must not hide a run of exactly the distance.
        let total = md[n - 1];
        if total + ROUNDING_M < target {
            continue;
        }
        let mut best: Option<(f64, f64)> = None; // (time, real distance at the start)
        let mut consider = |time: f64, start_dist: f64| {
            if time >= WORLD_RECORD_S[k] && best.is_none_or(|b| time < b.0) {
                best = Some((time, start_dist));
            }
        };
        // The fastest window starts or ends on a sample, since both series are piecewise linear.
        // Windows starting on sample i: find where the moving distance reaches md[i] + target.
        let mut j = 1;
        for i in 0..n {
            let mut goal = md[i] + target;
            if goal > total && goal <= total + ROUNDING_M {
                goal = total;
            }
            while j < n && md[j] < goal {
                j += 1;
            }
            if j == n {
                break;
            }
            consider(lerp(&md, &mt, j, goal) - mt[i], s.dist[i]);
        }
        // Windows ending on sample j: find where the moving distance was md[j] - target.
        let mut i = 1;
        for j in 1..n {
            let goal = md[j] - target;
            if goal < -ROUNDING_M {
                continue;
            }
            let goal = goal.max(0.0);
            while i < n && md[i] <= goal {
                i += 1;
            }
            if i == n {
                break;
            }
            consider(mt[j] - lerp(&md, &mt, i, goal), lerp(&md, &s.dist, i, goal));
        }
        if let Some((time, start)) = best {
            out.push(BestEffort {
                distance: target,
                moving_s: time,
                start_offset_m: start,
            });
        }
    }
    out
}

/// Riegel's projection of a time over `from_m` to the distance `to_m`.
pub fn riegel(time_s: f64, from_m: f64, to_m: f64) -> f64 {
    time_s * (to_m / from_m).powf(RIEGEL_EXPONENT)
}

/// The fastest projection to `target_m` among `(distance, time)` efforts, with the index of
/// the effort it comes from. An effort at the target distance projects to itself, so a
/// prediction is never slower than a result actually achieved.
pub fn predict(efforts: &[(f64, f64)], target_m: f64) -> Option<(f64, usize)> {
    efforts
        .iter()
        .enumerate()
        .filter(|(_, (d, _))| *d >= PREDICTION_MIN_EFFORT_M)
        .map(|(i, (d, t))| (riegel(*t, *d, target_m), i))
        .min_by(|a, b| a.0.total_cmp(&b.0))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// One sample per second; `f(second)` gives the speed in m/s and whether the interval is moving.
    fn streams(seconds: usize, f: impl Fn(usize) -> (f64, bool)) -> Streams {
        let mut s = Streams {
            t: vec![0.0],
            dist: vec![0.0],
            moving: vec![false],
            ..Streams::default()
        };
        for i in 1..=seconds {
            let (v, moving) = f(i);
            s.t.push(i as f64);
            s.dist.push(s.dist[i - 1] + v);
            s.moving.push(moving);
        }
        s
    }

    fn effort(list: &[BestEffort], d: f64) -> Option<&BestEffort> {
        list.iter().find(|e| e.distance == d)
    }

    #[test]
    fn fast_kilometre_in_the_middle_is_found() {
        // 8 km at 6:00 min/km, except the kilometre from 3.2 km to 4.2 km in 4:50.
        let (slow, fast) = (1000.0 / 360.0, 1000.0 / 290.0);
        let t1 = (3200.0_f64 / slow).round() as usize; // 1152 s
        let s = streams(t1 + 290 + ((8000.0_f64 - 4200.0) / slow) as usize, |i| {
            (if i > t1 && i <= t1 + 290 { fast } else { slow }, true)
        });
        let e = best_efforts(&s);
        let km = effort(&e, 1000.0).unwrap();
        assert!((km.moving_s - 290.0).abs() < 0.5, "{}", km.moving_s);
        assert!((km.start_offset_m - 3200.0).abs() < 5.0, "{}", km.start_offset_m);
    }

    #[test]
    fn a_run_shorter_than_the_distance_has_no_effort_for_it() {
        let s = streams(2520, |_| (7000.0 / 2520.0, true)); // 7 km
        let found: Vec<f64> = best_efforts(&s).iter().map(|e| e.distance).collect();
        assert_eq!(found, [400.0, 1000.0, 5000.0]);
    }

    #[test]
    fn a_pause_inside_the_stretch_does_not_count() {
        // 5 km covered in 28:40 on the clock, with a 40-second pause at a crossing.
        let v = 5000.0 / 1680.0;
        let s = streams(1720, |i| if (800..840).contains(&i) { (0.0, false) } else { (v, true) });
        let five = best_efforts(&s);
        let five = effort(&five, 5000.0).unwrap();
        assert!((five.moving_s - 1680.0).abs() < 0.5, "{}", five.moving_s);
    }

    #[test]
    fn a_run_of_exactly_the_distance_gives_its_moving_time() {
        let s = streams(1650, |_| (5000.0 / 1650.0, true)); // 5 km in 27:30
        let e = best_efforts(&s);
        assert!((effort(&e, 5000.0).unwrap().moving_s - 1650.0).abs() < 0.5);
    }

    #[test]
    fn ground_covered_while_paused_adds_no_distance() {
        // The runner walks 300 m with the timer stopped: that must not shorten the kilometre.
        let s = streams(1000, |i| if (300..360).contains(&i) { (5.0, false) } else { (3.0, true) });
        let e = best_efforts(&s);
        assert!((effort(&e, 1000.0).unwrap().moving_s - 1000.0 / 3.0).abs() < 0.5);
    }

    #[test]
    fn a_position_jump_does_not_make_a_record() {
        // One second in which the position jumps 400 m.
        let s = streams(1200, |i| (if i == 600 { 400.0 } else { 3.0 }, true));
        let e = best_efforts(&s);
        assert!((effort(&e, 400.0).unwrap().moving_s - 400.0 / 3.0).abs() < 0.5);
        assert!((effort(&e, 1000.0).unwrap().moving_s - 1000.0 / 3.0).abs() < 0.5);
    }

    #[test]
    fn prediction_from_a_recent_five_kilometres() {
        // 5 km in 28:00 gives about 58:20 over 10 km.
        let (t, from) = predict(&[(5000.0, 1680.0)], 10_000.0).unwrap();
        assert_eq!(from, 0);
        assert!((t - 3500.0).abs() < 10.0, "{t}");
    }

    #[test]
    fn prediction_is_never_slower_than_a_recent_result() {
        let (t, from) = predict(&[(5000.0, 1680.0), (10_000.0, 3420.0)], 10_000.0).unwrap();
        assert_eq!((t, from), (3420.0, 1));
    }

    #[test]
    fn no_qualifying_effort_gives_no_prediction() {
        assert_eq!(predict(&[], 5000.0), None);
        assert_eq!(predict(&[(400.0, 95.0)], 5000.0), None);
    }
}
