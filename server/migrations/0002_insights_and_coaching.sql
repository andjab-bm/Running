-- Derived data, recurring segments, sessions, routes and their delivery to the phone.
-- Only new tables and nullable or defaulted columns: existing rows stay valid, and the
-- derive worker fills the new figures in the background (activities start at version 0).

ALTER TABLE activities ADD COLUMN load REAL;                                   -- training load; NULL until derived
ALTER TABLE activities ADD COLUMN load_estimated INTEGER NOT NULL DEFAULT 0;   -- 1 when not based on heart rate
ALTER TABLE activities ADD COLUMN derived_version INTEGER NOT NULL DEFAULT 0;  -- 0 = waiting for the derive worker
ALTER TABLE activities ADD COLUMN exclude_from_records INTEGER NOT NULL DEFAULT 0;
-- What the run was done with. Plain references, no cascade: deleting a session or a
-- route leaves the snapshot and the name in place.
ALTER TABLE activities ADD COLUMN workout_id INTEGER;
ALTER TABLE activities ADD COLUMN workout_snapshot TEXT;    -- JSON: name and steps as run
ALTER TABLE activities ADD COLUMN route_id INTEGER;
ALTER TABLE activities ADD COLUMN route_name TEXT;
ALTER TABLE activities ADD COLUMN pacer TEXT;               -- JSON: target, strategy, final gap

CREATE INDEX activities_by_derived_version ON activities (derived_version);

-- Fastest stretch of each standard distance inside one activity, timed on moving time.
CREATE TABLE best_efforts (
    activity_id    INTEGER NOT NULL REFERENCES activities (id) ON DELETE CASCADE,
    distance_m     REAL    NOT NULL,
    moving_s       REAL    NOT NULL,
    start_offset_m REAL    NOT NULL,   -- where the stretch starts, metres from the start of the run
    PRIMARY KEY (activity_id, distance_m)
) WITHOUT ROWID;

CREATE INDEX best_efforts_by_distance ON best_efforts (distance_m, moving_s);

-- Where each activity passes: its trace resampled along distance and mapped to grid cells.
CREATE TABLE trace_cells (
    activity_id INTEGER NOT NULL REFERENCES activities (id) ON DELETE CASCADE,
    seq         INTEGER NOT NULL,   -- order along the run
    profile_id  INTEGER NOT NULL,
    cell        INTEGER NOT NULL,
    bearing8    INTEGER NOT NULL,   -- direction of travel, eight sectors
    PRIMARY KEY (activity_id, seq)
) WITHOUT ROWID;

CREATE INDEX trace_cells_by_cell ON trace_cells (profile_id, cell);

-- Recurring portions of route ("segments" in the interface; the `segments` table holds splits and laps).
CREATE TABLE courses (
    id          INTEGER PRIMARY KEY,
    profile_id  INTEGER NOT NULL REFERENCES profiles (id) ON DELETE CASCADE,
    name        TEXT    NOT NULL,
    custom_name INTEGER NOT NULL DEFAULT 0,   -- 1 once renamed by the runner
    distance    REAL    NOT NULL,
    gain        REAL,
    hidden      INTEGER NOT NULL DEFAULT 0,
    points      BLOB    NOT NULL,             -- zstd JSON: lat, lon, ele, cumulative distance
    cells       TEXT    NOT NULL,             -- JSON array of [cell, bearing8] in order
    created_at  REAL    NOT NULL DEFAULT (unixepoch('subsec'))
);

CREATE INDEX courses_by_profile ON courses (profile_id);

CREATE TABLE course_efforts (
    id          INTEGER PRIMARY KEY,
    course_id   INTEGER NOT NULL REFERENCES courses (id) ON DELETE CASCADE,
    activity_id INTEGER NOT NULL REFERENCES activities (id) ON DELETE CASCADE,
    start_t     REAL    NOT NULL,   -- seconds from the start of the activity
    elapsed_s   REAL    NOT NULL,
    avg_hr      REAL
);

CREATE INDEX course_efforts_by_course ON course_efforts (course_id, elapsed_s);
CREATE INDEX course_efforts_by_activity ON course_efforts (activity_id);

-- Structured sessions. `steps` is the JSON document validated by the API and read by the phone.
CREATE TABLE workouts (
    id         INTEGER PRIMARY KEY,
    profile_id INTEGER NOT NULL REFERENCES profiles (id) ON DELETE CASCADE,
    name       TEXT    NOT NULL,
    note       TEXT    NOT NULL DEFAULT '',
    steps      TEXT    NOT NULL,
    on_phone   INTEGER NOT NULL DEFAULT 0,
    version    INTEGER NOT NULL DEFAULT 1,   -- bumped on every change
    updated_at REAL    NOT NULL DEFAULT (unixepoch('subsec')),
    created_at REAL    NOT NULL DEFAULT (unixepoch('subsec'))
);

CREATE INDEX workouts_by_profile ON workouts (profile_id);

CREATE TABLE routes (
    id            INTEGER PRIMARY KEY,
    profile_id    INTEGER NOT NULL REFERENCES profiles (id) ON DELETE CASCADE,
    name          TEXT    NOT NULL,
    distance      REAL    NOT NULL,
    gain          REAL,
    loss          REAL,
    is_loop       INTEGER NOT NULL DEFAULT 0,
    has_elevation INTEGER NOT NULL DEFAULT 0,
    points        BLOB    NOT NULL,   -- zstd JSON: lat, lon, ele, cumulative distance
    preview       TEXT    NOT NULL,   -- JSON array of [lat, lon] for thumbnails
    on_phone      INTEGER NOT NULL DEFAULT 0,
    version       INTEGER NOT NULL DEFAULT 1,
    updated_at    REAL    NOT NULL DEFAULT (unixepoch('subsec')),
    created_at    REAL    NOT NULL DEFAULT (unixepoch('subsec'))
);

CREATE INDEX routes_by_profile ON routes (profile_id);

-- What a phone says it holds, so the web can show "on the phone since…".
CREATE TABLE plan_receipts (
    profile_id  INTEGER NOT NULL REFERENCES profiles (id) ON DELETE CASCADE,
    kind        TEXT    NOT NULL CHECK (kind IN ('workout', 'route')),
    item_id     INTEGER NOT NULL,
    device      TEXT    NOT NULL,
    version     INTEGER NOT NULL,
    received_at REAL    NOT NULL DEFAULT (unixepoch('subsec')),
    PRIMARY KEY (profile_id, kind, item_id, device)
) WITHOUT ROWID;

-- Last time a phone synced a profile's plan, even when nothing was marked for it.
CREATE TABLE plan_syncs (
    profile_id INTEGER NOT NULL REFERENCES profiles (id) ON DELETE CASCADE,
    device     TEXT    NOT NULL,
    synced_at  REAL    NOT NULL,
    PRIMARY KEY (profile_id, device)
) WITHOUT ROWID;
