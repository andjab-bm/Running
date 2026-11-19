<script lang="ts">
	// One square per day of a year, weeks as columns starting on Monday, tinted by how much was run.
	import type { CalendarDay } from '#lib/api.ts';

	let {
		year,
		days,
		value,
		color,
		selected = $bindable(null),
		label
	}: {
		year: number;
		days: CalendarDay[];
		/** What a day weighs: distance, time or load. */
		value: (d: CalendarDay) => number;
		/** CSS colour of the fullest squares. */
		color: string;
		/** ISO date of the day shown in detail. */
		selected?: string | null;
		/** Accessible text of a day. */
		label: (date: Date, day: CalendarDay | undefined) => string;
	} = $props();

	const MONTHS = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];
	const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

	const byDate = $derived(new Map(days.map((d) => [d.date, d])));
	const max = $derived(Math.max(1e-9, ...days.map(value)));
	const today = iso(new Date());

	// Blank cells before 1 January so that the first column starts on a Monday.
	const lead = $derived((new Date(year, 0, 1).getDay() + 6) % 7);
	const cells = $derived.by(() => {
		const out: { date: Date; key: string; day?: CalendarDay; level: number; future: boolean }[] = [];
		for (let d = new Date(year, 0, 1); d.getFullYear() === year; d = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1)) {
			const key = iso(d);
			const day = byDate.get(key);
			// Four steps of intensity; any run at all reaches the first.
			const level = day ? Math.min(4, 1 + Math.floor((value(day) / max) * 3.999)) : 0;
			out.push({ date: d, key, day, level, future: key > today });
		}
		return out;
	});
	// A month name above the first column that holds one of its first seven days.
	const months = $derived.by(() => {
		const columns = Math.ceil((lead + cells.length) / 7);
		const out = new Array<string>(columns).fill('');
		for (const c of cells) if (c.date.getDate() === 1) out[Math.floor((lead + cells.indexOf(c)) / 7)] = MONTHS[c.date.getMonth()];
		return out;
	});
</script>

<div class="wrap" style:--hm={color}>
	<div class="months" aria-hidden="true">
		{#each months as m, i (i)}<span>{m}</span>{/each}
	</div>
	<div class="grid" role="group" aria-label="Calendrier {year}">
		{#each { length: lead } as _, i (i)}<span class="pad"></span>{/each}
		{#each cells as c (c.key)}
			<button
				type="button"
				class="l{c.level}"
				class:on={selected === c.key}
				disabled={c.future}
				aria-label={label(c.date, c.day)}
				aria-pressed={selected === c.key}
				onclick={() => (selected = c.key)}
				onmouseenter={() => (selected = c.key)}
			></button>
		{/each}
	</div>
</div>
<div class="legend" style:--hm={color} aria-hidden="true">
	moins <i class="l0"></i><i class="l1"></i><i class="l2"></i><i class="l3"></i><i class="l4"></i> plus
</div>

<style>
	.wrap {
		overflow-x: auto;
		padding-bottom: 4px;
	}
	.months,
	.grid {
		display: grid;
		grid-auto-flow: column;
		grid-auto-columns: 14px;
		gap: 4px;
		width: max-content;
	}
	.months {
		font-size: 11.5px;
		font-weight: 600;
		color: var(--ink-3);
		margin-bottom: 6px;
		height: 16px;
	}
	.months span {
		white-space: nowrap;
		overflow: visible;
	}
	.grid {
		grid-template-rows: repeat(7, 14px);
		/* Room for the hover scale and the focus ring of the outer squares. */
		padding: 3px;
		margin: -3px;
	}
	.grid button,
	.pad {
		width: 14px;
		height: 14px;
		border-radius: 4px;
	}
	.grid button {
		background: var(--surface-2);
		transition: transform 0.12s;
	}
	.grid button:disabled {
		opacity: 0.35;
		cursor: default;
	}
	.grid button:not(:disabled):hover,
	.grid button.on {
		transform: scale(1.3);
		outline: 2px solid var(--ink);
		outline-offset: 1px;
	}
	.l1 {
		background: color-mix(in srgb, var(--hm) 28%, var(--surface-2)) !important;
	}
	.l2 {
		background: color-mix(in srgb, var(--hm) 52%, var(--surface-2)) !important;
	}
	.l3 {
		background: color-mix(in srgb, var(--hm) 78%, var(--surface-2)) !important;
	}
	.l4 {
		background: var(--hm) !important;
	}
	.legend {
		display: flex;
		align-items: center;
		justify-content: flex-end;
		gap: 5px;
		font-size: 12px;
		color: var(--ink-3);
		margin-top: 10px;
	}
	.legend i {
		width: 13px;
		height: 13px;
		border-radius: 4px;
		display: block;
		background: var(--surface-2);
	}
</style>
