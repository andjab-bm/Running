//! Turns an uploaded file into an analysed activity: `parse → normalise → derive → summarise`.
//!
//! Everything here is pure computation with no I/O, so it can be tested on
//! in-memory data and re-run whenever algorithms or profile settings change.
pub mod courses;
pub mod efforts;
pub mod gpx;
pub mod load;
pub mod metrics;
pub mod parse;
pub mod routes;
pub mod time;
pub mod workout;

use std::fmt;

pub use metrics::{Processed, Segment, Streams, Summary, Units, ZoneConfig, ZoneDistribution, process};
pub use parse::parse;

/// Bumped whenever a change in the algorithms alters stored results.
pub const METRICS_VERSION: u32 = 1;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Format {
    Gpx,
    Fit,
}

impl Format {
    pub fn extension(self) -> &'static str {
        match self {
            Format::Gpx => "gpx",
            Format::Fit => "fit",
        }
    }
}

/// One recorded point, as found in the file. Missing channels stay `None`.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Sample {
    /// Unix time in seconds.
    pub time: f64,
    pub lat: Option<f64>,
    pub lon: Option<f64>,
    /// Elevation in metres.
    pub ele: Option<f64>,
    /// Cumulative distance reported by the device, in metres.
    pub dist: Option<f64>,
    /// Heart rate in beats per minute.
    pub hr: Option<f64>,
    /// Cadence as written in the file (may be per leg; normalised later).
    pub cad: Option<f64>,
}

/// A file decoded into a format-independent activity.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Parsed {
    pub name: Option<String>,
    pub sport: Option<String>,
    pub samples: Vec<Sample>,
    /// Unix start time of each recorded lap. Empty when the source has no lap data.
    pub lap_starts: Vec<f64>,
    /// Recorded timer pauses as (from, to) Unix times. `None` when the source
    /// records no timer events, in which case pauses are detected from the data.
    pub pauses: Option<Vec<(f64, f64)>>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ParseError {
    /// Neither GPX nor FIT.
    Unrecognised,
    /// Recognised format, but the content could not be decoded.
    Corrupt(String),
    /// A GPX route or track without timestamps.
    NoTiming,
    /// No usable samples.
    Empty,
}

impl fmt::Display for ParseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ParseError::Unrecognised => write!(f, "The format is not recognised: only .gpx and .fit files are accepted."),
            ParseError::Corrupt(why) => write!(f, "The file is damaged and could not be read ({why})."),
            ParseError::NoTiming => write!(
                f,
                "The file is a route without timing data, not a recorded activity. It can be added to the routes instead."
            ),
            ParseError::Empty => write!(f, "The file contains no recorded samples."),
        }
    }
}

impl std::error::Error for ParseError {}
