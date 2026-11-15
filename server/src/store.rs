//! Persistence of activities: summary rows, compressed streams and original files.
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::{Row, SqlitePool};

use crate::pipeline::{
    Format, METRICS_VERSION, Processed, Segment, Streams,
    metrics::{Units, ZoneConfig},
};

/// Maximum heart rate assumed until the profile sets one.
pub const DEFAULT_HR_MAX: f64 = 190.0;
/// Two activities starting within this many seconds may be the same run.
pub const DUPLICATE_START_S: f64 = 60.0;
/// ...if their durations also differ by less than this ratio.
pub const DUPLICATE_DURATION_RATIO: f64 = 0.02;
const PREVIEW_POINTS: usize = 64;

/// What is kept in the `streams` blob.
#[derive(Serialize, Deserialize, Debug, PartialEq)]
pub struct StoredStreams {
    pub streams: Streams,
    /// Indices of the reduced-resolution view.
    pub low_res: Vec<usize>,
}

pub fn encode_streams(s: &StoredStreams) -> anyhow::Result<Vec<u8>> {
    Ok(zstd::encode_all(serde_json::to_vec(s)?.as_slice(), 3)?)
}

pub fn decode_streams(blob: &[u8]) -> anyhow::Result<StoredStreams> {
    Ok(serde_json::from_slice(&zstd::decode_all(blob)?)?)
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes).iter().map(|b| format!("{b:02x}")).collect()
}

pub fn originals_dir(data_dir: &Path) -> PathBuf {
    data_dir.join("originals")
}

pub fn original_path(data_dir: &Path, hash: &str, format: &str) -> PathBuf {
    originals_dir(data_dir).join(format!("{hash}.{format}"))
}

/// Writes the unmodified upload once; activities sharing the same content share the file.
pub async fn save_original(data_dir: &Path, hash: &str, format: Format, bytes: &[u8]) -> anyhow::Result<()> {
    let path = original_path(data_dir, hash, format.extension());
    if !tokio::fs::try_exists(&path).await? {
        tokio::fs::create_dir_all(originals_dir(data_dir)).await?;
        let tmp = path.with_extension("part");
        tokio::fs::write(&tmp, bytes).await?;
        tokio::fs::rename(&tmp, &path).await?;
    }
    Ok(())
}

/// Removes the original file when no activity references it any more.
/// `hash` may carry the `#n` suffix given to activities of a multi-track file.
pub async fn remove_original_if_unused(db: &SqlitePool, data_dir: &Path, hash: &str, format: &str) -> anyhow::Result<()> {
    let base = hash.split('#').next().unwrap_or_default();
    let left: i64 = sqlx::query("SELECT COUNT(*) FROM activities WHERE file_hash = ? OR file_hash LIKE ?")
        .bind(base)
        .bind(format!("{base}#%"))
        .fetch_one(db)
        .await?
        .get(0);
    if left == 0 {
        match tokio::fs::remove_file(original_path(data_dir, base, format)).await {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(e.into()),
        }
    }
    Ok(())
}

/// Heart-rate zones and units of a profile, or `None` when the profile does not exist.
pub async fn profile_settings(db: &SqlitePool, profile_id: i64) -> anyhow::Result<Option<(ZoneConfig, Units)>> {
    let Some(row) = sqlx::query("SELECT hr_max, zone_bounds, units FROM profiles WHERE id = ?")
        .bind(profile_id)
        .fetch_optional(db)
        .await?
    else {
        return Ok(None);
    };
    let custom: Option<[f64; 6]> = row
        .get::<Option<String>, _>("zone_bounds")
        .and_then(|j| serde_json::from_str(&j).ok());
    let zones = match custom {
        Some(bounds) => ZoneConfig { bounds },
        None => ZoneConfig::from_max(row.get::<Option<i64>, _>("hr_max").map_or(DEFAULT_HR_MAX, |v| v as f64)),
    };
    let units = if row.get::<String, _>("units") == "imperial" {
        Units::Imperial
    } else {
        Units::Metric
    };
    Ok(Some((zones, units)))
}

/// An existing activity of the profile that is the same file or the same run.
pub async fn find_duplicate(db: &SqlitePool, profile_id: i64, hash: &str, start_time: f64, elapsed: f64) -> anyhow::Result<Option<i64>> {
    if let Some(row) = sqlx::query("SELECT id FROM activities WHERE profile_id = ? AND file_hash = ? LIMIT 1")
        .bind(profile_id)
        .bind(hash)
        .fetch_optional(db)
        .await?
    {
        return Ok(Some(row.get(0)));
    }
    let rows = sqlx::query("SELECT id, elapsed_time FROM activities WHERE profile_id = ? AND start_time BETWEEN ? AND ?")
        .bind(profile_id)
        .bind(start_time - DUPLICATE_START_S)
        .bind(start_time + DUPLICATE_START_S)
        .fetch_all(db)
        .await?;
    Ok(rows.into_iter().find_map(|r| {
        let other: f64 = r.get("elapsed_time");
        ((other - elapsed).abs() < DUPLICATE_DURATION_RATIO * elapsed.max(other)).then(|| r.get("id"))
    }))
}

fn preview(streams: &Streams) -> String {
    let fixes: Vec<[f64; 2]> = streams
        .lat
        .iter()
        .zip(&streams.lon)
        .filter_map(|(la, lo)| Some([(*la)?, (*lo)?]))
        .collect();
    let step = (fixes.len() as f64 / PREVIEW_POINTS as f64).max(1.0);
    let mut pts: Vec<[f64; 2]> = (0..)
        .map(|i| (i as f64 * step) as usize)
        .take_while(|i| *i < fixes.len())
        .map(|i| fixes[i].map(|v| (v * 1e5).round() / 1e5))
        .collect();
    if let (Some(last), Some(end)) = (pts.last().copied(), fixes.last()) {
        let end = end.map(|v| (v * 1e5).round() / 1e5);
        if last != end {
            pts.push(end);
        }
    }
    serde_json::to_string(&pts).unwrap_or_else(|_| "[]".into())
}

pub struct NewActivity<'a> {
    pub profile_id: i64,
    pub title: &'a str,
    pub format: &'a str,
    pub file_hash: Option<&'a str>,
    pub copied_from: Option<i64>,
    pub processed: &'a Processed,
}

async fn insert_segments(
    tx: &mut sqlx::SqliteConnection,
    activity_id: i64,
    kind: &'static str,
    segments: &[Segment],
) -> anyhow::Result<()> {
    for (i, s) in segments.iter().enumerate() {
        sqlx::query(
            "INSERT INTO segments (activity_id, kind, idx, start_t, distance, moving_time, elapsed_time, speed, gap_speed, gain, loss, avg_hr, avg_cad)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(activity_id)
        .bind(kind)
        .bind(i as i64)
        .bind(s.start_t)
        .bind(s.dist)
        .bind(s.moving_time)
        .bind(s.elapsed_time)
        .bind(s.speed)
        .bind(s.gap_speed)
        .bind(s.gain)
        .bind(s.loss)
        .bind(s.avg_hr)
        .bind(s.avg_cad)
        .execute(&mut *tx)
        .await?;
    }
    Ok(())
}

/// Stores a processed activity with its splits and laps, atomically.
pub async fn insert_activity(db: &SqlitePool, a: NewActivity<'_>) -> anyhow::Result<i64> {
    let p = a.processed;
    let w = &p.summary.whole;
    let blob = encode_streams(&StoredStreams {
        streams: p.streams.clone(),
        low_res: p.low_res.clone(),
    })?;
    let mut tx = db.begin().await?;
    let id = sqlx::query(
        "INSERT INTO activities (profile_id, title, start_time, sport, source_format, file_hash, copied_from, metrics_version,
            distance, elapsed_time, moving_time, speed, gap_speed, gain, loss, avg_hr, max_hr, avg_cad, effort, zone_seconds, preview, streams)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(a.profile_id)
    .bind(a.title)
    .bind(p.start_time)
    .bind(p.sport.as_deref())
    .bind(a.format)
    .bind(a.file_hash)
    .bind(a.copied_from)
    .bind(i64::from(METRICS_VERSION))
    .bind(w.dist)
    .bind(w.elapsed_time)
    .bind(w.moving_time)
    .bind(w.speed)
    .bind(w.gap_speed)
    .bind(w.gain)
    .bind(w.loss)
    .bind(w.avg_hr)
    .bind(p.summary.max_hr)
    .bind(w.avg_cad)
    .bind(p.zones.as_ref().map(|z| z.effort))
    .bind(p.zones.as_ref().map(|z| serde_json::to_string(&z.seconds).unwrap_or_default()))
    .bind(preview(&p.streams))
    .bind(blob)
    .execute(&mut *tx)
    .await?
    .last_insert_rowid();
    insert_segments(&mut tx, id, "split", &p.splits).await?;
    insert_segments(&mut tx, id, "lap", &p.laps).await?;
    tx.commit().await?;
    Ok(id)
}

/// Deletes an activity and, when it was the last one using it, its original file.
/// Returns false when the activity does not exist.
pub async fn delete_activity(db: &SqlitePool, data_dir: &Path, id: i64) -> anyhow::Result<bool> {
    let Some(row) = sqlx::query("SELECT file_hash, source_format FROM activities WHERE id = ?")
        .bind(id)
        .fetch_optional(db)
        .await?
    else {
        return Ok(false);
    };
    sqlx::query("DELETE FROM activities WHERE id = ?").bind(id).execute(db).await?;
    if let Some(hash) = row.get::<Option<String>, _>("file_hash") {
        remove_original_if_unused(db, data_dir, &hash, &row.get::<String, _>("source_format")).await?;
    }
    Ok(true)
}

/// Replaces the zone-based figures and the splits of one activity.
async fn rewrite_derived(
    tx: &mut sqlx::SqliteConnection,
    id: i64,
    streams: &Streams,
    zones: &ZoneConfig,
    units: Units,
) -> anyhow::Result<()> {
    let dist = crate::pipeline::metrics::zones(streams, zones);
    sqlx::query("UPDATE activities SET effort = ?, zone_seconds = ? WHERE id = ?")
        .bind(dist.as_ref().map(|z| z.effort))
        .bind(dist.as_ref().map(|z| serde_json::to_string(&z.seconds).unwrap_or_default()))
        .bind(id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM segments WHERE activity_id = ? AND kind = 'split'")
        .bind(id)
        .execute(&mut *tx)
        .await?;
    insert_segments(tx, id, "split", &crate::pipeline::metrics::splits(streams, units)).await
}

/// Recomputes zone times, effort and splits of every activity of a profile
/// from its current settings. Returns how many activities were updated.
pub async fn recompute_profile(db: &SqlitePool, profile_id: i64) -> anyhow::Result<usize> {
    let Some((zones, units)) = profile_settings(db, profile_id).await? else {
        return Ok(0);
    };
    let rows = sqlx::query("SELECT id, streams FROM activities WHERE profile_id = ?")
        .bind(profile_id)
        .fetch_all(db)
        .await?;
    let mut tx = db.begin().await?;
    for r in &rows {
        let stored = decode_streams(&r.get::<Vec<u8>, _>("streams"))?;
        rewrite_derived(&mut tx, r.get("id"), &stored.streams, &zones, units).await?;
    }
    tx.commit().await?;
    Ok(rows.len())
}

pub enum CopyOutcome {
    Created(i64),
    /// The target profile already holds this run.
    AlreadyPresent(i64),
    SourceMissing,
    TargetMissing,
    SameProfile,
}

/// Copies an activity to another profile as an independent activity with an empty journal.
/// Heart rate belongs to whoever recorded the run, so it is dropped unless `keep_hr`.
pub async fn copy_activity(db: &SqlitePool, id: i64, target: i64, keep_hr: bool) -> anyhow::Result<CopyOutcome> {
    let Some(src) = sqlx::query(
        "SELECT profile_id, title, start_time, sport, source_format, file_hash, copied_from, streams FROM activities WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(db)
    .await?
    else {
        return Ok(CopyOutcome::SourceMissing);
    };
    if src.get::<i64, _>("profile_id") == target {
        return Ok(CopyOutcome::SameProfile);
    }
    let Some((zones, units)) = profile_settings(db, target).await? else {
        return Ok(CopyOutcome::TargetMissing);
    };
    // Copies of copies still point at the first recording.
    let root = src.get::<Option<i64>, _>("copied_from").unwrap_or(id);
    if let Some(existing) = sqlx::query("SELECT id FROM activities WHERE profile_id = ? AND (copied_from = ? OR id = ?) LIMIT 1")
        .bind(target)
        .bind(root)
        .bind(root)
        .fetch_optional(db)
        .await?
    {
        return Ok(CopyOutcome::AlreadyPresent(existing.get(0)));
    }

    let stored = decode_streams(&src.get::<Vec<u8>, _>("streams"))?;
    let mut streams = stored.streams;
    if !keep_hr {
        streams.hr = None;
    }
    let start_time: f64 = src.get("start_time");
    let lap_starts: Vec<f64> = sqlx::query("SELECT start_t FROM segments WHERE activity_id = ? AND kind = 'lap' ORDER BY idx")
        .bind(id)
        .fetch_all(db)
        .await?
        .iter()
        .map(|r| start_time + r.get::<f64, _>(0))
        .collect();
    use crate::pipeline::metrics as m;
    let processed = Processed {
        start_time,
        name: None,
        sport: src.get("sport"),
        summary: m::Summary {
            whole: m::segment(&streams, m::Axis::Time, 0.0, streams.elapsed()),
            max_hr: streams.hr.as_ref().and_then(|h| h.iter().flatten().copied().reduce(f64::max)),
        },
        splits: m::splits(&streams, units),
        laps: m::laps(&streams, &lap_starts, start_time),
        zones: m::zones(&streams, &zones),
        low_res: stored.low_res,
        streams,
    };
    let new_id = insert_activity(
        db,
        NewActivity {
            profile_id: target,
            title: &src.get::<String, _>("title"),
            format: &src.get::<String, _>("source_format"),
            file_hash: src.get::<Option<String>, _>("file_hash").as_deref(),
            copied_from: Some(root),
            processed: &processed,
        },
    )
    .await?;
    Ok(CopyOutcome::Created(new_id))
}

pub enum MoveOutcome {
    Moved,
    SourceMissing,
    TargetMissing,
    SameProfile,
}

/// Moves an activity to another profile, keeping its journal and tags (by name)
/// and recomputing zone-based figures with the target profile's settings.
pub async fn move_activity(db: &SqlitePool, id: i64, target: i64) -> anyhow::Result<MoveOutcome> {
    let Some(src) = sqlx::query("SELECT profile_id, streams FROM activities WHERE id = ?")
        .bind(id)
        .fetch_optional(db)
        .await?
    else {
        return Ok(MoveOutcome::SourceMissing);
    };
    if src.get::<i64, _>("profile_id") == target {
        return Ok(MoveOutcome::SameProfile);
    }
    let Some((zones, units)) = profile_settings(db, target).await? else {
        return Ok(MoveOutcome::TargetMissing);
    };
    let stored = decode_streams(&src.get::<Vec<u8>, _>("streams"))?;
    let tags: Vec<String> = sqlx::query("SELECT t.name FROM activity_tags x JOIN tags t ON t.id = x.tag_id WHERE x.activity_id = ?")
        .bind(id)
        .fetch_all(db)
        .await?
        .iter()
        .map(|r| r.get(0))
        .collect();

    let mut tx = db.begin().await?;
    sqlx::query("DELETE FROM activity_tags WHERE activity_id = ?")
        .bind(id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE activities SET profile_id = ? WHERE id = ?")
        .bind(target)
        .bind(id)
        .execute(&mut *tx)
        .await?;
    for name in &tags {
        sqlx::query("INSERT OR IGNORE INTO tags (profile_id, name) VALUES (?, ?)")
            .bind(target)
            .bind(name)
            .execute(&mut *tx)
            .await?;
        sqlx::query("INSERT OR IGNORE INTO activity_tags (activity_id, tag_id) SELECT ?, id FROM tags WHERE profile_id = ? AND name = ?")
            .bind(id)
            .bind(target)
            .bind(name)
            .execute(&mut *tx)
            .await?;
    }
    rewrite_derived(&mut tx, id, &stored.streams, &zones, units).await?;
    tx.commit().await?;
    Ok(MoveOutcome::Moved)
}

/// Deletes a profile with all its activities, then the original files nothing else uses.
/// Returns false when the profile does not exist.
pub async fn delete_profile(db: &SqlitePool, data_dir: &Path, profile_id: i64) -> anyhow::Result<bool> {
    let files = sqlx::query("SELECT DISTINCT file_hash, source_format FROM activities WHERE profile_id = ? AND file_hash IS NOT NULL")
        .bind(profile_id)
        .fetch_all(db)
        .await?;
    if sqlx::query("DELETE FROM profiles WHERE id = ?")
        .bind(profile_id)
        .execute(db)
        .await?
        .rows_affected()
        == 0
    {
        return Ok(false);
    }
    for f in &files {
        remove_original_if_unused(db, data_dir, &f.get::<String, _>("file_hash"), &f.get::<String, _>("source_format")).await?;
    }
    Ok(true)
}
