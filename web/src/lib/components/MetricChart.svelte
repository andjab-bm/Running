<script lang="ts">
	// One measure over distance, with per-unit average blocks, a relief silhouette,
	// the average marked, and a cursor shared with the map.
	import 'uplot/dist/uPlot.min.css';
	import { untrack } from 'svelte';
	import uPlot from 'uplot';

	export interface Block {
		x0: number;
		x1: number;
		value: number;
		color: string;
	}
	export interface TipRow {
		color: string;
		label: string;
		value: string;
	}
	interface Props {
		x: number[];
		y: (number | null)[];
		/** Faster is higher: flips the vertical axis (pace). */
		inverted: boolean;
		color: string;
		format: (v: number) => string;
		unit: string;
		xUnit: string;
		blocks: Block[] | null;
		relief: number[] | null;
		average: number | null;
		/** Colours read from the theme. */
		ink: string;
		grid: string;
		surface: string;
		cursor: number | null;
		sel: { i0: number; i1: number } | null;
		onhover: (index: number | null) => void;
		tip: (index: number) => { title: string; rows: TipRow[] };
		label: string;
	}
	let { x, y, inverted, color, format, unit, xUnit, blocks, relief, average, ink, grid, surface, cursor, sel, onhover, tip, label }: Props = $props();

	let host = $state<HTMLDivElement | null>(null);
	let plot: uPlot | null = null;
	let built = $state(0);
	let tipAt = $state<{ left: number; flip: boolean } | null>(null);
	let band = $state<{ left: number; width: number } | null>(null);
	// True while the pointer is over this chart, so external cursor updates are not echoed back.
	let inside = false;

	function percentile(sorted: number[], p: number) {
		return sorted[Math.min(sorted.length - 1, Math.max(0, Math.round(p * (sorted.length - 1))))];
	}

	/** Vertical range that ignores the odd outlier, so the curve fills the chart. */
	const range = $derived.by(() => {
		const values = (y.filter((v) => v !== null) as number[]).slice().sort((a, b) => a - b);
		if (!values.length) return [0, 1] as [number, number];
		let lo = percentile(values, 0.02);
		let hi = percentile(values, 0.98);
		for (const b of blocks ?? []) {
			lo = Math.min(lo, b.value);
			hi = Math.max(hi, b.value);
		}
		const pad = (hi - lo) * 0.14 || 1;
		return [lo - pad, hi + pad] as [number, number];
	});

	function withAlpha(c: string, a: number) {
		// Theme colours are six-digit hex values; anything else is used as is.
		const m = /^#([0-9a-f]{6})$/i.exec(c.trim());
		if (!m) return c;
		const n = parseInt(m[1], 16);
		return `rgba(${n >> 16},${(n >> 8) & 255},${n & 255},${a})`;
	}

	$effect(() => {
		if (!host || x.length < 2) return;
		const [lo, hi] = range;
		const font = '600 11.5px "Manrope Variable", system-ui, sans-serif';
		const showBlocks = blocks !== null && blocks.length > 0;
		const data: uPlot.AlignedData = [x, y];

		const paintUnder = (u: uPlot) => {
			const ctx = u.ctx;
			const { left, top, width, height } = u.bbox;
			const bottom = top + height;
			ctx.save();
			ctx.beginPath();
			ctx.rect(left, top, width, height);
			ctx.clip();
			if (relief) {
				// Faint elevation shape along the bottom fifth.
				const e0 = Math.min(...relief);
				const e1 = Math.max(...relief);
				ctx.beginPath();
				ctx.moveTo(u.valToPos(x[0], 'x', true), bottom);
				for (let i = 0; i < x.length; i++) ctx.lineTo(u.valToPos(x[i], 'x', true), bottom - height * 0.03 - ((relief[i] - e0) / (e1 - e0 || 1)) * height * 0.2);
				ctx.lineTo(u.valToPos(x[x.length - 1], 'x', true), bottom);
				ctx.closePath();
				ctx.fillStyle = withAlpha(ink, 0.09);
				ctx.fill();
			}
			if (showBlocks) {
				const gap = 2 * uPlot.pxRatio;
				for (const b of blocks!) {
					const x0 = u.valToPos(b.x0, 'x', true) + gap;
					const w = Math.max(gap, u.valToPos(b.x1, 'x', true) - gap - x0);
					const yy = Math.max(top, Math.min(bottom, u.valToPos(b.value, 'y', true)));
					ctx.fillStyle = withAlpha(b.color, 0.22);
					ctx.beginPath();
					ctx.roundRect(x0, yy, w, bottom - yy, [Math.min(8 * uPlot.pxRatio, w / 2), Math.min(8 * uPlot.pxRatio, w / 2), 0, 0]);
					ctx.fill();
				}
			}
			if (average !== null && average > lo && average < hi) {
				const ya = u.valToPos(average, 'y', true);
				ctx.strokeStyle = ink;
				ctx.lineWidth = 1.5 * uPlot.pxRatio;
				ctx.setLineDash([2 * uPlot.pxRatio, 6 * uPlot.pxRatio]);
				ctx.lineCap = 'round';
				ctx.beginPath();
				ctx.moveTo(left, ya);
				ctx.lineTo(left + width, ya);
				ctx.stroke();
				ctx.setLineDash([]);
				ctx.fillStyle = ink;
				ctx.font = `600 ${11.5 * uPlot.pxRatio}px "Manrope Variable", system-ui, sans-serif`;
				ctx.textAlign = 'right';
				ctx.fillText(`moy. ${format(average)}`, left + width - 4 * uPlot.pxRatio, ya - 6 * uPlot.pxRatio);
			}
			ctx.restore();
		};

		const opts: uPlot.Options = {
			width: host.clientWidth,
			height: host.clientHeight,
			legend: { show: false },
			padding: [14, 8, 0, 0],
			cursor: { y: false, drag: { x: false, y: false }, points: { size: 11, width: 3, stroke: surface, fill: color } },
			scales: { x: { time: false, range: [x[0], x[x.length - 1]] }, y: { dir: inverted ? -1 : 1, range: [lo, hi] } },
			axes: [
				{ stroke: ink, font, grid: { show: false }, ticks: { show: false }, gap: 6, values: (_u, ticks) => ticks.map((v, i) => (i === 0 ? `${v} ${xUnit}` : String(v))) },
				{ stroke: ink, font, size: 52, gap: 8, ticks: { show: false }, grid: { stroke: grid, width: 1.5 }, values: (_u, ticks) => ticks.map(format) }
			],
			series: [
				{},
				{
					stroke: color,
					width: 2.6,
					points: { show: false },
					spanGaps: true,
					// With per-unit blocks behind it, the curve is drawn without an area.
					fill: showBlocks
						? undefined
						: (u) => {
								const g = u.ctx.createLinearGradient(0, u.bbox.top, 0, u.bbox.top + u.bbox.height);
								g.addColorStop(inverted ? 1 : 0, withAlpha(color, 0.3));
								g.addColorStop(inverted ? 0 : 1, withAlpha(color, 0));
								return g;
							},
					fillTo: inverted ? hi : lo
				}
			],
			hooks: {
				drawAxes: [paintUnder],
				setCursor: [
					(u) => {
						if (!inside) return;
						const idx = u.cursor.idx;
						onhover(idx === null || idx === undefined || (u.cursor.left ?? -1) < 0 ? null : idx);
					}
				]
			}
		};
		const u = new uPlot(opts, data, host);
		plot = u;
		built = untrack(() => built) + 1;

		const over = u.over;
		const enter = () => (inside = true);
		const leave = () => {
			inside = false;
			onhover(null);
		};
		over.addEventListener('pointerenter', enter);
		over.addEventListener('pointerleave', leave);
		// Let a finger scrub the chart without scrolling the page sideways.
		over.style.touchAction = 'pan-y';
		const touch = (e: TouchEvent) => {
			const r = over.getBoundingClientRect();
			inside = true;
			u.setCursor({ left: e.touches[0].clientX - r.left, top: e.touches[0].clientY - r.top });
		};
		over.addEventListener('touchstart', touch, { passive: true });
		over.addEventListener('touchmove', touch, { passive: true });
		over.addEventListener('touchend', leave);

		const ro = new ResizeObserver(() => {
			if (host && host.clientWidth > 0) {
				u.setSize({ width: host.clientWidth, height: host.clientHeight });
				built += 1;
			}
		});
		ro.observe(host);

		return () => {
			ro.disconnect();
			u.destroy();
			plot = null;
		};
	});

	// Follow the shared cursor: marker when it comes from the map, tooltip in both cases.
	$effect(() => {
		void built;
		const u = plot;
		if (!u) return;
		if (cursor === null || cursor >= x.length) {
			if (!inside) u.setCursor({ left: -10, top: -10 }, false);
			tipAt = null;
			return;
		}
		const left = u.valToPos(x[cursor], 'x');
		const v = y[cursor];
		if (!inside) u.setCursor({ left, top: v === null ? 0 : u.valToPos(v, 'y') }, false);
		const full = u.over.clientWidth;
		tipAt = { left: left + (u.over.offsetLeft || 0), flip: left > full * 0.58 };
	});

	$effect(() => {
		void built;
		const u = plot;
		if (!u || !sel) {
			band = null;
			return;
		}
		const a = u.valToPos(x[sel.i0], 'x') + u.over.offsetLeft;
		const b = u.valToPos(x[Math.min(sel.i1, x.length - 1)], 'x') + u.over.offsetLeft;
		band = { left: a, width: Math.max(2, b - a) };
	});

	const tipData = $derived(cursor !== null && tipAt ? tip(cursor) : null);
</script>

<div class="wrap">
	<div class="unit">{unit}</div>
	<div class="host" bind:this={host} role="img" aria-label="{label} en fonction de la distance"></div>
	{#if band}<div class="band" style:left="{band.left}px" style:width="{band.width}px"></div>{/if}
	{#if tipData && tipAt}
		<div class="tip" style:left={tipAt.flip ? 'auto' : `${tipAt.left + 14}px`} style:right={tipAt.flip ? `calc(100% - ${tipAt.left - 14}px)` : 'auto'}>
			<h4>{tipData.title}</h4>
			{#each tipData.rows as r (r.label)}
				<div><span><i style:background={r.color}></i>{r.label}</span><b>{r.value}</b></div>
			{/each}
		</div>
	{/if}
</div>

<style>
	.wrap {
		position: relative;
	}
	.host {
		width: 100%;
		height: 270px;
	}
	.host :global(.u-over) {
		cursor: crosshair;
	}
	.host :global(.u-cursor-x) {
		border-right: 1.5px solid var(--ink-3);
	}
	.unit {
		position: absolute;
		left: 0;
		top: -4px;
		font-size: 11.5px;
		font-weight: 600;
		color: var(--ink-3);
		width: 44px;
		text-align: right;
	}
	.band {
		position: absolute;
		top: 14px;
		bottom: 26px;
		border-radius: 10px;
		background: var(--accent);
		opacity: 0.14;
		pointer-events: none;
	}
	.tip {
		position: absolute;
		top: 8px;
		z-index: 30;
		pointer-events: none;
		background: var(--ink);
		color: var(--bg);
		border-radius: 14px;
		padding: 10px 12px;
		font-size: 12.5px;
		min-width: 160px;
		box-shadow: var(--pop);
	}
	.tip h4 {
		margin: 0 0 6px;
		font: 600 15px var(--font-display);
	}
	.tip div {
		display: flex;
		justify-content: space-between;
		gap: 14px;
		align-items: center;
	}
	.tip b {
		font: 600 14px var(--font-display);
		font-variant-numeric: tabular-nums;
	}
	.tip i {
		display: inline-block;
		width: 9px;
		height: 9px;
		border-radius: 50%;
		margin-right: 5px;
	}
	@media (max-width: 900px) {
		.host {
			height: 230px;
		}
	}
</style>
