//! Recurring segments in the database: the index of where each activity passes,
//! the efforts of an activity on known segments, and the creation of new segments.
use std::collections::{HashMap, HashSet};

use serde::{Deserialize, Serialize};
use sqlx::{Row, SqlitePool};

use crate::{
    pipeline::{
        courses::{self, CELL_ROW, MIN_TRAVERSALS, Passage, TracePoint},
        metrics::{self, Axis, Streams},
    },
    store,
};

/// Cells looked up per query when finding who else passes somewhere.
const LOOKUP_BATCH: usize = 500;

/// What is kept in `courses.points`.
#[derive(Serialize, Deserialize)]
pub struct CourseShape {
    /// Resampled path; `dist` starts at zero.
    pub points: Vec<TracePoint>,
    /// Elevation at each point, when the run it was taken from had elevation.
    pub ele: Option<Vec<f64>>,
}

pub fn encode_shape(shape: &CourseShape) -> anyhow::Result<Vec<u8>> {
    Ok(zstd::encode_all(serde_json::to_vec(shape)?.as_slice(), 3)?)
}

pub fn decode_shape(blob: &[u8]) -> anyhow::Result<CourseShape> {
    Ok(serde_json::from_slice(&zstd::decode_all(blob)?)?)
}

/// Elevation of the activity at a distance along it.
fn elevation_at(s: &Streams, ele: &[f64], dist: f64) -> f64 {
    let i = s.dist.partition_point(|d| *d < dist).clamp(1, s.len() - 1);
    let span = s.dist[i] - s.dist[i - 1];
    let f = if span > 0.0 {
        ((dist - s.dist[i - 1]) / span).clamp(0.0, 1.0)
    } else {
        0.0
    };
    ele[i - 1] + (ele[i] - ele[i - 1]) * f
}

async fn insert_efforts(db: &SqlitePool, course_id: i64, activity_id: i64, s: &Streams, found: &[Passage]) -> anyhow::Result<()> {
    for p in found {
        // Timed on the clock: a segment effort is a duel with one's earlier passages, stops included.
        let seg = metrics::segment(s, Axis::Dist, p.start_dist, p.end_dist);
        sqlx::query("INSERT INTO course_efforts (course_id, activity_id, start_t, elapsed_s, avg_hr) VALUES (?, ?, ?, ?, ?)")
            .bind(course_id)
            .bind(activity_id)
            .bind(seg.start_t)
            .bind(seg.elapsed_time)
            .bind(seg.avg_hr)
            .execute(db)
            .await?;
    }
    Ok(())
}

/// Streams and resampled trace of another activity, decoded off the async threads.
async fn load_trace(db: &SqlitePool, activity_id: i64) -> anyhow::Result<Option<(Streams, Vec<TracePoint>)>> {
    let Some(row) = sqlx::query("SELECT streams FROM activities WHERE id = ?")
        .bind(activity_id)
        .fetch_optional(db)
        .await?
    else {
        return Ok(None);
    };
    let blob: Vec<u8> = row.get(0);
    let out = tokio::task::spawn_blocking(move || -> anyhow::Result<_> {
        let streams = store::decode_streams(&blob)?.streams;
        let points = courses::resample(&streams);
        Ok((streams, points))
    })
    .await??;
    Ok(Some(out))
}

/// Brings one activity up to date: where it passes, its efforts on the profile's
/// segments, and the segments its passage creates.
pub async fn refresh_activity(db: &SqlitePool, id: i64, profile_id: i64, s: &Streams, points: &[TracePoint]) -> anyhow::Result<()> {
    // 1. Where it passes. Efforts are rebuilt below, for the profile it belongs to now.
    let mut tx = db.begin().await?;
    sqlx::query("DELETE FROM trace_cells WHERE activity_id = ?")
        .bind(id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM course_efforts WHERE activity_id = ?")
        .bind(id)
        .execute(&mut *tx)
        .await?;
    for (seq, p) in points.iter().enumerate() {
        sqlx::query("INSERT INTO trace_cells (activity_id, seq, profile_id, cell, bearing8) VALUES (?, ?, ?, ?, ?)")
            .bind(id)
            .bind(seq as i64)
            .bind(profile_id)
            .bind(p.cell())
            .bind(p.sector())
            .execute(&mut *tx)
            .await?;
    }
    tx.commit().await?;
    if points.len() < 2 {
        return Ok(());
    }

    // 2. Efforts on the segments the profile already has, hidden ones included,
    //    so that a restored segment has its whole history.
    let rows = sqlx::query("SELECT id, points FROM courses WHERE profile_id = ?")
        .bind(profile_id)
        .fetch_all(db)
        .await?;
    let mut known: Vec<Vec<TracePoint>> = Vec::new();
    for r in &rows {
        let shape = decode_shape(&r.get::<Vec<u8>, _>("points"))?;
        insert_efforts(db, r.get("id"), id, s, &courses::passages(points, &shape.points)).await?;
        known.push(shape.points);
    }

    // 3. Who else passes where this activity passes, in the same direction.
    //    The cells around each point are looked up by value, in batches: a join would leave
    //    the choice of the driving table to the planner, which gets it badly wrong here.
    let around = [
        -CELL_ROW - 1,
        -CELL_ROW,
        -CELL_ROW + 1,
        -1,
        0,
        1,
        CELL_ROW - 1,
        CELL_ROW,
        CELL_ROW + 1,
    ];
    let cells: Vec<i64> = points.iter().map(TracePoint::cell).collect();
    let mut wanted: Vec<i64> = cells.iter().flat_map(|c| around.iter().map(move |d| c + d)).collect();
    wanted.sort_unstable();
    wanted.dedup();
    let mut present: HashMap<i64, Vec<(i64, i64)>> = HashMap::new();
    for batch in wanted.chunks(LOOKUP_BATCH) {
        let marks = vec!["?"; batch.len()].join(",");
        // Only placeholders are interpolated; every value is bound.
        let sql =
            format!("SELECT cell, activity_id, bearing8 FROM trace_cells WHERE profile_id = ? AND activity_id <> ? AND cell IN ({marks})");
        let mut query = sqlx::query(sqlx::AssertSqlSafe(sql)).bind(profile_id).bind(id);
        for cell in batch {
            query = query.bind(cell);
        }
        for r in query.fetch_all(db).await? {
            present
                .entry(r.get("cell"))
                .or_default()
                .push((r.get("activity_id"), r.get("bearing8")));
        }
    }
    let sharing: Vec<HashSet<i64>> = points
        .iter()
        .zip(&cells)
        .map(|(p, cell)| {
            let mine = p.sector();
            around
                .iter()
                .filter_map(|d| present.get(&(cell + d)))
                .flatten()
                .filter(|(_, theirs)| courses::sectors_agree(mine, *theirs))
                .map(|(activity, _)| *activity)
                .collect()
        })
        .collect();
    // 4. New segments: stretches shared with enough other runs that no segment covers yet.
    //    Candidates are recomputed after each creation, so that the laps of a loop, or a path
    //    run out and back, do not each become a copy of the segment just created.
    let mut from = 0;
    loop {
        let covered = courses::covered(points, &known);
        let Some((a, b)) = courses::candidates(points, &sharing, &covered)
            .into_iter()
            .find(|(a, _)| *a >= from)
        else {
            break;
        };
        from = b + 1;
        let origin = points[a].dist;
        let shape = CourseShape {
            points: points[a..=b]
                .iter()
                .map(|p| TracePoint {
                    dist: p.dist - origin,
                    ..*p
                })
                .collect(),
            ele: s
                .ele
                .as_ref()
                .map(|e| points[a..=b].iter().map(|p| elevation_at(s, e, p.dist)).collect()),
        };
        let whole = metrics::segment(s, Axis::Dist, points[a].dist, points[b].dist);
        let course_id = sqlx::query("INSERT INTO courses (profile_id, name, distance, gain, points) VALUES (?, ?, ?, ?, ?)")
            .bind(profile_id)
            .bind(courses::default_name(&shape.points))
            .bind(points[b].dist - origin)
            .bind(whole.gain)
            .bind(encode_shape(&shape)?)
            .execute(db)
            .await?
            .last_insert_rowid();

        // This run, and the earlier runs that pass here, get their efforts at once.
        insert_efforts(db, course_id, id, s, &courses::passages(points, &shape.points)).await?;
        let others: HashSet<i64> = sharing[a..=b].iter().flatten().copied().collect();
        for other in others {
            if let Some((streams, trace)) = load_trace(db, other).await? {
                insert_efforts(db, course_id, other, &streams, &courses::passages(&trace, &shape.points)).await?;
            }
            tokio::task::yield_now().await;
        }
        // Sharing cells is a hint; only complete passages count. Too few: not a segment after all.
        let runs: i64 = sqlx::query("SELECT COUNT(DISTINCT activity_id) FROM course_efforts WHERE course_id = ?")
            .bind(course_id)
            .fetch_one(db)
            .await?
            .get(0);
        if (runs as usize) < MIN_TRAVERSALS {
            sqlx::query("DELETE FROM courses WHERE id = ?").bind(course_id).execute(db).await?;
        } else {
            known.push(shape.points);
        }
    }
    Ok(())
}
