import { describe, expect, it } from 'vitest';
import type { WorkoutItem } from './api';
import { endText, ordered, parseDistance, parseDuration, parsePace, paceText, problem, step, totals, unfold } from './workout';

const intervals = (skip: boolean): WorkoutItem[] => [
	{
		type: 'repeat',
		count: 6,
		skip_last_recovery: skip,
		steps: [step('effort', { type: 'distance', value: 400 }, { type: 'pace', fast: 300, slow: 315 }), step('recovery', { type: 'time', value: 90 })]
	}
];

describe('unfold', () => {
	it('gives twelve steps for six repeats, eleven without the last recovery', () => {
		expect(unfold(intervals(false))).toHaveLength(12);
		expect(unfold(intervals(true))).toHaveLength(11);
		expect(unfold(intervals(true)).at(-1)!.kind).toBe('effort');
	});
});

describe('totals', () => {
	it('shows a timed session exactly and its distance as unknown', () => {
		const t = totals([step('warmup', { type: 'time', value: 600 }), step('effort', { type: 'time', value: 1200 })], 'metric');
		expect(t.time).toBe('30:00');
		expect(t.distance).toBe('inconnue');
	});
	it('shows "at least" when a step ends on lap', () => {
		const t = totals([step('effort', { type: 'time', value: 600 }), step('recovery', { type: 'lap' })], 'metric');
		expect(t.time).toBe('au moins 10:00');
	});
	it('never invents a duration for a distance without pace', () => {
		expect(totals([step('effort', { type: 'distance', value: 5000 })], 'metric')).toMatchObject({ time: 'inconnue', distance: '5,0 km' });
	});
	it('estimates a distance step with a pace target', () => {
		const t = totals(intervals(false), 'metric');
		expect(t.time.startsWith('≈ ')).toBe(true);
		expect(t.distance).toBe('au moins 2,4 km');
	});
});

describe('entry', () => {
	it('reads durations', () => {
		expect(parseDuration('8:00')).toBe(480);
		expect(parseDuration('90')).toBe(90);
		expect(parseDuration('1:02:03')).toBe(3723);
		expect(parseDuration('5:75')).toBeNull();
		expect(parseDuration('abc')).toBeNull();
	});
	it('reads paces and distances in the profile units', () => {
		expect(parsePace('5:00', 'metric')).toBe(300);
		expect(parsePace('8:03', 'imperial')).toBeCloseTo(300, 0);
		expect(paceText(300, 'imperial')).toBe('8:03');
		expect(parseDistance('400', 'metric')).toBe(400);
		expect(parseDistance('0,25', 'imperial')).toBeCloseTo(402.3, 1);
		expect(endText({ type: 'distance', value: 1500 }, 'metric')).toBe('1,5 km');
		expect(endText({ type: 'distance', value: 400 }, 'metric')).toBe('400 m');
	});
	it('never keeps an inverted range', () => {
		expect(ordered({ type: 'pace', fast: 330, slow: 300 })).toEqual({ type: 'pace', fast: 300, slow: 330 });
	});
});

describe('problem', () => {
	it('points out a missing step and an invalid length', () => {
		expect(problem([])).toMatch(/au moins une étape/);
		expect(problem([step('effort', { type: 'distance', value: 20 })])).toMatch(/50 m/);
		expect(problem([step('effort', { type: 'time', value: 3 })])).toMatch(/5 secondes/);
		expect(problem(intervals(false))).toBeNull();
	});
});
