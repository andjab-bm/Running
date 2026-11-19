// Structured sessions on the web: unfolding, totals, labels and parsing, mirroring
// `server/src/pipeline/workout.rs`. Paces are stored in seconds per kilometre and
// shown in the profile's units; distances are stored in metres.
import type { Repeat, Step, StepEnd, StepKind, StepTarget, UnfoldedStep, Units, WorkoutItem } from './api';
import * as f from './format.ts';

export const KINDS: Record<StepKind, string> = {
	warmup: 'Échauffement',
	effort: 'Effort',
	recovery: 'Récupération',
	cooldown: 'Retour au calme'
};

export const LIMITS = { minTime: 5, maxTime: 6 * 3600, minDist: 50, maxDist: 100_000, minRepeat: 2, maxRepeat: 50, minPace: 120, maxPace: 1200, maxSteps: 500 };

/** Seconds per kilometre assumed for a step with no pace target when drawing the outline only. */
const OUTLINE_PACE = 360;
/** Width given to an open-ended step in the outline. */
const OUTLINE_LAP_S = 90;

export const step = (kind: StepKind, end: StepEnd, target: StepTarget = { type: 'none' }): Step => ({ type: 'step', kind, end, target });

/** The steps in the order they are run, repeats expanded (same rule as the server). */
export function unfold(items: WorkoutItem[]): UnfoldedStep[] {
	const out: UnfoldedStep[] = [];
	for (const it of items) {
		if (it.type === 'step') out.push({ kind: it.kind, end: it.end, target: it.target });
		else
			for (let i = 0; i < it.count; i++)
				it.steps.forEach((s, j) => {
					const last = i === it.count - 1 && j === it.steps.length - 1;
					if (!(last && it.skip_last_recovery && s.kind === 'recovery')) out.push({ kind: s.kind, end: s.end, target: s.target });
				});
	}
	return out;
}

const midPace = (t: StepTarget) => (t.type === 'pace' ? (t.fast + t.slow) / 2 : null);

/** Relative length of a step in the outline: never shown as a figure. */
export function outlineWeight(s: UnfoldedStep): number {
	if (s.end.type === 'time') return s.end.value;
	if (s.end.type === 'distance') return (s.end.value / 1000) * (midPace(s.target) ?? OUTLINE_PACE);
	return OUTLINE_LAP_S;
}

export interface Totals {
	/** Text for the duration: exact, "au moins …", "≈ …" or "inconnue". */
	time: string;
	distance: string;
	steps: number;
}

/**
 * Totals that are never invented: a duration is exact when every step is timed, a minimum when some
 * end on lap, an estimate when distances can be converted with their pace target, unknown otherwise.
 */
export function totals(items: WorkoutItem[], units: Units): Totals {
	const u = unfold(items);
	const open = u.some((s) => s.end.type === 'lap');
	const timed = u.filter((s) => s.end.type === 'time').reduce((a, s) => a + (s.end as { value: number }).value, 0);
	const metres = u.filter((s) => s.end.type === 'distance').reduce((a, s) => a + (s.end as { value: number }).value, 0);
	const byDistance = u.filter((s) => s.end.type === 'distance');
	let time: string;
	if (!byDistance.length) time = open ? `au moins ${f.duration(timed)}` : f.duration(timed);
	else if (byDistance.some((s) => s.target.type !== 'pace')) time = 'inconnue';
	else {
		const est = timed + byDistance.reduce((a, s) => a + ((s.end as { value: number }).value / 1000) * midPace(s.target)!, 0);
		time = `${open ? 'au moins ' : '≈ '}${f.duration(est)}`;
	}
	const allDistance = u.length > 0 && byDistance.length === u.length;
	const km = `${f.distance(metres, units, 1)} ${f.distanceUnit(units)}`;
	const distance = allDistance ? km : metres ? `au moins ${km}` : 'inconnue';
	return { time, distance, steps: u.length };
}

/** `400 m`, `1,5 km`, `0,25 mi`, `8:00` or `jusqu'à Tour`. */
export function endText(end: StepEnd, units: Units): string {
	if (end.type === 'time') return f.duration(end.value);
	if (end.type === 'lap') return "jusqu'à Tour";
	if (units === 'imperial') return `${trim(end.value / 1609.344, 2)} mi`;
	return end.value >= 1000 ? `${trim(end.value / 1000, 2)} km` : `${Math.round(end.value)} m`;
}

const trim = (n: number, digits: number) => n.toFixed(digits).replace(/\.?0+$/, '').replace('.', ',');

/** A pace stored per kilometre, shown in the profile's unit: `5:00`. */
export function paceText(secondsPerKm: number, units: Units): string {
	return f.duration(units === 'imperial' ? secondsPerKm * 1.609344 : secondsPerKm);
}

export function targetText(t: StepTarget, units: Units, bounds?: number[]): string {
	if (t.type === 'pace') return `${paceText(t.fast, units)}–${paceText(t.slow, units)} ${f.paceUnit(units)}`;
	if (t.type === 'hr_zone') return bounds ? `Zone ${t.zone} · ${bounds[t.zone - 1]}–${bounds[t.zone]} bpm` : `Zone ${t.zone}`;
	return '';
}

/** `8:00`, `8'`, `90` (seconds) or `1:02:03` into seconds; null when unreadable. */
export function parseDuration(text: string): number | null {
	const t = text.trim().replace(/['’]/g, ':').replace(/:$/, ':0');
	if (!/^\d+(:\d{1,2}){0,2}$/.test(t)) return null;
	const parts = t.split(':').map(Number);
	if (parts.length === 1) return parts[0];
	if (parts.slice(1).some((p) => p > 59)) return null;
	return parts.reduce((a, p) => a * 60 + p, 0);
}

/** A pace typed in the profile's unit (`5:00`) into seconds per kilometre. */
export function parsePace(text: string, units: Units): number | null {
	const t = text.trim();
	if (!/^\d{1,2}:\d{2}$/.test(t)) return null;
	const s = parseDuration(t);
	if (s === null) return null;
	return units === 'imperial' ? s / 1.609344 : s;
}

/** A distance typed in metres (metric) or miles (imperial) into metres. */
export function parseDistance(text: string, units: Units): number | null {
	const n = Number(text.trim().replace(',', '.'));
	if (!Number.isFinite(n) || n <= 0) return null;
	return units === 'imperial' ? n * 1609.344 : n;
}

export function distanceInput(metres: number, units: Units): string {
	return units === 'imperial' ? trim(metres / 1609.344, 3) : String(Math.round(metres));
}

/** What would make the server refuse the session, in French; null when it is valid. */
export function problem(items: WorkoutItem[]): string | null {
	if (!items.length) return 'Ajoutez au moins une étape pour pouvoir enregistrer la séance.';
	const check = (s: Step): string | null => {
		if (s.end.type === 'time' && !(s.end.value >= LIMITS.minTime && s.end.value <= LIMITS.maxTime)) return 'Une étape dure entre 5 secondes et 6 heures.';
		if (s.end.type === 'distance' && !(s.end.value >= LIMITS.minDist && s.end.value <= LIMITS.maxDist)) return 'Une étape couvre entre 50 m et 100 km.';
		if (s.target.type === 'pace') {
			const { fast, slow } = s.target;
			if (![fast, slow].every((p) => p >= LIMITS.minPace && p <= LIMITS.maxPace)) return "Une allure cible est comprise entre 2:00 et 20:00 au kilomètre.";
			if (fast >= slow) return "Les deux bornes de l'allure cible doivent être différentes.";
		}
		return null;
	};
	for (const it of items) {
		const p = it.type === 'step' ? check(it) : it.steps.length ? it.steps.map(check).find(Boolean) : 'Un bloc répété contient au moins une étape.';
		if (p) return p;
		if (it.type === 'repeat' && !(it.count >= LIMITS.minRepeat && it.count <= LIMITS.maxRepeat)) return 'Un bloc se répète entre 2 et 50 fois.';
	}
	if (unfold(items).length > LIMITS.maxSteps) return `Une séance se déroule en ${LIMITS.maxSteps} étapes au plus.`;
	return null;
}

/** The two bounds in order, so a range is never saved inverted. */
export function ordered(t: StepTarget): StepTarget {
	return t.type === 'pace' && t.fast > t.slow ? { type: 'pace', fast: t.slow, slow: t.fast } : t;
}

const repeat = (count: number, skip: boolean, steps: Step[]): Repeat => ({ type: 'repeat', count, skip_last_recovery: skip, steps });

/** Offered to a profile with an empty library; added on request, then edited like any other. */
export const STARTERS: { name: string; note: string; steps: WorkoutItem[] }[] = [
	{ name: 'Footing facile 30 min', note: 'À une allure où vous pouvez parler.', steps: [step('effort', { type: 'time', value: 1800 }, { type: 'hr_zone', zone: 2 })] },
	{
		name: 'Marche-course 8 × (2 min / 1 min)',
		note: 'Courir 2 minutes, marcher 1 minute.',
		steps: [
			step('warmup', { type: 'time', value: 300 }),
			repeat(8, false, [step('effort', { type: 'time', value: 120 }), step('recovery', { type: 'time', value: 60 })]),
			step('cooldown', { type: 'time', value: 300 })
		]
	},
	{
		name: 'Premier fractionné 5 × 1 min',
		note: 'Un peu plus vite que votre allure habituelle pendant les efforts.',
		steps: [
			step('warmup', { type: 'time', value: 600 }),
			repeat(5, true, [step('effort', { type: 'time', value: 60 }), step('recovery', { type: 'time', value: 120 })]),
			step('cooldown', { type: 'time', value: 600 })
		]
	}
];
