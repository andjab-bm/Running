import { describe, expect, it } from 'vitest';
import { activityColor, clock, dateLong, distance, duration, elevation, pace, paceSeconds, tagColor, unitLength, whole } from './format';

describe('distance', () => {
	it('shows kilometres with a decimal comma', () => {
		expect(distance(10210, 'metric')).toBe('10,21');
		expect(distance(42195, 'metric', 1)).toBe('42,2');
	});
	it('converts to miles', () => {
		expect(distance(1609.344, 'imperial')).toBe('1,00');
		expect(distance(10000, 'imperial')).toBe('6,21');
	});
});

describe('duration', () => {
	it('omits hours under one hour', () => {
		expect(duration(3527)).toBe('58:47');
		expect(duration(59.6)).toBe('1:00');
		expect(duration(5)).toBe('0:05');
	});
	it('pads minutes once hours appear', () => {
		expect(duration(3723)).toBe('1:02:03');
		expect(duration(36000)).toBe('10:00:00');
	});
});

describe('pace', () => {
	it('is minutes per kilometre in metric', () => {
		expect(pace(1000 / 345, 'metric')).toBe('5:45');
		expect(paceSeconds(2.5, 'metric')).toBe(400);
	});
	it('is minutes per mile in imperial', () => {
		expect(pace(1609.344 / 480, 'imperial')).toBe('8:00');
		expect(unitLength('imperial')).toBe(1609.344);
	});
	it('is unavailable when not moving', () => {
		expect(pace(0, 'metric')).toBe('—');
		expect(pace(null, 'metric')).toBe('—');
		expect(paceSeconds(0.1, 'metric')).toBeNull();
	});
});

describe('elevation', () => {
	it('rounds metres and converts to feet', () => {
		expect(elevation(42.4, 'metric')).toBe('42');
		expect(elevation(100, 'imperial')).toBe('328');
	});
	it('never shows a missing value as zero', () => {
		expect(elevation(null, 'metric')).toBe('—');
		expect(whole(undefined)).toBe('—');
		expect(whole(153.6)).toBe('154');
	});
});

describe('dates', () => {
	// 2026-11-08 20:30 UTC
	const t = 1794169800;
	it('uses the profile time zone', () => {
		expect(clock(t, 'Europe/Paris')).toBe('21:30');
		expect(dateLong(t, 'Europe/Paris')).toBe('Dimanche 8 novembre 2026');
		expect(dateLong(t, 'Asia/Tokyo')).toBe('Lundi 9 novembre 2026');
	});
	it('survives an unknown time zone name', () => {
		expect(clock(t, 'Nowhere/Land')).toMatch(/^\d{2}:\d{2}$/);
	});
});

describe('colours', () => {
	it('gives well-known tags a fixed pastel', () => {
		expect(tagColor('Fractionné')).toBe('peach');
		expect(tagColor('Trail')).toBe('butter');
		expect(activityColor({ id: 3, tags: ['Trail', 'Compétition'] })).toBe('rose');
		expect(activityColor({ id: 7, tags: [] })).toBe(activityColor({ id: 7, tags: ['Trail'] }));
	});
});
