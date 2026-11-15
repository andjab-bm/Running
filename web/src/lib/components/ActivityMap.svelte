<script lang="ts">
	// Interactive map of one run: gradient-coloured trace, start/finish markers,
	// a cursor marker driven by the chart, and a highlight for the hovered split.
	import 'maplibre-gl/dist/maplibre-gl.css';
	import type { GeoJSONSource, Map as MlMap, Marker } from 'maplibre-gl';
	// MapLibre runs tile work in a web worker shipped as a separate file; let the bundler emit it.
	import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';

	interface Props {
		lat: (number | null)[];
		lon: (number | null)[];
		/** One concrete CSS colour per stream index. */
		colors: string[];
		/** MapLibre style URL, or '' for a plain background. */
		styleUrl: string;
		land: string;
		casing: string;
		highlight: string;
		cursor: number | null;
		sel: { i0: number; i1: number } | null;
		onhover: (index: number | null) => void;
	}
	let { lat, lon, colors, styleUrl, land, casing, highlight, cursor, sel, onhover }: Props = $props();

	const MAX_STOPS = 240;
	const HOVER_PX = 28;

	let el = $state<HTMLDivElement | null>(null);
	let map: MlMap | null = null;
	let cursorMarker: Marker | null = null;
	let ready = $state(false);

	// Points with a position, each remembering its index in the streams.
	const pts = $derived.by(() => {
		const out: { i: number; lng: number; lat: number }[] = [];
		for (let i = 0; i < lat.length; i++) {
			const la = lat[i];
			const lo = lon[i];
			if (la !== null && lo !== null) out.push({ i, lng: lo, lat: la });
		}
		return out;
	});

	const line = (from = 0, to = pts.length - 1) => ({
		type: 'Feature' as const,
		properties: {},
		geometry: { type: 'LineString' as const, coordinates: pts.slice(from, to + 1).map((p) => [p.lng, p.lat]) }
	});

	/** `line-gradient` stops: colour as a function of progress along the line's length. */
	function gradient(): unknown[] {
		const k = Math.cos(((pts[0]?.lat ?? 0) * Math.PI) / 180);
		const cum = [0];
		for (let j = 1; j < pts.length; j++) cum.push(cum[j - 1] + Math.hypot((pts[j].lng - pts[j - 1].lng) * k, pts[j].lat - pts[j - 1].lat));
		const total = cum[cum.length - 1] || 1;
		const step = Math.max(1, Math.ceil(pts.length / MAX_STOPS));
		const stops: unknown[] = [];
		let last = -1;
		for (let j = 0; j < pts.length; j += step) {
			const p = cum[j] / total;
			// Stops must strictly increase.
			if (p > last) {
				stops.push(p, colors[pts[j].i] ?? highlight);
				last = p;
			}
		}
		if (stops.length < 4) return ['interpolate', ['linear'], ['line-progress'], 0, highlight, 1, highlight];
		return ['interpolate', ['linear'], ['line-progress'], ...stops];
	}

	function dot(color: string, ring: string, size: number) {
		const d = document.createElement('div');
		d.style.cssText = `width:${size}px;height:${size}px;border-radius:50%;background:${color};border:3.5px solid ${ring};box-sizing:border-box;pointer-events:none`;
		return d;
	}

	// (Re)create the map when the basemap or its palette changes.
	$effect(() => {
		if (!el || pts.length < 2) return;
		const style = styleUrl || { version: 8 as const, sources: {}, layers: [{ id: 'bg', type: 'background' as const, paint: { 'background-color': land } }] };
		const [ring, accent] = [casing, highlight];
		let dead = false;
		let resizer: ResizeObserver | null = null;
		ready = false;

		import('maplibre-gl').then((mod) => {
			if (dead || !el) return;
			// The package exposes its API as named exports, or under `default` depending on the bundler.
			const ml = ('Map' in mod ? mod : (mod as unknown as { default: typeof mod }).default) as typeof import('maplibre-gl');
			ml.setWorkerUrl(workerUrl);
			const bounds = pts.reduce((b, p) => b.extend([p.lng, p.lat]), new ml.LngLatBounds([pts[0].lng, pts[0].lat], [pts[0].lng, pts[0].lat]));
			const m = new ml.Map({ container: el, style, bounds, fitBoundsOptions: { padding: 56 }, attributionControl: { compact: true }, cooperativeGestures: true });
			map = m;
			// Keep the canvas in step with its container (window resize, layout changes).
			resizer = new ResizeObserver(() => m.resize());
			resizer.observe(el);

			m.on('load', () => {
				if (dead) return;
				m.addSource('trace', { type: 'geojson', lineMetrics: true, data: line() });
				m.addSource('sel', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
				const round = { 'line-cap': 'round' as const, 'line-join': 'round' as const };
				m.addLayer({ id: 'trace-casing', type: 'line', source: 'trace', layout: round, paint: { 'line-color': ring, 'line-width': 9 } });
				m.addLayer({ id: 'trace', type: 'line', source: 'trace', layout: round, paint: { 'line-width': 5, 'line-gradient': gradient() as never } });
				m.addLayer({ id: 'sel-casing', type: 'line', source: 'sel', layout: round, paint: { 'line-color': accent, 'line-width': 11 } });
				m.addLayer({ id: 'sel', type: 'line', source: 'sel', layout: round, paint: { 'line-color': ring, 'line-width': 3.5 } });
				new ml.Marker({ element: dot('#35AF80', ring, 18) }).setLngLat([pts[0].lng, pts[0].lat]).addTo(m);
				const end = pts[pts.length - 1];
				new ml.Marker({ element: dot(accent, ring, 18) }).setLngLat([end.lng, end.lat]).addTo(m);
				cursorMarker = new ml.Marker({ element: dot(accent, ring, 20) }).setLngLat([pts[0].lng, pts[0].lat]);
				ready = true;
			});

			m.on('mousemove', (e) => {
				let best = -1;
				let bestD = HOVER_PX * HOVER_PX;
				for (const p of pts) {
					const q = m.project([p.lng, p.lat]);
					const d = (q.x - e.point.x) ** 2 + (q.y - e.point.y) ** 2;
					if (d < bestD) {
						bestD = d;
						best = p.i;
					}
				}
				onhover(best < 0 ? null : best);
			});
			m.on('mouseout', () => onhover(null));
		});

		return () => {
			dead = true;
			resizer?.disconnect();
			ready = false;
			cursorMarker = null;
			map?.remove();
			map = null;
		};
	});

	// Recolour without rebuilding the map.
	$effect(() => {
		void colors;
		if (ready && map?.getLayer('trace')) map.setPaintProperty('trace', 'line-gradient', gradient() as never);
	});

	$effect(() => {
		if (!ready || !map || !cursorMarker) return;
		const p = cursor === null ? undefined : (pts.find((q) => q.i >= cursor) ?? pts[pts.length - 1]);
		if (p) cursorMarker.setLngLat([p.lng, p.lat]).addTo(map);
		else cursorMarker.remove();
	});

	$effect(() => {
		if (!ready || !map) return;
		const src = map.getSource('sel') as GeoJSONSource | undefined;
		if (!src) return;
		if (!sel) {
			src.setData({ type: 'FeatureCollection', features: [] });
			map.setPaintProperty('trace', 'line-opacity', 1);
			return;
		}
		const from = pts.findIndex((p) => p.i >= sel.i0);
		let to = pts.findIndex((p) => p.i >= sel.i1);
		if (to < 0) to = pts.length - 1;
		src.setData(from >= 0 && to > from ? line(from, to) : { type: 'FeatureCollection', features: [] });
		map.setPaintProperty('trace', 'line-opacity', 0.35);
	});
</script>

{#if pts.length < 2}
	<div class="none">Cette sortie ne contient pas de tracé GPS.</div>
{:else}
	<div class="map" bind:this={el} style:background={land} role="img" aria-label="Tracé GPS de la sortie"></div>
{/if}

<style>
	.map {
		width: 100%;
		aspect-ratio: 800/470;
	}
	.none {
		padding: 48px 24px;
		text-align: center;
		color: var(--ink-2);
	}
	.map :global(.maplibregl-ctrl-attrib) {
		font-size: 10px;
		background: color-mix(in srgb, var(--surface) 80%, transparent);
		color: var(--ink-2);
	}
	.map :global(.maplibregl-ctrl-attrib a) {
		color: inherit;
	}
	@media (max-width: 900px) {
		.map {
			aspect-ratio: 4/3.4;
		}
	}
</style>
