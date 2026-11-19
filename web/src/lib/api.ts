// Typed client for the REST API described in docs/api/openapi.json.
// Quantities are SI (metres, seconds, m/s) and times are Unix seconds; see format.ts for display.

export type Units = 'metric' | 'imperial';

export interface Zones {
	bounds: number[];
	custom: boolean;
	uses_default_hr_max: boolean;
	default_hr_max: number;
}

export interface Profile {
	id: number;
	name: string;
	color: string;
	hr_max: number | null;
	hr_rest: number | null;
	units: Units;
	timezone: string | null;
	activity_count: number;
	zones: Zones;
}

export interface Segment {
	start_t: number;
	distance: number;
	moving_time: number;
	elapsed_time: number;
	speed: number;
	gap_speed: number | null;
	gain: number | null;
	loss: number | null;
	avg_hr: number | null;
	avg_cad: number | null;
}

export interface ActivitySummary {
	id: number;
	profile_id: number;
	title: string;
	start_time: number;
	sport: string | null;
	distance: number;
	elapsed_time: number;
	moving_time: number;
	speed: number;
	gap_speed: number | null;
	gain: number | null;
	loss: number | null;
	avg_hr: number | null;
	max_hr: number | null;
	avg_cad: number | null;
	effort: number | null;
	rpe: number | null;
	copied_from: number | null;
	tags: string[];
	preview: [number, number][];
	/** Training load; null until the background computation has processed the run. */
	load: number | null;
	load_estimated: boolean;
	/** Household list only: the copies of this run in other profiles. */
	shared_with?: { profile_id: number; activity_id: number }[];
}

/** The fastest stretch of a standard distance inside one run, on moving time. */
export interface Effort {
	moving_time: number;
	speed: number;
	activity_id: number;
	title: string;
	start_time: number;
	start_offset: number;
}

export interface RecordDistance {
	distance: number;
	record: Effort | null;
	next: Effort[];
	history: Effort[];
}

export interface Prediction {
	distance: number;
	time: number;
	speed: number;
	indicative: boolean;
	basis: { distance: number; moving_time: number; activity_id: number; title: string; start_time: number };
}

export interface CalendarDay {
	date: string;
	distance: number;
	moving_time: number;
	load: number;
	activities: { id: number; title: string; distance: number }[];
}

export interface Calendar {
	year: number;
	days: CalendarDay[];
	totals: { runs: number; active_days: number; distance: number; moving_time: number; gain: number };
}

export interface VolumePeriod {
	start: string;
	runs: number;
	distance: number;
	moving_time: number;
	gain: number;
	current: boolean;
}

export interface ProgressMonth {
	month: string;
	runs: number;
	avg_speed: number | null;
	longest: number | null;
	avg_hr: number | null;
}

export type LoadStatus = 'not_enough_data' | 'resuming' | 'light' | 'steady' | 'building' | 'too_much';

export interface Load {
	days: { date: string; load: number; estimated: boolean; short: number; long: number }[];
	weeks: { start: string; load: number; current: boolean }[];
	status: { code: LoadStatus; ratio: number | null; short: number | null; long: number | null; history_days: number };
	recovery: {
		activity_id: number;
		load: number;
		estimated: boolean;
		hours_advised: number;
		hours_remaining: number;
		recovered_at: number;
	} | null;
	typical_load: number | null;
	processing: boolean;
}

export interface Activity extends ActivitySummary {
	description: string;
	source_format: 'gpx' | 'fit';
	has_original: boolean;
	metrics_version: number;
	zones: { seconds: number[]; bounds: number[]; uses_default_hr_max: boolean } | null;
	splits: Segment[];
	laps: Segment[];
}

export interface Streams {
	resolution: 'low' | 'full';
	count: number;
	t: number[];
	dist: number[];
	lat: (number | null)[];
	lon: (number | null)[];
	speed: number[];
	moving: boolean[];
	ele: number[] | null;
	hr: (number | null)[] | null;
	cad: (number | null)[] | null;
}

export interface ImportOutcome {
	file: string;
	status: 'imported' | 'duplicate' | 'failed';
	activity_id?: number;
	reason?: string;
}

export class ApiError extends Error {
	constructor(
		public status: number,
		public code: string,
		message: string,
		public body: Record<string, unknown> = {}
	) {
		super(message);
	}
}

const BASE = '/api/v1';

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
	const init: RequestInit = { method, headers: {} };
	const headers = init.headers as Record<string, string>;
	// The server refuses state-changing requests without this header (cross-site protection).
	if (method !== 'GET') headers['X-Requested-With'] = 'running-web';
	if (body instanceof FormData) init.body = body;
	else if (body !== undefined) {
		headers['Content-Type'] = 'application/json';
		init.body = JSON.stringify(body);
	}
	let res: Response;
	try {
		res = await fetch(BASE + path, init);
	} catch {
		throw new ApiError(0, 'network', 'Le serveur ne répond pas. Vérifiez votre connexion au réseau domestique.');
	}
	if (res.status === 204) return undefined as T;
	const data = await res.json().catch(() => ({}));
	if (!res.ok) throw new ApiError(res.status, data.error ?? 'error', data.message ?? `Erreur ${res.status}`, data);
	return data as T;
}

export interface ListParams {
	/** Household list only: restrict to one profile. */
	profile_id?: number | null;
	cursor?: string | null;
	limit?: number;
	tag?: string | null;
	from?: number | null;
	to?: number | null;
}

export interface ClientConfig {
	version: string;
	map_style_url: string;
	map_style_url_dark: string;
	max_upload_bytes: number;
}

const query = (p: object) => {
	const q = new URLSearchParams();
	for (const [k, v] of Object.entries(p)) if (v !== undefined && v !== null && v !== '') q.set(k, String(v));
	const qs = q.toString();
	return qs ? '?' + qs : '';
};

/** Minutes east of UTC in a time zone right now; the browser's zone when none is given or it is unknown. */
export function tzOffsetMinutes(timezone?: string | null): number {
	const local = -new Date().getTimezoneOffset();
	if (!timezone) return local;
	try {
		const now = new Date();
		const parts = new Intl.DateTimeFormat('en-US', {
			timeZone: timezone,
			hourCycle: 'h23',
			year: 'numeric',
			month: 'numeric',
			day: 'numeric',
			hour: 'numeric',
			minute: 'numeric'
		}).formatToParts(now);
		const n = (type: string) => Number(parts.find((p) => p.type === type)?.value);
		const there = Date.UTC(n('year'), n('month') - 1, n('day'), n('hour'), n('minute'));
		return Math.round((there - now.getTime()) / 60000);
	} catch {
		return local;
	}
}

export const api = {
	config: () => request<ClientConfig>('GET', '/config'),
	profiles: () => request<{ profiles: Profile[] }>('GET', '/profiles').then((r) => r.profiles),
	createProfile: (name: string) => request<Profile>('POST', '/profiles', { name }),
	updateProfile: (id: number, changes: Partial<Omit<Profile, 'id' | 'zones' | 'activity_count'>> & { zone_bounds?: number[] | null }) =>
		request<Profile>('PUT', `/profiles/${id}`, changes),
	deleteProfile: (id: number) => request<void>('DELETE', `/profiles/${id}`),
	tags: (profileId: number) => request<{ tags: string[] }>('GET', `/profiles/${profileId}/tags`).then((r) => r.tags),
	activities: (profileId: number, p: ListParams = {}) => {
		const q = new URLSearchParams();
		for (const [k, v] of Object.entries(p)) if (v !== undefined && v !== null && v !== '') q.set(k, String(v));
		const qs = q.toString();
		return request<{ items: ActivitySummary[]; next_cursor: string | null }>('GET', `/profiles/${profileId}/activities${qs ? '?' + qs : ''}`);
	},
	/** The whole household, or one profile of it; without a profile, a shared run comes once. */
	household: (p: ListParams = {}) => request<{ items: ActivitySummary[]; next_cursor: string | null }>('GET', `/activities${query(p)}`),
	calendar: (profileId: number, year: number, tz: number) =>
		request<Calendar>('GET', `/profiles/${profileId}/stats/calendar${query({ year, tz_offset_minutes: tz })}`),
	volume: (profileId: number, unit: 'week' | 'month', count: number, tz: number) =>
		request<{ unit: string; periods: VolumePeriod[] }>('GET', `/profiles/${profileId}/stats/volume${query({ unit, count, tz_offset_minutes: tz })}`).then(
			(r) => r.periods
		),
	progress: (profileId: number, months: number, tag: string | null, tz: number) =>
		request<{ months: ProgressMonth[] }>('GET', `/profiles/${profileId}/stats/progress${query({ months, tag, tz_offset_minutes: tz })}`).then(
			(r) => r.months
		),
	records: (profileId: number) => request<{ distances: RecordDistance[]; processing: boolean }>('GET', `/profiles/${profileId}/records`),
	predictions: (profileId: number) =>
		request<{ window_weeks: number; longest_recent_run: number | null; predictions: Prediction[]; processing: boolean }>(
			'GET',
			`/profiles/${profileId}/predictions`
		),
	load: (profileId: number, days: number, tz: number) => request<Load>('GET', `/profiles/${profileId}/load${query({ days, tz_offset_minutes: tz })}`),
	activity: (id: number) => request<Activity>('GET', `/activities/${id}`),
	streams: (id: number, resolution: 'low' | 'full' = 'low') => request<Streams>('GET', `/activities/${id}/streams?resolution=${resolution}`),
	updateJournal: (id: number, j: { title?: string; description?: string; rpe?: number | null; tags?: string[] }) =>
		request<Activity>('PATCH', `/activities/${id}`, j),
	deleteActivity: (id: number) => request<void>('DELETE', `/activities/${id}`),
	copyActivity: (id: number, profileId: number, keepHr: boolean) =>
		request<{ activity_id: number }>('POST', `/activities/${id}/copy`, { profile_id: profileId, keep_hr: keepHr }),
	moveActivity: (id: number, profileId: number) => request<{ activity_id: number }>('POST', `/activities/${id}/move`, { profile_id: profileId }),
	importFiles: (profileId: number, files: File[]) => {
		const form = new FormData();
		for (const f of files) form.append('files', f, f.name);
		// Minutes east of UTC, so untitled runs are named after the local time of day.
		const tz = -new Date().getTimezoneOffset();
		return request<{ results: ImportOutcome[] }>('POST', `/profiles/${profileId}/imports?tz_offset_minutes=${tz}`, form).then((r) => r.results);
	},
	exportUrl: (id: number) => `${BASE}/activities/${id}/export.gpx`,
	originalUrl: (id: number) => `${BASE}/activities/${id}/original`
};
