//! Everything computed across or after activities — best efforts, training load,
//! recurring segments — is produced here, in the background, by one worker.
//!
//! Requests never wait for it: they commit their own change, then enqueue a job.
//! An activity whose `derived_version` is older than [`DERIVED_VERSION`] is waiting
//! for the worker; that is also how history is filled in after an upgrade.
use std::{
    collections::{HashSet, VecDeque},
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
    },
};

use sqlx::{Row, SqlitePool};

use crate::{
    pipeline::{
        efforts::{BestEffort, best_efforts},
        load::activity_load,
    },
    store,
};
use tokio::sync::mpsc;

/// Bumped whenever a derived figure would come out differently, so that it is recomputed.
pub const DERIVED_VERSION: i64 = 1;
/// How many finished jobs are remembered, for diagnostics and tests.
const HISTORY: usize = 64;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Job {
    /// Derive one activity, then refresh what its profile shares (segments).
    Activity(i64),
    /// Derive the profile's outdated activities, then refresh what the profile shares.
    /// Enqueued when an activity left the profile or its settings changed.
    Profile(i64),
    /// Derive every outdated activity of every profile.
    Backfill,
}

struct Shared {
    queued: AtomicUsize,
    done: Mutex<VecDeque<Job>>,
}

/// Handle used by request handlers to hand work to the worker.
#[derive(Clone)]
pub struct Deriver {
    tx: mpsc::UnboundedSender<Job>,
    shared: Arc<Shared>,
}

/// The receiving end: run it once with [`Worker::run`].
pub struct Worker {
    rx: mpsc::UnboundedReceiver<Job>,
    shared: Arc<Shared>,
}

impl Deriver {
    pub fn channel() -> (Deriver, Worker) {
        let (tx, rx) = mpsc::unbounded_channel();
        let shared = Arc::new(Shared {
            queued: AtomicUsize::new(0),
            done: Mutex::new(VecDeque::new()),
        });
        (
            Deriver {
                tx,
                shared: shared.clone(),
            },
            Worker { rx, shared },
        )
    }

    /// Never blocks and never fails the caller: if the worker is gone the job is dropped,
    /// and the activity stays outdated until the next start.
    pub fn enqueue(&self, job: Job) {
        self.shared.queued.fetch_add(1, Ordering::SeqCst);
        if self.tx.send(job).is_err() {
            self.shared.queued.fetch_sub(1, Ordering::SeqCst);
        }
    }

    /// Jobs enqueued and not finished yet.
    pub fn queued(&self) -> usize {
        self.shared.queued.load(Ordering::SeqCst)
    }

    /// Resolves once every enqueued job has been processed.
    pub async fn idle(&self) {
        while self.queued() != 0 {
            tokio::time::sleep(std::time::Duration::from_millis(5)).await;
        }
    }

    /// The most recently finished jobs, oldest first.
    pub fn recent(&self) -> Vec<Job> {
        self.shared.done.lock().map(|d| d.iter().copied().collect()).unwrap_or_default()
    }
}

impl Worker {
    /// Processes jobs one at a time until every [`Deriver`] is dropped.
    pub async fn run(mut self, db: SqlitePool) {
        while let Some(first) = self.rx.recv().await {
            // Take everything already waiting, so that redundant jobs are done once.
            let mut batch = vec![first];
            while let Ok(job) = self.rx.try_recv() {
                batch.push(job);
            }
            let taken = batch.len();
            for job in coalesce(&db, batch).await {
                if let Err(e) = run_job(&db, job).await {
                    tracing::error!("derive job {job:?} failed: {e:#}");
                }
                if let Ok(mut done) = self.shared.done.lock() {
                    if done.len() == HISTORY {
                        done.pop_front();
                    }
                    done.push_back(job);
                }
                tokio::task::yield_now().await;
            }
            self.shared.queued.fetch_sub(taken, Ordering::SeqCst);
        }
    }
}

/// Drops repeated jobs, and activity jobs already covered by a job for their profile.
async fn coalesce(db: &SqlitePool, batch: Vec<Job>) -> Vec<Job> {
    let profiles: HashSet<i64> = batch
        .iter()
        .filter_map(|j| match j {
            Job::Profile(p) => Some(*p),
            _ => None,
        })
        .collect();
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for job in batch {
        if !seen.insert(job) {
            continue;
        }
        if let Job::Activity(id) = job
            && !profiles.is_empty()
            && let Ok(Some(row)) = sqlx::query("SELECT profile_id FROM activities WHERE id = ?")
                .bind(id)
                .fetch_optional(db)
                .await
            && profiles.contains(&row.get::<i64, _>(0))
        {
            continue;
        }
        out.push(job);
    }
    // Activities first: a profile job then only has the shared part left to do.
    out.sort_by_key(|j| match j {
        Job::Activity(_) => 0,
        Job::Profile(_) => 1,
        Job::Backfill => 2,
    });
    out
}

async fn run_job(db: &SqlitePool, job: Job) -> anyhow::Result<()> {
    match job {
        Job::Activity(id) => {
            if let Some(profile_id) = derive_activity(db, id).await? {
                derive_shared(db, profile_id).await?;
            }
        }
        Job::Profile(profile_id) => {
            for id in outdated(db, Some(profile_id)).await? {
                derive_activity(db, id).await?;
                tokio::task::yield_now().await;
            }
            derive_shared(db, profile_id).await?;
        }
        Job::Backfill => {
            let mut profiles = HashSet::new();
            for id in outdated(db, None).await? {
                if let Some(p) = derive_activity(db, id).await? {
                    profiles.insert(p);
                }
                tokio::task::yield_now().await;
            }
            for p in profiles {
                derive_shared(db, p).await?;
            }
        }
    }
    Ok(())
}

async fn outdated(db: &SqlitePool, profile_id: Option<i64>) -> anyhow::Result<Vec<i64>> {
    let rows = sqlx::query("SELECT id FROM activities WHERE derived_version < ? AND (? IS NULL OR profile_id = ?) ORDER BY start_time")
        .bind(DERIVED_VERSION)
        .bind(profile_id)
        .bind(profile_id)
        .fetch_all(db)
        .await?;
    Ok(rows.iter().map(|r| r.get(0)).collect())
}

/// Computes everything that belongs to one activity and marks it up to date.
/// Returns its profile, or `None` when the activity no longer exists.
async fn derive_activity(db: &SqlitePool, id: i64) -> anyhow::Result<Option<i64>> {
    let Some(row) = sqlx::query("SELECT profile_id, streams, effort, moving_time, rpe FROM activities WHERE id = ?")
        .bind(id)
        .fetch_optional(db)
        .await?
    else {
        return Ok(None);
    };
    let profile_id: i64 = row.get(0);
    let blob: Vec<u8> = row.get(1);
    // Decompressing and scanning a long run is CPU work: keep it off the async threads.
    let computed = tokio::task::spawn_blocking(move || -> anyhow::Result<Vec<BestEffort>> {
        Ok(best_efforts(&store::decode_streams(&blob)?.streams))
    })
    .await?;

    let mut tx = db.begin().await?;
    match &computed {
        Ok(efforts) => {
            sqlx::query("DELETE FROM best_efforts WHERE activity_id = ?")
                .bind(id)
                .execute(&mut *tx)
                .await?;
            for e in efforts {
                sqlx::query("INSERT INTO best_efforts (activity_id, distance_m, moving_s, start_offset_m) VALUES (?, ?, ?, ?)")
                    .bind(id)
                    .bind(e.distance)
                    .bind(e.moving_s)
                    .bind(e.start_offset_m)
                    .execute(&mut *tx)
                    .await?;
            }
        }
        // A failure in one figure must not leave the activity "processing" for ever:
        // it is logged and the activity is retried at the next version bump.
        Err(e) => tracing::error!("activity {id}: best efforts not computed: {e:#}"),
    }
    // Training load: the heart-rate effort when there is one, an estimate otherwise.
    let (load, estimated) = activity_load(row.get("effort"), row.get("moving_time"), row.get("rpe"));
    sqlx::query("UPDATE activities SET load = ?, load_estimated = ?, derived_version = ? WHERE id = ?")
        .bind(load)
        .bind(estimated)
        .bind(DERIVED_VERSION)
        .bind(id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(Some(profile_id))
}

/// Refreshes what the activities of a profile share. Nothing yet: recurring
/// segments are detected and matched here.
async fn derive_shared(_db: &SqlitePool, _profile_id: i64) -> anyhow::Result<()> {
    Ok(())
}

/// Marks activities as waiting for the worker again.
pub async fn invalidate_activity(db: &SqlitePool, id: i64) -> anyhow::Result<()> {
    sqlx::query("UPDATE activities SET derived_version = 0 WHERE id = ?")
        .bind(id)
        .execute(db)
        .await?;
    Ok(())
}

pub async fn invalidate_profile(db: &SqlitePool, profile_id: i64) -> anyhow::Result<()> {
    sqlx::query("UPDATE activities SET derived_version = 0 WHERE profile_id = ?")
        .bind(profile_id)
        .execute(db)
        .await?;
    Ok(())
}

/// How many of a profile's activities are waiting for the worker, out of how many.
pub async fn status(db: &SqlitePool, profile_id: i64) -> anyhow::Result<(i64, i64)> {
    let row = sqlx::query("SELECT COALESCE(SUM(derived_version < ?), 0), COUNT(*) FROM activities WHERE profile_id = ?")
        .bind(DERIVED_VERSION)
        .bind(profile_id)
        .fetch_one(db)
        .await?;
    Ok((row.get(0), row.get(1)))
}
