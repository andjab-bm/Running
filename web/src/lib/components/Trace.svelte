<script lang="ts">
	// Small drawing of a GPS trace for list cards, without any map background.
	let { points, color }: { points: [number, number][]; color: string } = $props();

	const W = 200;
	const H = 96;
	const PAD = 14;

	const d = $derived.by(() => {
		if (points.length < 2) return '';
		const lat0 = (points[0][0] * Math.PI) / 180;
		// Equirectangular projection is plenty at the scale of a run.
		const xy = points.map(([lat, lon]) => [lon * Math.cos(lat0), -lat]);
		const xs = xy.map((p) => p[0]);
		const ys = xy.map((p) => p[1]);
		const [x0, x1, y0, y1] = [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)];
		const scale = Math.min((W - 2 * PAD) / (x1 - x0 || 1e-9), (H - 2 * PAD) / (y1 - y0 || 1e-9));
		const ox = (W - (x1 - x0) * scale) / 2;
		const oy = (H - (y1 - y0) * scale) / 2;
		return xy.map((p, i) => `${i ? 'L' : 'M'}${(ox + (p[0] - x0) * scale).toFixed(1)},${(oy + (p[1] - y0) * scale).toFixed(1)}`).join('');
	});
</script>

<svg viewBox="0 0 {W} {H}" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
	<path {d} style:stroke="var(--i-{color})" />
</svg>

<style>
	svg {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
	}
	path {
		fill: none;
		stroke-width: 3;
		stroke-linecap: round;
		stroke-linejoin: round;
		transition: stroke-width 0.2s;
	}
	:global(.act:hover) path {
		stroke-width: 4;
	}
</style>
