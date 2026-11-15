//! `POST /profiles/{profile_id}/imports`: upload one or more `.gpx` / `.fit` files.
use axum::{
    Json,
    extract::{Multipart, Path, Query, State},
};
use serde::{Deserialize, Serialize};

use super::{ApiError, ApiResult};
use crate::{
    AppState,
    pipeline::{self, Processed},
    store,
};

#[derive(Deserialize)]
pub struct ImportQuery {
    /// Minutes east of UTC at the uploader, used only to name untitled runs by time of day.
    pub tz_offset_minutes: Option<i32>,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "lowercase")]
pub enum Status {
    Imported,
    Duplicate,
    Failed,
}

/// Outcome for one activity found in one uploaded file.
#[derive(Serialize, Debug)]
pub struct Outcome {
    pub file: String,
    pub status: Status,
    /// The created activity, or the existing one for a duplicate.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub activity_id: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

impl Outcome {
    fn failed(file: &str, reason: impl Into<String>) -> Self {
        Self {
            file: file.to_string(),
            status: Status::Failed,
            activity_id: None,
            reason: Some(reason.into()),
        }
    }
}

#[derive(Serialize)]
pub struct ImportResponse {
    pub results: Vec<Outcome>,
}

/// Names a run after the local time of day it started at.
pub fn default_title(start_time: f64, tz_offset_minutes: i32) -> &'static str {
    let local = start_time as i64 + i64::from(tz_offset_minutes) * 60;
    match local.rem_euclid(86_400) / 3600 {
        5..=10 => "Sortie matinale",
        11..=13 => "Sortie du midi",
        14..=17 => "Sortie de l'après-midi",
        18..=21 => "Sortie du soir",
        _ => "Sortie nocturne",
    }
}

pub async fn import(
    State(state): State<AppState>,
    Path(profile_id): Path<i64>,
    Query(query): Query<ImportQuery>,
    mut multipart: Multipart,
) -> ApiResult<Json<ImportResponse>> {
    let Some((zones, units)) = store::profile_settings(&state.db, profile_id).await? else {
        return Err(ApiError::not_found("This profile"));
    };
    let max = state.config.max_upload_bytes;
    let mut results = Vec::new();

    loop {
        let mut field = match multipart.next_field().await {
            Ok(Some(f)) => f,
            Ok(None) => break,
            Err(e) => return Err(ApiError::bad_request(format!("The upload could not be read: {e}"))),
        };
        let Some(file) = field.file_name().map(str::to_string) else {
            continue;
        };

        // Read at most `max` bytes; the rest of an oversized file is drained and discarded.
        let mut bytes: Vec<u8> = Vec::new();
        let mut too_large = false;
        loop {
            match field.chunk().await {
                Ok(Some(chunk)) => {
                    if too_large || bytes.len() + chunk.len() > max {
                        too_large = true;
                        bytes = Vec::new();
                    } else {
                        bytes.extend_from_slice(&chunk);
                    }
                }
                Ok(None) => break,
                Err(e) => return Err(ApiError::bad_request(format!("The upload was interrupted: {e}"))),
            }
        }
        if too_large {
            results.push(Outcome::failed(
                &file,
                format!("The file is larger than the {} MB limit.", max / (1024 * 1024)),
            ));
            continue;
        }

        // Parsing and metric computation are CPU-bound: run them off the async
        // threads, a few at a time, so a large batch cannot exhaust a small machine.
        let permit = state.import_slots.clone().acquire_owned().await?;
        let (bytes, parsed) = tokio::task::spawn_blocking(move || {
            let _permit = permit;
            let out = pipeline::parse(&bytes).map(|(format, acts)| {
                let processed: Vec<Result<Processed, pipeline::ParseError>> =
                    acts.iter().map(|a| pipeline::process(a, &zones, units)).collect();
                (format, processed)
            });
            (bytes, out)
        })
        .await?;

        let (format, activities) = match parsed {
            Ok(v) => v,
            Err(e) => {
                results.push(Outcome::failed(&file, e.to_string()));
                continue;
            }
        };
        let hash = store::sha256_hex(&bytes);
        let multi = activities.len() > 1;
        for (n, processed) in activities.into_iter().enumerate() {
            let processed = match processed {
                Ok(p) => p,
                Err(e) => {
                    results.push(Outcome::failed(&file, e.to_string()));
                    continue;
                }
            };
            // A file holding several tracks cannot be matched by its hash alone.
            let lookup_hash = if multi { format!("{hash}#{n}") } else { hash.clone() };
            let elapsed = processed.summary.whole.elapsed_time;
            if let Some(existing) = store::find_duplicate(&state.db, profile_id, &lookup_hash, processed.start_time, elapsed).await? {
                results.push(Outcome {
                    file: file.clone(),
                    status: Status::Duplicate,
                    activity_id: Some(existing),
                    reason: Some("This run is already in the profile.".into()),
                });
                continue;
            }
            store::save_original(&state.config.data_dir, &hash, format, &bytes).await?;
            let title = processed
                .name
                .clone()
                .filter(|n| !n.trim().is_empty())
                .unwrap_or_else(|| default_title(processed.start_time, query.tz_offset_minutes.unwrap_or(0)).to_string());
            let id = store::insert_activity(
                &state.db,
                store::NewActivity {
                    profile_id,
                    title: &title,
                    format: format.extension(),
                    file_hash: Some(&lookup_hash),
                    copied_from: None,
                    processed: &processed,
                },
            )
            .await?;
            results.push(Outcome {
                file: file.clone(),
                status: Status::Imported,
                activity_id: Some(id),
                reason: None,
            });
        }
    }
    Ok(Json(ImportResponse { results }))
}
