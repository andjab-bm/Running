// Shared state of the web app: household profiles, the active one, theme, toasts.
import { api, ApiError, type ClientConfig, type Profile } from './api';

export type Theme = 'light' | 'dark' | 'system';

const PROFILE_KEY = 'running-profile';
const THEME_KEY = 'running-theme';
const BASEMAP_KEY = 'running-basemap';

function read(key: string): string | null {
	try {
		return localStorage.getItem(key);
	} catch {
		return null;
	}
}

function write(key: string, value: string | null) {
	try {
		if (value === null) localStorage.removeItem(key);
		else localStorage.setItem(key, value);
	} catch {
		// Private browsing: the choice simply lasts for this visit.
	}
}

class AppState {
	profiles = $state<Profile[]>([]);
	activeId = $state<number | null>(null);
	/** False until the first load of profiles finished. */
	ready = $state(false);
	loadError = $state<string | null>(null);
	theme = $state<Theme>('system');
	/** Whether maps show streets, parks and water (tiles come from the configured provider). */
	basemap = $state(true);
	/** Whether the dark palette is in effect, whatever selected it. */
	dark = $state(false);
	config = $state<ClientConfig | null>(null);
	toast = $state<{ text: string; n: number } | null>(null);
	/** Bumped whenever activities change, so open lists reload. */
	revision = $state(0);

	get active(): Profile | null {
		return this.profiles.find((p) => p.id === this.activeId) ?? null;
	}

	async load() {
		const saved = read(THEME_KEY);
		this.theme = saved === 'light' || saved === 'dark' ? saved : 'system';
		this.basemap = read(BASEMAP_KEY) !== 'off';
		const media = window.matchMedia('(prefers-color-scheme: dark)');
		const sync = () => (this.dark = this.theme === 'dark' || (this.theme === 'system' && media.matches));
		media.addEventListener('change', sync);
		this.syncDark = sync;
		sync();
		api.config().then((c) => (this.config = c), () => {});
		try {
			this.profiles = await api.profiles();
			const remembered = Number(read(PROFILE_KEY));
			this.activeId = this.profiles.find((p) => p.id === remembered)?.id ?? this.profiles[0]?.id ?? null;
			this.loadError = null;
		} catch (e) {
			this.loadError = e instanceof ApiError ? e.message : 'Chargement impossible.';
		}
		this.ready = true;
	}

	async refreshProfiles() {
		this.profiles = await api.profiles();
		if (!this.active) this.setActive(this.profiles[0]?.id ?? null);
	}

	setActive(id: number | null) {
		this.activeId = id;
		write(PROFILE_KEY, id === null ? null : String(id));
	}

	/** Replaces one profile after an update, without another round trip. */
	patchProfile(p: Profile) {
		this.profiles = this.profiles.map((q) => (q.id === p.id ? p : q));
	}

	setTheme(theme: Theme) {
		this.theme = theme;
		write(THEME_KEY, theme === 'system' ? null : theme);
		const root = document.documentElement;
		if (theme === 'system') root.removeAttribute('data-theme');
		else root.setAttribute('data-theme', theme);
		this.syncDark();
	}

	private syncDark: () => void = () => {};

	/** MapLibre style URL for the current theme; empty when the basemap is off or not configured. */
	get mapStyle(): string {
		if (!this.basemap || !this.config) return '';
		return this.dark ? this.config.map_style_url_dark : this.config.map_style_url;
	}

	setBasemap(on: boolean) {
		this.basemap = on;
		write(BASEMAP_KEY, on ? null : 'off');
	}

	notify(text: string) {
		this.toast = { text, n: (this.toast?.n ?? 0) + 1 };
	}

	changed() {
		this.revision += 1;
	}
}

export const app = new AppState();

/** Message to show for any failed request. */
export function errorText(e: unknown): string {
	return e instanceof ApiError ? e.message : 'Une erreur inattendue est survenue.';
}
