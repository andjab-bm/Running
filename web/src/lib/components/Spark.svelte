<script lang="ts">
	// A small line chart over periods. A period without a value leaves a gap, never a zero.
	let {
		values,
		labels,
		color,
		format,
		invert = false,
		title,
		width = 300,
		height = 96
	}: {
		values: (number | null)[];
		labels: string[];
		color: string;
		format: (v: number) => string;
		/** Draw smaller values higher (a faster pace is a smaller number). */
		invert?: boolean;
		title: string;
		width?: number;
		height?: number;
	} = $props();

	const known = $derived(values.filter((v): v is number => v !== null));
	const lo = $derived(Math.min(...known));
	const hi = $derived(Math.max(...known));
	const X = (i: number) => 6 + (i * (width - 12)) / Math.max(1, values.length - 1);
	const Y = (v: number) => {
		const r = hi > lo ? (v - lo) / (hi - lo) : 0.5;
		return 10 + (invert ? r : 1 - r) * (height - 28);
	};
	// One path per unbroken run of values.
	const path = $derived.by(() => {
		let d = '';
		let pen = false;
		values.forEach((v, i) => {
			if (v === null) pen = false;
			else {
				d += `${pen ? 'L' : 'M'}${X(i).toFixed(1)} ${Y(v).toFixed(1)}`;
				pen = true;
			}
		});
		return d;
	});
</script>

{#if known.length === 0}
	<p class="none">Pas encore de données.</p>
{:else}
	<svg viewBox="0 0 {width} {height}" role="img" aria-label="{title} : {values.map((v, i) => `${labels[i]} ${v === null ? 'pas de donnée' : format(v)}`).join(', ')}">
		<path d={path} fill="none" stroke={color} stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" />
		{#each values as v, i (i)}
			{#if v === null}
				<text x={X(i)} y={height / 2} text-anchor="middle">—</text>
			{:else}
				<circle cx={X(i)} cy={Y(v)} r="3.2" fill="var(--surface-2)" stroke={color} stroke-width="2"><title>{labels[i]} : {format(v)}</title></circle>
			{/if}
		{/each}
		<text x="6" y={height - 1}>{labels[0]}</text>
		<text x={width - 6} y={height - 1} text-anchor="end">{labels[labels.length - 1]}</text>
	</svg>
{/if}

<style>
	svg {
		display: block;
		width: 100%;
		height: auto;
		overflow: visible;
	}
	text {
		font: 600 11.5px var(--font-body);
		fill: var(--ink-3);
		font-variant-numeric: tabular-nums;
	}
	.none {
		margin: 0;
		color: var(--ink-3);
		font-size: 13px;
		padding: 24px 0;
	}
</style>
