-- Household athlete profiles. There are no accounts or passwords.
CREATE TABLE profiles (
    id          INTEGER PRIMARY KEY,
    name        TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    color       TEXT    NOT NULL DEFAULT '#B9AFFF',
    hr_max      INTEGER,            -- NULL until configured: a documented default applies
    hr_rest     INTEGER,
    zone_bounds TEXT,               -- JSON array of six bpm values when zones are customised
    units       TEXT    NOT NULL DEFAULT 'metric' CHECK (units IN ('metric', 'imperial')),
    timezone    TEXT,               -- IANA name; NULL means "use the browser's"
    created_at  REAL    NOT NULL DEFAULT (unixepoch('subsec'))
);

-- One row per stored run. Summary metrics are denormalised for fast listing;
-- per-sample series live in the compressed `streams` blob.
CREATE TABLE activities (
    id              INTEGER PRIMARY KEY,
    profile_id      INTEGER NOT NULL REFERENCES profiles (id) ON DELETE CASCADE,
    title           TEXT    NOT NULL,
    description     TEXT    NOT NULL DEFAULT '',
    rpe             INTEGER CHECK (rpe BETWEEN 1 AND 10),
    start_time      REAL    NOT NULL,   -- Unix seconds, UTC
    sport           TEXT,
    source_format   TEXT    NOT NULL,   -- 'gpx' or 'fit'
    file_hash       TEXT,               -- SHA-256 of the original upload, shared by copies
    copied_from     INTEGER,            -- id of the activity this one was copied from
    metrics_version INTEGER NOT NULL,
    distance        REAL    NOT NULL,   -- metres
    elapsed_time    REAL    NOT NULL,   -- seconds
    moving_time     REAL    NOT NULL,
    speed           REAL    NOT NULL,   -- m/s over moving time
    gap_speed       REAL,
    gain            REAL,
    loss            REAL,
    avg_hr          REAL,
    max_hr          REAL,
    avg_cad         REAL,
    effort          REAL,
    zone_seconds    TEXT,               -- JSON array of five durations
    preview         TEXT    NOT NULL,   -- JSON array of [lat, lon] for list thumbnails
    streams         BLOB    NOT NULL,
    created_at      REAL    NOT NULL DEFAULT (unixepoch('subsec'))
);

CREATE INDEX activities_by_profile_time ON activities (profile_id, start_time DESC);
CREATE INDEX activities_by_profile_hash ON activities (profile_id, file_hash);
CREATE INDEX activities_by_hash ON activities (file_hash);

-- Distance splits and laps share one shape, told apart by `kind`.
CREATE TABLE segments (
    activity_id  INTEGER NOT NULL REFERENCES activities (id) ON DELETE CASCADE,
    kind         TEXT    NOT NULL CHECK (kind IN ('split', 'lap')),
    idx          INTEGER NOT NULL,
    start_t      REAL    NOT NULL,
    distance     REAL    NOT NULL,
    moving_time  REAL    NOT NULL,
    elapsed_time REAL    NOT NULL,
    speed        REAL    NOT NULL,
    gap_speed    REAL,
    gain         REAL,
    loss         REAL,
    avg_hr       REAL,
    avg_cad      REAL,
    PRIMARY KEY (activity_id, kind, idx)
) WITHOUT ROWID;

CREATE TABLE tags (
    id         INTEGER PRIMARY KEY,
    profile_id INTEGER NOT NULL REFERENCES profiles (id) ON DELETE CASCADE,
    name       TEXT    NOT NULL COLLATE NOCASE,
    UNIQUE (profile_id, name)
);

CREATE TABLE activity_tags (
    activity_id INTEGER NOT NULL REFERENCES activities (id) ON DELETE CASCADE,
    tag_id      INTEGER NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (activity_id, tag_id)
) WITHOUT ROWID;
