//! Minimal RFC 3339 timestamp handling, enough for GPX files.

/// Days since 1970-01-01 of a civil date.
pub fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y.rem_euclid(400);
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

/// Civil date (year, month, day) of a day count since 1970-01-01.
pub fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (yoe + era * 400 + if m <= 2 { 1 } else { 0 }, m, d)
}

/// Parses `2026-11-08T08:42:10.5+01:00` style timestamps into Unix seconds.
pub fn parse_rfc3339(s: &str) -> Option<f64> {
    let s = s.trim();
    if s.len() < 19 || !s.is_ascii() {
        return None;
    }
    let num = |a: usize, z: usize| s.get(a..z)?.parse::<i64>().ok();
    let (y, mo, d, h, mi, se) = (num(0, 4)?, num(5, 7)?, num(8, 10)?, num(11, 13)?, num(14, 16)?, num(17, 19)?);
    if !(1..=12).contains(&mo) || !(1..=31).contains(&d) || h > 23 || mi > 59 || se > 60 {
        return None;
    }
    let mut rest = &s[19..];
    let mut frac = 0.0;
    if rest.starts_with('.') {
        let end = rest[1..].find(|c: char| !c.is_ascii_digit()).map_or(rest.len(), |i| i + 1);
        frac = rest[..end].parse::<f64>().ok()?;
        rest = &rest[end..];
    }
    let offset = match rest {
        "" | "Z" | "z" => 0,
        _ => {
            let sign = match rest.as_bytes()[0] {
                b'+' => 1,
                b'-' => -1,
                _ => return None,
            };
            let hh: i64 = rest.get(1..3)?.parse().ok()?;
            let mm: i64 = rest.get(rest.len() - 2..)?.parse().ok()?;
            sign * (hh * 3600 + mm * 60)
        }
    };
    Some((days_from_civil(y, mo, d) * 86_400 + h * 3600 + mi * 60 + se - offset) as f64 + frac)
}

/// Formats Unix seconds as a UTC timestamp with whole seconds.
pub fn format_rfc3339(unix: f64) -> String {
    let secs = unix.round() as i64;
    let (y, m, d) = civil_from_days(secs.div_euclid(86_400));
    let r = secs.rem_euclid(86_400);
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z", r / 3600, r % 3600 / 60, r % 60)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_utc_and_offsets() {
        assert_eq!(parse_rfc3339("1970-01-01T00:00:00Z"), Some(0.0));
        assert_eq!(parse_rfc3339("2026-11-08T08:42:10Z"), Some(1_794_127_330.0));
        assert_eq!(parse_rfc3339("2026-11-08T09:42:10+01:00"), Some(1_794_127_330.0));
        assert_eq!(parse_rfc3339("2026-11-08T08:42:10.500Z"), Some(1_794_127_330.5));
    }

    #[test]
    fn rejects_garbage() {
        assert_eq!(parse_rfc3339("yesterday"), None);
        assert_eq!(parse_rfc3339("2026-13-08T08:42:10Z"), None);
    }

    #[test]
    fn round_trips() {
        for t in [0.0, 951_782_400.0, 1_794_127_330.0] {
            assert_eq!(parse_rfc3339(&format_rfc3339(t)), Some(t));
        }
        assert_eq!(format_rfc3339(951_782_400.0), "2000-02-29T00:00:00Z");
    }
}
