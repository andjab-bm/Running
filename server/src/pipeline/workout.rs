//! Structured sessions: the document shared by the web editor, the server and the phone,
//! and its validation.
//!
//! ```json
//! { "type": "step", "kind": "effort",
//!   "end": { "type": "distance", "value": 400 },
//!   "target": { "type": "pace", "fast": 300, "slow": 315 } }
//! { "type": "repeat", "count": 6, "skip_last_recovery": false, "steps": [ ... ] }
//! ```
//!
//! Durations are seconds, distances metres, paces seconds per kilometre. The phone reads
//! the same document; the fixtures in `tests/fixtures/workouts/` are checked by both sides.
use serde::{Deserialize, Serialize};

pub const MIN_TIME_S: f64 = 5.0;
pub const MAX_TIME_S: f64 = 6.0 * 3600.0;
pub const MIN_DISTANCE_M: f64 = 50.0;
pub const MAX_DISTANCE_M: f64 = 100_000.0;
pub const MIN_REPEATS: u32 = 2;
pub const MAX_REPEATS: u32 = 50;
/// Bounds of a pace target, in seconds per kilometre (2:00 to 20:00).
pub const MIN_PACE_S_PER_KM: f64 = 120.0;
pub const MAX_PACE_S_PER_KM: f64 = 1200.0;
/// A session unfolds into at most this many steps.
pub const MAX_STEPS: usize = 500;
pub const MAX_NAME: usize = 80;
pub const MAX_NOTE: usize = 500;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    Warmup,
    Effort,
    Recovery,
    Cooldown,
}

/// What ends a step.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum End {
    /// After this many seconds of moving time.
    Time { value: f64 },
    /// After this many metres.
    Distance { value: f64 },
    /// When the runner presses lap.
    Lap,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Target {
    #[default]
    None,
    /// Between `fast` and `slow`, in seconds per kilometre.
    Pace { fast: f64, slow: f64 },
    /// A heart-rate zone of the profile, 1 to 5.
    HrZone { zone: u8 },
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum Item {
    Step {
        kind: Kind,
        end: End,
        #[serde(default)]
        target: Target,
    },
    /// A block of steps done `count` times. Blocks are not nested.
    Repeat {
        count: u32,
        /// Leave out the recovery that would end the last repetition.
        #[serde(default)]
        skip_last_recovery: bool,
        steps: Vec<Item>,
    },
}

/// One step of the unfolded session.
#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
pub struct Step {
    pub kind: Kind,
    pub end: End,
    pub target: Target,
}

fn check_step(end: &End, target: &Target) -> Result<(), String> {
    match *end {
        End::Time { value } if !(MIN_TIME_S..=MAX_TIME_S).contains(&value) => {
            return Err("A step lasts between 5 seconds and 6 hours.".into());
        }
        End::Distance { value } if !(MIN_DISTANCE_M..=MAX_DISTANCE_M).contains(&value) => {
            return Err("A step covers between 50 m and 100 km.".into());
        }
        _ => {}
    }
    match *target {
        Target::Pace { fast, slow } => {
            if !(MIN_PACE_S_PER_KM..=MAX_PACE_S_PER_KM).contains(&fast) || !(MIN_PACE_S_PER_KM..=MAX_PACE_S_PER_KM).contains(&slow) {
                return Err("A pace target lies between 2:00 and 20:00 per kilometre.".into());
            }
            if fast >= slow {
                return Err("The fast bound of a pace target must be faster than its slow bound.".into());
            }
        }
        Target::HrZone { zone } if !(1..=5).contains(&zone) => {
            return Err("A heart-rate zone target is a zone from 1 to 5.".into());
        }
        _ => {}
    }
    Ok(())
}

/// Checks a session and returns it unfolded, or the reason it is refused.
pub fn validate(items: &[Item]) -> Result<Vec<Step>, String> {
    if items.is_empty() {
        return Err("A session needs at least one step.".into());
    }
    for item in items {
        match item {
            Item::Step { end, target, .. } => check_step(end, target)?,
            Item::Repeat { count, steps, .. } => {
                if !(MIN_REPEATS..=MAX_REPEATS).contains(count) {
                    return Err("A block is repeated between 2 and 50 times.".into());
                }
                if steps.is_empty() {
                    return Err("A repeated block needs at least one step.".into());
                }
                for inner in steps {
                    match inner {
                        Item::Step { end, target, .. } => check_step(end, target)?,
                        Item::Repeat { .. } => return Err("Repeated blocks cannot be nested.".into()),
                    }
                }
            }
        }
    }
    let steps = unfold(items);
    if steps.is_empty() {
        return Err("A session needs at least one step.".into());
    }
    if steps.len() > MAX_STEPS {
        return Err(format!("A session unfolds into at most {MAX_STEPS} steps."));
    }
    Ok(steps)
}

/// The steps in the order they are run, repeats expanded.
pub fn unfold(items: &[Item]) -> Vec<Step> {
    let mut out = Vec::new();
    for item in items {
        match item {
            Item::Step { kind, end, target } => out.push(Step {
                kind: *kind,
                end: *end,
                target: *target,
            }),
            Item::Repeat {
                count,
                skip_last_recovery,
                steps,
            } => {
                for round in 0..*count {
                    for (i, inner) in steps.iter().enumerate() {
                        if let Item::Step { kind, end, target } = inner {
                            let closing_recovery =
                                *skip_last_recovery && round + 1 == *count && i + 1 == steps.len() && *kind == Kind::Recovery;
                            if !closing_recovery {
                                out.push(Step {
                                    kind: *kind,
                                    end: *end,
                                    target: *target,
                                });
                            }
                        }
                    }
                }
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixtures(sub: &str) -> Vec<(String, serde_json::Value)> {
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("tests/fixtures/workouts")
            .join(sub);
        let mut out: Vec<(String, serde_json::Value)> = std::fs::read_dir(dir)
            .unwrap()
            .flatten()
            .map(|e| {
                let text = std::fs::read_to_string(e.path()).unwrap();
                (e.file_name().to_string_lossy().to_string(), serde_json::from_str(&text).unwrap())
            })
            .collect();
        out.sort_by(|a, b| a.0.cmp(&b.0));
        out
    }

    fn check(doc: &serde_json::Value) -> Result<Vec<Step>, String> {
        let items: Vec<Item> = serde_json::from_value(doc["steps"].clone()).map_err(|e| e.to_string())?;
        validate(&items)
    }

    #[test]
    fn valid_fixtures_are_accepted_with_their_step_count() {
        let all = fixtures("valid");
        assert!(all.len() >= 3);
        for (name, doc) in all {
            let steps = check(&doc).unwrap_or_else(|e| panic!("{name}: {e}"));
            assert_eq!(steps.len() as u64, doc["expect_steps"].as_u64().unwrap(), "{name}");
        }
    }

    #[test]
    fn invalid_fixtures_are_refused() {
        let all = fixtures("invalid");
        assert!(all.len() >= 6);
        for (name, doc) in all {
            assert!(check(&doc).is_err(), "{name} should be refused");
        }
    }

    #[test]
    fn six_repeats_unfold_into_twelve_steps_or_eleven() {
        let block = |skip| {
            vec![Item::Repeat {
                count: 6,
                skip_last_recovery: skip,
                steps: vec![
                    Item::Step {
                        kind: Kind::Effort,
                        end: End::Distance { value: 400.0 },
                        target: Target::Pace { fast: 300.0, slow: 315.0 },
                    },
                    Item::Step {
                        kind: Kind::Recovery,
                        end: End::Time { value: 90.0 },
                        target: Target::None,
                    },
                ],
            }]
        };
        assert_eq!(validate(&block(false)).unwrap().len(), 12);
        let eleven = validate(&block(true)).unwrap();
        assert_eq!(eleven.len(), 11);
        assert_eq!(eleven.last().unwrap().kind, Kind::Effort);
    }

    #[test]
    fn the_document_round_trips() {
        let text = r#"[{"type":"step","kind":"warmup","end":{"type":"time","value":600.0},"target":{"type":"hr_zone","zone":2}},
            {"type":"step","kind":"recovery","end":{"type":"lap"}}]"#;
        let items: Vec<Item> = serde_json::from_str(text).unwrap();
        assert_eq!(
            items[1],
            Item::Step {
                kind: Kind::Recovery,
                end: End::Lap,
                target: Target::None
            }
        );
        let again: Vec<Item> = serde_json::from_str(&serde_json::to_string(&items).unwrap()).unwrap();
        assert_eq!(again, items);
    }
}
