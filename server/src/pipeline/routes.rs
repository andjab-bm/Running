//! Routes to follow: built from the path of a GPX file or of a past run.
//!
//! A route carries only where to go — positions, elevations and distance along the way.
//! Its figures are computed with the same code as an activity's, by treating the path
//! as a run at a constant speed.
use serde::{Deserialize, Serialize};

use super::{
    Parsed, Sample,
    metrics::{Axis, Streams, build_streams, segment},
};

/// Points further than this from the simplified path are kept (Douglas–Peucker tolerance).
pub const SIMPLIFY_M: f64 = 5.0;
/// A route keeps at most this many points; the tolerance is widened until it fits.
pub const MAX_POINTS: usize = 5000;
/// A route ending this close to its start is a loop...
pub const LOOP_M: f64 = 100.0;
/// ...provided it is at least this long.
pub const MIN_LOOP_LENGTH_M: f64 = 300.0;
/// Straight legs longer than this are filled in before the figures are computed.
const DENSIFY_M: f64 = 20.0;
/// Speed assumed to lay the path out in time for the metric code.
const NOMINAL_SPEED_MPS: f64 = 3.0;
const M_PER_DEG: f64 = 111_194.93;

/// The path of a route, as index-aligned arrays.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Shape {
    pub lat: Vec<f64>,
    pub lon: Vec<f64>,
    /// Smoothed elevation, when the source has elevation.
    pub ele: Option<Vec<f64>>,
    /// Metres from the start.
    pub dist: Vec<f64>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Route {
    pub shape: Shape,
    pub distance: f64,
    pub gain: Option<f64>,
    pub loss: Option<f64>,
    pub is_loop: bool,
}

fn metres(a: (f64, f64), b: (f64, f64)) -> f64 {
    let north = (b.0 - a.0) * M_PER_DEG;
    let east = (b.1 - a.1) * M_PER_DEG * a.0.to_radians().cos();
    north.hypot(east)
}

/// Indices kept by Douglas–Peucker on the positions, first and last always included.
fn simplify(lat: &[f64], lon: &[f64], tolerance: f64) -> Vec<usize> {
    let n = lat.len();
    if n < 3 {
        return (0..n).collect();
    }
    let cos = lat[0].to_radians().cos();
    let xy = |i: usize| ((lon[i] - lon[0]) * M_PER_DEG * cos, (lat[i] - lat[0]) * M_PER_DEG);
    let mut keep = vec![false; n];
    (keep[0], keep[n - 1]) = (true, true);
    let mut stack = vec![(0, n - 1)];
    while let Some((a, b)) = stack.pop() {
        let (pa, pb) = (xy(a), xy(b));
        let (dx, dy) = (pb.0 - pa.0, pb.1 - pa.1);
        let len2 = dx * dx + dy * dy;
        let (mut far, mut far_d) = (a, 0.0);
        for i in a + 1..b {
            let p = xy(i);
            let d = if len2 == 0.0 {
                (p.0 - pa.0).hypot(p.1 - pa.1)
            } else {
                // Distance to the segment, not to the infinite line: an out-and-back must keep its far end.
                let t = (((p.0 - pa.0) * dx + (p.1 - pa.1) * dy) / len2).clamp(0.0, 1.0);
                (p.0 - (pa.0 + t * dx)).hypot(p.1 - (pa.1 + t * dy))
            };
            if d > far_d {
                (far, far_d) = (i, d);
            }
        }
        if far_d > tolerance {
            keep[far] = true;
            stack.push((a, far));
            stack.push((far, b));
        }
    }
    (0..n).filter(|i| keep[*i]).collect()
}

/// Reduces a path to at most [`MAX_POINTS`], keeping true distances along the way.
fn reduce(shape: Shape) -> Shape {
    let mut tolerance = SIMPLIFY_M;
    let mut keep = simplify(&shape.lat, &shape.lon, tolerance);
    while keep.len() > MAX_POINTS {
        tolerance *= 2.0;
        keep = simplify(&shape.lat, &shape.lon, tolerance);
    }
    let pick = |v: &[f64]| keep.iter().map(|i| v[*i]).collect::<Vec<_>>();
    Shape {
        lat: pick(&shape.lat),
        lon: pick(&shape.lon),
        ele: shape.ele.as_deref().map(pick),
        dist: pick(&shape.dist),
    }
}

fn finish(shape: Shape, gain: Option<f64>, loss: Option<f64>) -> Option<Route> {
    let n = shape.lat.len();
    let distance = *shape.dist.last()?;
    if n < 2 || distance <= 0.0 {
        return None;
    }
    let ends = metres((shape.lat[0], shape.lon[0]), (shape.lat[n - 1], shape.lon[n - 1]));
    Some(Route {
        is_loop: distance >= MIN_LOOP_LENGTH_M && ends <= LOOP_M,
        shape: reduce(shape),
        distance,
        gain,
        loss,
    })
}

/// Builds a route from raw `(lat, lon, elevation)` points, in order. `None` when fewer
/// than two usable points are given.
pub fn from_points(points: &[(f64, f64, Option<f64>)]) -> Option<Route> {
    // A planned route may be a handful of waypoints kilometres apart. The elevation code
    // works on closely spaced samples, so the path is first filled in along its straight legs.
    let mut dense: Vec<(f64, f64, Option<f64>)> = Vec::with_capacity(points.len());
    for w in points.windows(2) {
        let (a, b) = (w[0], w[1]);
        let parts = (metres((a.0, a.1), (b.0, b.1)) / DENSIFY_M).ceil().max(1.0) as usize;
        for k in 0..parts {
            let f = k as f64 / parts as f64;
            let ele = match (a.2, b.2) {
                (Some(x), Some(y)) => Some(x + (y - x) * f),
                (x, _) => x.filter(|_| k == 0),
            };
            dense.push((a.0 + (b.0 - a.0) * f, a.1 + (b.1 - a.1) * f, ele));
        }
    }
    dense.extend(points.last());
    let points = &dense[..];
    let mut t = 0.0;
    let mut samples: Vec<Sample> = Vec::with_capacity(points.len());
    for (i, p) in points.iter().enumerate() {
        if i > 0 {
            // At least a second apart, so that repeated points do not share a timestamp.
            t += (metres((points[i - 1].0, points[i - 1].1), (p.0, p.1)) / NOMINAL_SPEED_MPS).max(1.0);
        }
        samples.push(Sample {
            time: t,
            lat: Some(p.0),
            lon: Some(p.1),
            ele: p.2,
            ..Sample::default()
        });
    }
    let streams = build_streams(&Parsed {
        samples,
        ..Parsed::default()
    })
    .ok()?;
    from_streams(&streams, 0.0, streams.total_dist())
}

/// Builds a route from the path of an activity between two distances along it.
pub fn from_streams(s: &Streams, from_m: f64, to_m: f64) -> Option<Route> {
    let (from_m, to_m) = (from_m.max(0.0), to_m.min(s.total_dist()));
    if to_m <= from_m {
        return None;
    }
    let idx: Vec<usize> = (0..s.len())
        .filter(|i| s.dist[*i] >= from_m && s.dist[*i] <= to_m && s.lat[*i].is_some() && s.lon[*i].is_some())
        .collect();
    let shape = Shape {
        lat: idx.iter().filter_map(|i| s.lat[*i]).collect(),
        lon: idx.iter().filter_map(|i| s.lon[*i]).collect(),
        ele: s.ele.as_ref().map(|e| idx.iter().map(|i| e[*i]).collect()),
        dist: idx.iter().map(|i| s.dist[*i] - from_m).collect(),
    };
    let whole = segment(s, Axis::Dist, from_m, to_m);
    finish(shape, whole.gain, whole.loss)
}

/// The same path run the other way: climbs become descents.
pub fn reversed(r: &Route) -> Route {
    let total = r.distance;
    let rev = |v: &[f64]| v.iter().rev().copied().collect::<Vec<_>>();
    Route {
        shape: Shape {
            lat: rev(&r.shape.lat),
            lon: rev(&r.shape.lon),
            ele: r.shape.ele.as_deref().map(rev),
            dist: r.shape.dist.iter().rev().map(|d| total - d).collect(),
        },
        distance: total,
        gain: r.loss,
        loss: r.gain,
        is_loop: r.is_loop,
    }
}

/// At most `max` evenly spaced `[lat, lon]` pairs, for thumbnails.
pub fn preview(shape: &Shape, max: usize) -> Vec<[f64; 2]> {
    let n = shape.lat.len();
    let step = (n as f64 / max as f64).max(1.0);
    let round = |v: f64| (v * 1e5).round() / 1e5;
    let mut out: Vec<[f64; 2]> = (0..)
        .map(|i| (i as f64 * step) as usize)
        .take_while(|i| *i < n)
        .map(|i| [round(shape.lat[i]), round(shape.lon[i])])
        .collect();
    if n > 0 {
        let end = [round(shape.lat[n - 1]), round(shape.lon[n - 1])];
        if out.last() != Some(&end) {
            out.push(end);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Points every 10 m through waypoints in metres east and north; `ele(distance)` gives the elevation.
    fn path(waypoints: &[(f64, f64)], ele: Option<&dyn Fn(f64) -> f64>) -> Vec<(f64, f64, Option<f64>)> {
        let mut out = Vec::new();
        let mut d = 0.0;
        for w in waypoints.windows(2) {
            let len = (w[1].0 - w[0].0).hypot(w[1].1 - w[0].1);
            let steps = (len / 10.0).round() as usize;
            for k in 0..steps {
                let f = k as f64 / steps as f64;
                let (e, n) = (w[0].0 + (w[1].0 - w[0].0) * f, w[0].1 + (w[1].1 - w[0].1) * f);
                out.push((
                    48.0 + n / M_PER_DEG,
                    2.0 + e / (M_PER_DEG * 48.0_f64.to_radians().cos()),
                    ele.map(|g| g(d + len * f)),
                ));
            }
            d += len;
        }
        let last = waypoints[waypoints.len() - 1];
        out.push((
            48.0 + last.1 / M_PER_DEG,
            2.0 + last.0 / (M_PER_DEG * 48.0_f64.to_radians().cos()),
            ele.map(|g| g(d)),
        ));
        out
    }

    #[test]
    fn a_planned_route_has_its_length_and_no_time() {
        // 9.4 km in four legs.
        let r = from_points(&path(
            &[(0.0, 0.0), (3000.0, 0.0), (3000.0, 2000.0), (600.0, 2000.0), (600.0, 0.0)],
            None,
        ))
        .unwrap();
        assert!((r.distance - 9400.0).abs() < 20.0, "{}", r.distance);
        assert!(!r.is_loop);
        assert_eq!((r.gain, r.loss, r.shape.ele.as_ref()), (None, None, None));
        // Straight legs need few points; distances stay true.
        assert!(r.shape.lat.len() <= 12, "{}", r.shape.lat.len());
        assert_eq!(r.shape.dist[0], 0.0);
        assert!((r.shape.dist.last().unwrap() - r.distance).abs() < 1e-6);
    }

    #[test]
    fn fewer_than_two_points_is_not_a_route() {
        assert_eq!(from_points(&[]), None);
        assert_eq!(from_points(&[(48.0, 2.0, None)]), None);
        assert_eq!(from_points(&[(48.0, 2.0, None), (48.0, 2.0, None)]), None);
    }

    #[test]
    fn a_route_back_to_its_start_is_a_loop() {
        let square = [(0.0, 0.0), (500.0, 0.0), (500.0, 500.0), (0.0, 500.0), (0.0, 40.0)];
        assert!(from_points(&path(&square, None)).unwrap().is_loop);
        assert!(!from_points(&path(&square[..4], None)).unwrap().is_loop);
    }

    #[test]
    fn climbs_are_counted_and_swap_when_reversed() {
        // 2 km climbing 120 m, then 1 km descending 80 m.
        let ele = |d: f64| {
            if d <= 2000.0 {
                40.0 + d * 0.06
            } else {
                160.0 - (d - 2000.0) * 0.08
            }
        };
        let r = from_points(&path(&[(0.0, 0.0), (2000.0, 0.0), (2000.0, 1000.0)], Some(&ele))).unwrap();
        let (gain, loss) = (r.gain.unwrap(), r.loss.unwrap());
        assert!((gain - 120.0).abs() < 6.0 && (loss - 80.0).abs() < 6.0, "{gain} {loss}");
        assert_eq!(r.shape.ele.as_ref().unwrap().len(), r.shape.lat.len());

        let back = reversed(&r);
        assert_eq!((back.gain, back.loss), (r.loss, r.gain));
        assert_eq!(back.distance, r.distance);
        assert_eq!(back.shape.dist[0], 0.0);
        assert_eq!(
            (back.shape.lat[0], back.shape.lon[0]),
            (*r.shape.lat.last().unwrap(), *r.shape.lon.last().unwrap())
        );
        assert!(back.shape.dist.windows(2).all(|w| w[1] >= w[0]));
    }

    #[test]
    fn an_out_and_back_keeps_its_far_end() {
        let r = from_points(&path(&[(0.0, 0.0), (1500.0, 0.0), (0.0, 0.0)], None)).unwrap();
        assert!((r.distance - 3000.0).abs() < 10.0);
        let far = r.shape.lon.iter().copied().fold(f64::MIN, f64::max);
        assert!((far - 2.0) * M_PER_DEG * 48.0_f64.to_radians().cos() > 1490.0);
    }

    #[test]
    fn preview_is_bounded_and_ends_at_the_end() {
        let r = from_points(&path(&[(0.0, 0.0), (300.0, 100.0), (600.0, 0.0), (900.0, 100.0)], None)).unwrap();
        let p = preview(&r.shape, 3);
        assert!(p.len() <= 4);
        assert!((p.last().unwrap()[0] - r.shape.lat.last().unwrap()).abs() < 1e-5);
    }
}
