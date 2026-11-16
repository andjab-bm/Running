//! Training load of a run, short-term against long-term load, and advised recovery time.
//!
//! Deliberately simple formulas, explained to the runner on the page: they are
//! estimates from recorded runs only, not a physiological model.

/// Session-RPE scaling: `moving minutes × RPE × K` lands near the heart-rate based
/// effort of the same run (60 min at RPE 5 ≈ 105, like an hour around zone 2).
pub const SRPE_FACTOR: f64 = 0.35;
/// Intensity assumed for a run with neither heart rate nor perceived effort (zone 2).
pub const DEFAULT_INTENSITY: f64 = 2.0;
/// Time constants, in days, of the short-term and long-term averages.
pub const SHORT_TERM_DAYS: f64 = 7.0;
pub const LONG_TERM_DAYS: f64 = 28.0;
/// Below this much history the status is "not enough data yet".
pub const MIN_HISTORY_DAYS: usize = 14;
/// Bounds of the short/long ratio between the statuses.
pub const RATIO_RESUMING: f64 = 0.5;
pub const RATIO_LIGHT: f64 = 0.8;
pub const RATIO_STEADY: f64 = 1.2;
pub const RATIO_BUILDING: f64 = 1.5;
/// Recovery: `BASE + PER_TYPICAL × (load / typical) × max(1, ratio)`, within bounds.
pub const RECOVERY_BASE_H: f64 = 12.0;
pub const RECOVERY_PER_TYPICAL_H: f64 = 24.0;
pub const RECOVERY_MIN_H: f64 = 6.0;
pub const RECOVERY_MAX_H: f64 = 96.0;
/// Share of the recovery still owed from the previous run that is added to the next one.
pub const RECOVERY_CARRY: f64 = 0.5;
/// The runner's usual load is the median over this many weeks.
pub const TYPICAL_WINDOW_WEEKS: f64 = 6.0;

/// Load of one run and whether it is an estimate (not based on heart rate).
pub fn activity_load(effort: Option<f64>, moving_s: f64, rpe: Option<i64>) -> (f64, bool) {
    let minutes = moving_s / 60.0;
    match (effort, rpe) {
        (Some(e), _) => (e, false),
        (None, Some(r)) => (minutes * r as f64 * SRPE_FACTOR, true),
        (None, None) => (minutes * DEFAULT_INTENSITY, true),
    }
}

/// Exponentially weighted daily average with time constant `tau` days, one value per day.
///
/// The average is corrected for its start: after a few days of a constant load it already
/// equals that load, instead of creeping up from zero — otherwise a new runner's
/// short-term average would outrun the long-term one for weeks.
pub fn averaged(daily: &[f64], tau: f64) -> Vec<f64> {
    let k = 1.0 - (-1.0 / tau).exp();
    let mut raw = 0.0;
    daily
        .iter()
        .enumerate()
        .map(|(i, v)| {
            raw += (v - raw) * k;
            raw / (1.0 - (1.0 - k).powi(i as i32 + 1))
        })
        .collect()
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Status {
    NotEnoughData,
    Resuming,
    Light,
    Steady,
    Building,
    TooMuch,
}

impl Status {
    pub fn code(self) -> &'static str {
        match self {
            Status::NotEnoughData => "not_enough_data",
            Status::Resuming => "resuming",
            Status::Light => "light",
            Status::Steady => "steady",
            Status::Building => "building",
            Status::TooMuch => "too_much",
        }
    }
}

/// The relation between recent and usual load, in words.
pub fn status(short: f64, long: f64, history_days: usize) -> Status {
    if history_days < MIN_HISTORY_DAYS || long <= 0.0 {
        return Status::NotEnoughData;
    }
    match short / long {
        r if r < RATIO_RESUMING => Status::Resuming,
        r if r < RATIO_LIGHT => Status::Light,
        r if r <= RATIO_STEADY => Status::Steady,
        r if r <= RATIO_BUILDING => Status::Building,
        _ => Status::TooMuch,
    }
}

/// Hours of recovery advised after a run: longer for a harder session relative to the
/// runner's usual one, longer when recent load is already high, and longer when the
/// previous run was not recovered from (`unrecovered_h` hours were still owed at the start).
pub fn recovery_hours(load: f64, typical: f64, ratio: f64, unrecovered_h: f64) -> f64 {
    let relative = if typical > 0.0 { load / typical } else { 1.0 };
    let base = (RECOVERY_BASE_H + RECOVERY_PER_TYPICAL_H * relative * ratio.max(1.0)).clamp(RECOVERY_MIN_H, RECOVERY_MAX_H);
    (base + RECOVERY_CARRY * unrecovered_h.max(0.0)).min(RECOVERY_MAX_H)
}

/// Median of the values, or `None` when there is none.
pub fn median(values: &mut [f64]) -> Option<f64> {
    if values.is_empty() {
        return None;
    }
    values.sort_by(f64::total_cmp);
    let mid = values.len() / 2;
    Some(if values.len() % 2 == 1 {
        values[mid]
    } else {
        (values[mid - 1] + values[mid]) / 2.0
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn last(v: &[f64]) -> f64 {
        v[v.len() - 1]
    }

    /// A run of `load` every other day.
    fn regular(days: usize, load: f64) -> Vec<f64> {
        (0..days).map(|d| if d % 2 == 0 { load } else { 0.0 }).collect()
    }

    #[test]
    fn load_uses_effort_then_perceived_effort_then_duration() {
        assert_eq!(activity_load(Some(95.0), 2400.0, Some(8)), (95.0, false));
        let (hard, est) = activity_load(None, 2400.0, Some(8));
        let (easy, _) = activity_load(None, 2400.0, Some(3));
        assert!(est && hard > easy);
        assert!((hard - 112.0).abs() < 1e-9);
        assert_eq!(activity_load(None, 2400.0, None), (80.0, true));
    }

    #[test]
    fn steady_training_is_steady() {
        let d = regular(60, 80.0);
        let (s, l) = (last(&averaged(&d, SHORT_TERM_DAYS)), last(&averaged(&d, LONG_TERM_DAYS)));
        assert!((s / l - 1.0).abs() < 0.2, "{s} {l}");
        assert_eq!(status(s, l, d.len()), Status::Steady);
        // The start correction: two weeks in, a constant habit is already read as steady.
        let early = regular(15, 80.0);
        assert_eq!(
            status(
                last(&averaged(&early, SHORT_TERM_DAYS)),
                last(&averaged(&early, LONG_TERM_DAYS)),
                early.len()
            ),
            Status::Steady
        );
    }

    #[test]
    fn a_sudden_heavy_week_lifts_short_term_above_long_term() {
        let mut d = regular(60, 80.0);
        d.extend(regular(7, 160.0));
        let (s, l) = (last(&averaged(&d, SHORT_TERM_DAYS)), last(&averaged(&d, LONG_TERM_DAYS)));
        assert!(s > l * 1.2, "{s} {l}");
        assert_eq!(status(s, l, d.len()), Status::Building);
        // Tripling it is too much, too soon.
        let mut d = regular(60, 80.0);
        d.extend(regular(7, 280.0));
        let (s, l) = (last(&averaged(&d, SHORT_TERM_DAYS)), last(&averaged(&d, LONG_TERM_DAYS)));
        assert_eq!(status(s, l, d.len()), Status::TooMuch);
    }

    #[test]
    fn two_weeks_off_lower_both_and_short_term_faster() {
        let mut d = regular(60, 80.0);
        d.extend([0.0; 14]);
        let (s, l) = (averaged(&d, SHORT_TERM_DAYS), averaged(&d, LONG_TERM_DAYS));
        for i in 61..74 {
            assert!(s[i] < s[i - 1] && l[i] < l[i - 1], "day {i}");
        }
        assert!(s[73] / s[59] < l[73] / l[59]);
        assert_eq!(status(s[73], l[73], d.len()), Status::Resuming);
    }

    #[test]
    fn statuses_follow_the_ratio() {
        assert_eq!(status(40.0, 100.0, 30), Status::Resuming);
        assert_eq!(status(70.0, 100.0, 30), Status::Light);
        assert_eq!(status(110.0, 100.0, 30), Status::Steady);
        assert_eq!(status(140.0, 100.0, 30), Status::Building);
        assert_eq!(status(160.0, 100.0, 30), Status::TooMuch);
        // A profile whose first run was five days ago.
        assert_eq!(status(60.0, 50.0, 5), Status::NotEnoughData);
    }

    #[test]
    fn recovery_grows_with_the_session_and_with_fatigue() {
        let usual = recovery_hours(80.0, 80.0, 1.0, 0.0);
        assert_eq!(usual, 36.0);
        // Twice the usual load.
        assert!(recovery_hours(160.0, 80.0, 1.0, 0.0) > usual);
        // Recent load already high.
        assert!(recovery_hours(80.0, 80.0, 1.4, 0.0) > usual);
        // Twenty hours of recovery were still owed when the run started.
        assert_eq!(recovery_hours(80.0, 80.0, 1.0, 20.0), usual + 10.0);
        // Bounds.
        assert!((recovery_hours(1.0, 400.0, 1.0, 0.0) - 12.06).abs() < 1e-9);
        assert_eq!(recovery_hours(0.0, 80.0, 1.0, 0.0), 12.0);
        assert_eq!(recovery_hours(1000.0, 80.0, 2.0, 50.0), RECOVERY_MAX_H);
    }

    #[test]
    fn median_of_loads() {
        assert_eq!(median(&mut []), None);
        assert_eq!(median(&mut [30.0, 90.0, 60.0]), Some(60.0));
        assert_eq!(median(&mut [30.0, 90.0, 60.0, 70.0]), Some(65.0));
    }
}
