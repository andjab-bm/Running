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
