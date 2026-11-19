<script lang="ts">
	import { api, type Prediction, type RecordDistance } from '#lib/api.ts';
	import { app, errorText } from '#lib/app.svelte.ts';
	import Icon from '#lib/components/Icon.svelte';
	import * as f from '#lib/format.ts';

	const profile = $derived(app.active!);
	const units = $derived(profile.units);
	const tz = $derived(profile.timezone);

	let distances = $state<RecordDistance[] | null>(null);
	let predictions = $state<Prediction[] | null>(null);
	let longest = $state<number | null>(null);
	let weeks = $state(12);
	let processing = $state(false);
	let selected = $state(-1);
	let error = $state('');

	$effect(() => {
		void app.revision;
		Promise.all([api.records(profile.id), api.predictions(profile.id)]).then(
			([r, p]) => {
				distances = r.distances;
				predictions = p.predictions;
				longest = p.longest_recent_run;
				weeks = p.window_weeks;
				processing = r.processing || p.processing;
				// Open on the longest distance that has a record: it is the one a runner asks about first.
				if (selected < 0) selected = Math.max(0, r.distances.findLastIndex((d) => d.record !== null));
				error = '';
			},
			(e) => (error = errorText(e))
		);
	});

	const current = $derived(distances?.[selected] ?? null);
	const date = (unix: number) => new Date(unix * 1000).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: 'numeric', ...(tz ? { timeZone: tz } : {}) });
	const paceOf = (metres: number, seconds: number) => `${f.pace(metres / seconds, units)} ${f.paceUnit(units)}`;

	// The record over time as descending steps: each point is a run that beat every earlier one.
	const W = 640;
	const H = 190;
	const steps = $derived.by(() => {
		const h = current?.history ?? [];
		if (!h.length) return null;
		const t0 = h[0].start_time;
		const t1 = Math.max(Date.now() / 1000, h[h.length - 1].start_time + 1);
		const times = h.map((e) => e.moving_time);
		const [lo, hi] = [Math.min(...times), Math.max(...times)];
		const X = (t: number) => 10 + ((t - t0) / (t1 - t0)) * (W - 20);
		const Y = (v: number) => 26 + (hi > lo ? (v - lo) / (hi - lo) : 0.5) * (H - 62);
		const points = h.map((e, i) => ({ x: X(e.start_time), y: Y(e.moving_time), e, anchor: i === 0 ? 'start' : i === h.length - 1 ? 'end' : 'middle' }));
		const d = points.map((p, i) => (i ? `H${p.x.toFixed(1)}V${p.y.toFixed(1)}` : `M${p.x.toFixed(1)} ${p.y.toFixed(1)}`)).join('') + `H${X(t1).toFixed(1)}`;
		return { points, d, first: date(t0) };
	});
</script>

{#if error}
	<div class="note warn"><Icon name="x" /><span>{error}</span></div>
{/if}
{#if processing}
	<div class="note butter"><Icon name="clock" /><span>Calcul en cours sur une partie de votre historique : les records se complètent tout seuls.</span></div>
{/if}
<div class="note butter">
	<Icon name="timer" />
	<span>Les records sont mesurés en <b>temps en mouvement</b> : les pauses, manuelles ou automatiques, ne comptent pas. Ils sont cherchés partout à l'intérieur de vos courses, pas seulement sur les courses de cette distance.</span>
</div>

{#if distances}
	<div class="recs" role="group" aria-label="Records par distance">
		{#each distances as d, i (d.distance)}
			<button type="button" class="rec" class:none={!d.record} aria-pressed={selected === i} onclick={() => (selected = i)}>
				<span class="d">{f.raceName(d.distance)}</span>
				{#if d.record}
					<span class="t num">{f.duration(d.record.moving_time)}</span>
					<span class="o">{paceOf(d.distance, d.record.moving_time)} · {date(d.record.start_time)}</span>
					<span class="o">
						{#if d.next.length}
							{d.next.map((e, j) => `${j + 2}ᵉ ${f.duration(e.moving_time)}`).join(' · ')}
						{:else}
							Un seul temps pour l'instant
						{/if}
					</span>
				{:else}
					<span class="t">Pas encore couru</span>
					<span class="o">Il apparaîtra dès qu'une course atteindra cette distance.</span>
				{/if}
			</button>
		{/each}
	</div>

	<div class="grid2">
		<section class="card">
			<div class="card-h">
				<h2>Évolution · {current ? f.raceName(current.distance) : ''}</h2>
				{#if current?.record}
					<a class="btn sm" href="/activities/{current.record.activity_id}"><Icon name="route" size={16} />Voir la course du record</a>
				{/if}
			</div>
			<div class="card-b">
				{#if steps && current}
					<svg class="steps" viewBox="0 0 {W} {H}" role="img" aria-label="Évolution du record sur {f.raceName(current.distance)} : {steps.points.map((p) => `${date(p.e.start_time)} ${f.duration(p.e.moving_time)}`).join(', ')}">
						<path d={steps.d} fill="none" stroke="var(--accent)" stroke-width="3" stroke-linejoin="round" />
						{#each steps.points as p (p.e.activity_id)}
							<circle cx={p.x} cy={p.y} r="5" fill="var(--surface)" stroke="var(--accent)" stroke-width="3" />
							<text class="v" x={p.x} y={p.y - 11} text-anchor={p.anchor}>{f.duration(p.e.moving_time)}</text>
						{/each}
						<text x="10" y={H - 4}>{steps.first}</text>
						<text x={W - 10} y={H - 4} text-anchor="end">aujourd'hui</text>
					</svg>
					<p class="muted small">Chaque marche est un record battu : {steps.points.length} {steps.points.length > 1 ? 'améliorations' : 'temps'} sur {f.raceName(current.distance)}. Plus haut = plus rapide.</p>
				{:else}
					<p class="muted">Pas encore de temps sur cette distance.</p>
				{/if}
			</div>
		</section>

		<section class="card">
			<div class="card-h"><h2>Chronos potentiels</h2></div>
			<div class="card-b">
				{#if predictions && predictions.length}
					{#each predictions as p (p.distance)}
						<div class="pred">
							<span class="pd num">{f.raceName(p.distance).replace('-marathon', '')}</span>
							<span>
								<span class="pt num">{f.duration(p.time)}</span> <span class="muted small">{paceOf(p.distance, p.time)}</span>
								{#if p.indicative}
									<br /><span class="tag">Indicatif</span>
									<span class="muted small">Aucune sortie récente n'approche cette distance{longest ? ` (plus longue : ${f.distance(longest, units, 1)} ${f.distanceUnit(units)})` : ''}.</span>
								{/if}
							</span>
							<span class="muted small basis">d'après votre {f.raceName(p.basis.distance).toLowerCase()}<br />en {f.duration(p.basis.moving_time)} du {date(p.basis.start_time)}</span>
						</div>
					{/each}
					<div class="note" style="margin-top:12px">
						<Icon name="info" />
						<span>Ce sont des estimations, calculées à partir de vos meilleurs efforts des {weeks} dernières semaines. Elles ne tiennent compte ni du parcours, ni de la météo, ni de votre préparation.</span>
					</div>
				{:else if predictions}
					<div class="emptybox flat">
						<div class="big"><Icon name="gauge" size={28} /></div>
						<h3>Pas d'effort récent</h3>
						<p class="muted">Il faut au moins une course d'un kilomètre ou plus dans les {weeks} dernières semaines pour estimer vos chronos.</p>
					</div>
				{/if}
			</div>
		</section>
	</div>
{:else if !error}
	<div class="skeleton" style="min-height:240px"></div>
{/if}

<style>
	.recs {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 14px;
	}
	.rec {
		border-radius: var(--r-lg);
		padding: 16px 18px;
		background: var(--surface);
		box-shadow: var(--shadow);
		display: flex;
		flex-direction: column;
		gap: 4px;
		text-align: left;
		min-width: 0;
		transition:
			transform 0.15s,
			background 0.15s;
	}
	.rec:hover {
		transform: translateY(-2px);
	}
	.rec[aria-pressed='true'] {
		background: var(--p-lav);
		box-shadow: none;
	}
	.rec .d {
		font-size: 13px;
		font-weight: 700;
		color: var(--ink-2);
	}
	.rec .t {
		font: 600 34px/1.1 var(--font-display);
	}
	.rec .o {
		font-size: 12.5px;
		color: var(--ink-2);
	}
	.rec.none .t {
		color: var(--ink-2);
		font-size: 22px;
		line-height: 1.6;
	}
	.grid2 {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 20px;
		align-items: start;
	}
	.steps {
		display: block;
		width: 100%;
		height: auto;
		overflow: visible;
	}
	.steps text {
		font: 600 11.5px var(--font-body);
		fill: var(--ink-3);
	}
	.steps text.v {
		fill: var(--ink);
		font: 600 13px var(--font-display);
	}
	.small {
		font-size: 13px;
	}
	.pred {
		display: grid;
		grid-template-columns: auto minmax(0, 1fr) auto;
		gap: 6px 16px;
		align-items: center;
		padding: 12px;
		border-radius: var(--r-md);
	}
	.pred:hover {
		background: var(--surface-2);
	}
	.pt {
		font-size: 26px;
		font-weight: 600;
	}
	.pd {
		font-size: 16px;
		font-weight: 600;
		min-width: 74px;
	}
	.basis {
		text-align: right;
	}
	.note.butter {
		background: var(--p-butter);
	}
	.emptybox.flat {
		box-shadow: none;
		background: var(--surface-2);
		padding: 30px 20px;
	}
	h3 {
		font-size: 17px;
	}
	@media (max-width: 1180px) {
		.grid2 {
			grid-template-columns: minmax(0, 1fr);
		}
		.recs {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}
	}
	@media (max-width: 560px) {
		.recs {
			grid-template-columns: minmax(0, 1fr);
		}
		.pred {
			grid-template-columns: auto minmax(0, 1fr);
		}
		.basis {
			grid-column: 1 / -1;
			text-align: left;
		}
	}
</style>
