// Display formatting. The API speaks SI; everything shown to the athlete goes through here.
import type { Units } from './api';

const M_PER_MILE = 1609.344;
const FT_PER_M = 3.28084;
const LOCALE = 'fr-FR';

/** Length of one split in metres. */
export const unitLength = (units: Units) => (units === 'imperial' ? M_PER_MILE : 1000);
export const distanceUnit = (units: Units) => (units === 'imperial' ? 'mi' : 'km');
export const paceUnit = (units: Units) => (units === 'imperial' ? '/mi' : '/km');
export const elevationUnit = (units: Units) => (units === 'imperial' ? 'ft' : 'm');

const decimal = (n: number, digits: number) => n.toFixed(digits).replace('.', ',');

/** `10,21` for 10 210 m in metric. */
export function distance(metres: number, units: Units, digits = 2): string {
	return decimal(metres / unitLength(units), digits);
}

/** `1:02:03`, or `58:47` under an hour. */
export function duration(seconds: number): string {
	const s = Math.max(0, Math.round(seconds));
	const h = Math.floor(s / 3600);
	const m = Math.floor((s % 3600) / 60);
	const x = s % 60;
	return (h ? `${h}:${String(m).padStart(2, '0')}` : `${m}`) + ':' + String(x).padStart(2, '0');
}

/** Seconds per kilometre or mile for a speed in m/s; `null` when not moving. */
export function paceSeconds(speed: number | null | undefined, units: Units): number | null {
	return speed && speed > 0.2 ? unitLength(units) / speed : null;
}

/** `5:45` for 2.9 m/s in metric; `—` when unavailable. */
export function pace(speed: number | null | undefined, units: Units): string {
	const p = paceSeconds(speed, units);
	return p === null ? '—' : duration(p);
}

/** Whole metres or feet. */
export function elevation(metres: number | null | undefined, units: Units): string {
	if (metres === null || metres === undefined) return '—';
	return String(Math.round(units === 'imperial' ? metres * FT_PER_M : metres));
}

export function whole(n: number | null | undefined): string {
	return n === null || n === undefined ? '—' : String(Math.round(n));
}

function capitalise(s: string) {
	return s.charAt(0).toUpperCase() + s.slice(1);
}

/** Time zone to display in: the profile's when set, otherwise the browser's. */
const zone = (timezone: string | null | undefined) => (timezone ? { timeZone: timezone } : {});

function safeFormat(unix: number, options: Intl.DateTimeFormatOptions, timezone?: string | null): string {
	try {
		return new Date(unix * 1000).toLocaleString(LOCALE, { ...options, ...zone(timezone) });
	} catch {
		// An unknown time zone name must not break the page.
		return new Date(unix * 1000).toLocaleString(LOCALE, options);
	}
}

/** `Dimanche 8 novembre 2026` */
export function dateLong(unix: number, timezone?: string | null): string {
	return capitalise(safeFormat(unix, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }, timezone));
}

/** `Dim. 8 nov.` */
export function dateShort(unix: number, timezone?: string | null): string {
	return capitalise(safeFormat(unix, { weekday: 'short', day: 'numeric', month: 'short' }, timezone));
}

/** `08:42` */
export function clock(unix: number, timezone?: string | null): string {
	return safeFormat(unix, { hour: '2-digit', minute: '2-digit' }, timezone);
}

/** `novembre 2026`, used to group the activity list. */
export function monthKey(unix: number, timezone?: string | null): string {
	return safeFormat(unix, { month: 'long', year: 'numeric' }, timezone);
}

const MONTHS_SHORT = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];

/** `9 nov.` for the calendar date `2026-11-09`, and `nov.` for the month `2026-11`. */
export function isoDayShort(iso: string): string {
	const [, m, d] = iso.split('-').map(Number);
	return d ? `${d} ${MONTHS_SHORT[m - 1]}` : MONTHS_SHORT[m - 1];
}

/** `Lundi 9 novembre 2026` for the calendar date `2026-11-09`. */
export function isoDayLong(iso: string): string {
	const [y, m, d] = iso.split('-').map(Number);
	return capitalise(new Date(y, m - 1, d).toLocaleDateString(LOCALE, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }));
}

/** Name of a standard race distance given in metres. */
export function raceName(metres: number): string {
	if (metres === 21097.5) return 'Semi-marathon';
	if (metres === 42195) return 'Marathon';
	return metres < 1000 ? `${metres} m` : `${metres / 1000} km`;
}

/** `2 h 05` for a number of hours and minutes; `45 min` under an hour. */
export function hoursMinutes(seconds: number): string {
	const m = Math.round(seconds / 60);
	return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')}`;
}

export const RPE_WORDS = ['', 'très facile', 'facile', 'facile', 'modéré', 'modéré', 'soutenu', 'difficile', 'très difficile', 'extrême', 'maximal'];
export const ZONE_NAMES = ['Récupération', 'Endurance', 'Tempo', 'Seuil', 'VO₂ max'];

export function effortWord(effort: number): string {
	return effort < 90 ? 'léger' : effort < 170 ? 'modéré' : effort < 260 ? 'soutenu' : 'intense';
}

const TAG_COLORS: Record<string, string> = {
	'endurance fondamentale': 'mint',
	fractionné: 'peach',
	compétition: 'rose',
	tempo: 'lav',
	'sortie longue': 'sky'
};
const PASTELS = ['lav', 'mint', 'peach', 'sky', 'rose', 'butter'];

/** Pastel assigned to a tag: fixed for the well-known ones, butter otherwise. */
export const tagColor = (tag: string) => TAG_COLORS[tag.toLowerCase()] ?? 'butter';

/** Pastel of an activity card: from its first well-known tag, else stable per activity. */
export function activityColor(a: { id: number; tags: string[] }): string {
	for (const t of a.tags) if (TAG_COLORS[t.toLowerCase()]) return TAG_COLORS[t.toLowerCase()];
	return PASTELS[a.id % PASTELS.length];
}
