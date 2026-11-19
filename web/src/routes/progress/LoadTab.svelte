<script lang="ts">
	import { api, tzOffsetMinutes, type Load, type LoadStatus } from '#lib/api.ts';
	import { app, errorText } from '#lib/app.svelte.ts';
	import Bars from '#lib/components/Bars.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import * as f from '#lib/format.ts';

	// One pastel and one sentence per state, as approved on the prototype.
	const STATUS: Record<Exclude<LoadStatus, 'not_enough_data'>, { name: string; tile: string; text: string }> = {
		resuming: { name: 'Reprise', tile: 'sky', text: 'Votre charge récente est bien en dessous de votre habitude. Reprenez progressivement.' },
		light: { name: 'Léger', tile: 'mint', text: "Vous en faites un peu moins que d'habitude : bon pour récupérer, pas pour progresser." },
		steady: { name: 'Stable', tile: 'lav', text: 'Votre charge récente est en ligne avec votre habitude. Vous entretenez votre forme.' },
		building: { name: 'En progression', tile: 'peach', text: "Vous en faites un peu plus que d'habitude, à un rythme que le corps suit." },
		too_much: { name: 'Trop, trop vite', tile: 'rose', text: 'Votre charge a bondi par rapport à votre habitude. Levez le pied quelques jours.' }
	};
	const ORDER = ['resuming', 'light', 'steady', 'building', 'too_much'] as const;

	const profile = $derived(app.active!);
	const tz = $derived(tzOffsetMinutes(profile.timezone));

	let load = $state<Load | null>(null);
	let error = $state('');

	$effect(() => {
		void app.revision;
		api.load(profile.id, 84, tz).then((l) => ((load = l), (error = '')), (e) => (error = errorText(e)));
	});

	const status = $derived(load && load.status.code !== 'not_enough_data' ? STATUS[load.status.code] : null);
	const anyEstimated = $derived(!!load?.days.some((d) => d.estimated));

	// Daily bars with the two averages over them. The averages are per day, far below the
	// load of a single run: they get their own scale so that their shapes can be compared.
	const W = 760;
	const H = 230;
	const chart = $derived.by(() => {
		const days = load?.days ?? [];
		if (!days.length) return null;
		const n = days.length;
		const maxLoad = Math.max(1, ...days.map((d) => d.load));
		const maxAvg = Math.max(1, ...days.map((d) => Math.max(d.short, d.long)));
		const X = (i: number) => 8 + (i * (W - 16)) / Math.max(1, n - 1);
		const Y = (v: number, max: number) => H - 26 - (v / max) * (H - 44);
		const bw = Math.max(2, (W - 16) / n - 2);
		const line = (key: 'short' | 'long') => days.map((d, i) => `${i ? 'L' : 'M'}${X(i).toFixed(1)} ${Y(d[key], maxAvg * 1.15).toFixed(1)}`).join('');
		return {
			bars: days.map((d, i) => ({ d, x: X(i) - bw / 2, y: Y(d.load, maxLoad), h: H - 26 - Y(d.load, maxLoad) })).filter((b) => b.d.load > 0),
			bw,
			short: line('short'),
			long: line('long'),
			first: f.isoDayShort(days[0].date),
			middle: f.isoDayShort(days[Math.floor(n / 2)].date)
		};
	});

	const weeks = $derived(
		(load?.weeks ?? []).map((w) => ({ label: f.isoDayShort(w.start), value: w.load, text: `Charge ${Math.round(w.load)}`, current: w.current }))
	);

	const recovery = $derived.by(() => {
		const r = load?.recovery;
		if (!r) return null;
		const left = Math.ceil(r.hours_remaining);
		const at = new Date(r.recovered_at * 1000);
		const sameDay = at.toDateString() === new Date().toDateString();
		const when = `${sameDay ? "aujourd'hui" : at.toLocaleDateString('fr-FR', { weekday: 'long' })} vers ${at.getHours()} h`;
		return { ...r, left, when, done: Math.round((1 - r.hours_remaining / r.hours_advised) * 100) };
	});
</script>

{#if error}
	<div class="note warn"><Icon name="x" /><span>{error}</span></div>
{/if}

{#if load && load.days.length === 0}
	<div class="emptybox">
		<div class="big"><Icon name="zap" size={28} /></div>
		<h2>Pas encore de charge</h2>
		<p class="muted">{load.processing ? 'Le calcul est en cours sur vos courses.' : 'La charge apparaît dès votre première course.'}</p>
	</div>
{:else if load}
	{#if load.processing}
		<div class="note butter"><Icon name="clock" /><span>Calcul en cours sur une partie de votre historique : la charge se complète toute seule.</span></div>
	{/if}
	<div class="grid2">
		{#if status}
			<div class="status tile {status.tile}">
				<span class="eyebrow">État de la charge</span>
				<span class="sv">{status.name}</span>
				<span>{status.text}</span>
				<div class="gauge" aria-hidden="true">
					{#each ORDER as code (code)}<i class:on={code === load.status.code}></i>{/each}
				</div>
				<span class="muted small">Charge récente (7 j) : <b class="num">{Math.round(load.status.short ?? 0)}</b> par jour · habitude (28 j) : <b class="num">{Math.round(load.status.long ?? 0)}</b> par jour</span>
			</div>
		{:else}
			<div class="status tile sky">
				<span class="eyebrow">État de la charge</span>
				<span class="sv">Pas encore assez de données</span>
				<span>Il faut deux semaines de courses pour comparer votre charge récente à votre habitude. Votre première course date d'il y a {load.status.history_days} jour{load.status.history_days > 1 ? 's' : ''}.</span>
			</div>
		{/if}

		<section class="card">
			<div class="card-b rec">
				{#if recovery}
					<div class="ring" style:--p={recovery.done} role="img" aria-label="{recovery.left ? `${recovery.left} heures de récupération restantes` : 'Récupéré'}">
						<div>
							{#if recovery.left}<span class="num">{recovery.left} h</span><small>restantes</small>{:else}<Icon name="check" size={28} /><small>récupéré</small>{/if}
						</div>
					</div>
					<div class="col">
						<span class="eyebrow">Récupération conseillée</span>
						<b class="rt">{recovery.left ? `Encore ${recovery.left} h de récupération` : 'Vous avez récupéré'}</b>
						<span class="muted small">
							{recovery.left ? `Récupéré ${recovery.when}.` : 'Vous pouvez repartir quand vous voulez.'}
							{Math.round(recovery.hours_advised)} h conseillées après votre
							<a class="link" href="/activities/{recovery.activity_id}">dernière course</a> (charge {Math.round(recovery.load)}).
						</span>
						{#if recovery.estimated}
							<span class="tag off" data-tip="Sans cardio : estimée d'après la durée et le ressenti" data-tip-pos="start"><Icon name="info" size={14} />Charge estimée</span>
						{/if}
					</div>
				{/if}
			</div>
		</section>
	</div>

	<section class="card">
		<div class="card-h">
			<h2>Charge au fil des jours</h2>
			<div class="lg">
				<span><i class="sq"></i>Charge du jour</span>
				<span><i style:background="var(--accent)"></i>Récente (7 j)</span>
				<span><i class="dash"></i>Habitude (28 j)</span>
			</div>
		</div>
		<div class="card-b">
			{#if chart}
				<svg class="chart" viewBox="0 0 {W} {H}" role="img" aria-label="Charge quotidienne sur {load.days.length} jours, avec la charge récente et la charge habituelle">
					{#each chart.bars as b (b.d.date)}
						<rect x={b.x} y={b.y} width={chart.bw} height={b.h} rx="3" fill="var(--p-peach)"><title>{f.isoDayShort(b.d.date)} : charge {Math.round(b.d.load)}{b.d.estimated ? ' (estimée)' : ''}</title></rect>
					{/each}
					<path d={chart.long} fill="none" stroke="var(--ink-3)" stroke-width="2.4" stroke-dasharray="5 5" />
					<path d={chart.short} fill="none" stroke="var(--accent)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />
					<text x="8" y={H - 6}>{chart.first}</text>
					<text x={W / 2} y={H - 6} text-anchor="middle">{chart.middle}</text>
					<text x={W - 8} y={H - 6} text-anchor="end">aujourd'hui</text>
				</svg>
			{/if}
			{#if anyEstimated}
				<p class="muted small" style="margin:8px 0 0">Certaines charges sont estimées : ces courses n'ont pas de fréquence cardiaque.</p>
			{/if}
		</div>
	</section>

	<section class="card">
		<div class="card-h"><h2>Charge par semaine</h2></div>
		<div class="card-b"><Bars items={weeks} color="var(--c-peach)" title="Charge par semaine" /></div>
	</section>

	<details>
		<summary><Icon name="chev" />Comment est-ce calculé ?</summary>
		<ul>
			<li><b>Charge d'une course</b> : son effort relatif (minutes passées dans chaque zone cardiaque × numéro de la zone). Sans cardio, elle est estimée d'après la durée et le ressenti que vous avez noté, et marquée « estimée ».</li>
			<li><b>Charge récente et habitude</b> : moyennes par jour sur environ 7 et 28 jours, qui donnent plus de poids aux jours récents. Leur rapport donne l'état.</li>
			<li><b>Récupération</b> : plus longue après une séance plus dure que d'habitude ou quand la charge récente est déjà haute, entre 6 et 96 heures.</li>
			<li>Ce sont des estimations faites uniquement à partir de vos courses enregistrées. Le sommeil, le stress, les autres sports et votre santé ne sont pas pris en compte : ce n'est pas un avis médical.</li>
		</ul>
	</details>
{:else if !error}
	<div class="skeleton" style="min-height:240px"></div>
{/if}

<style>
	.grid2 {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 20px;
		align-items: stretch;
	}
	.status {
		padding: 18px 20px;
		gap: 6px;
	}
	.status:hover {
		transform: none;
	}
	/* The tertiary ink falls under 4.5:1 on the rose and peach fills. */
	.status .eyebrow {
		color: var(--ink-2);
	}
	.sv {
		font: 600 28px/1.15 var(--font-display);
	}
	.gauge {
		height: 12px;
		display: flex;
		gap: 3px;
		margin-top: 8px;
	}
	.gauge i {
		flex: 1;
		border-radius: 6px;
		background: var(--surface);
		opacity: 0.55;
	}
	.gauge i.on {
		opacity: 1;
		background: var(--ink);
	}
	.small {
		font-size: 13px;
	}
	.rec {
		display: flex;
		gap: 20px;
		align-items: center;
		padding-top: 22px;
		min-height: 100%;
	}
	.col {
		display: flex;
		flex-direction: column;
		gap: 6px;
		min-width: 0;
		align-items: flex-start;
	}
	.rt {
		font: 600 20px var(--font-display);
	}
	.link {
		text-decoration: underline;
		text-underline-offset: 2px;
	}
	.ring {
		width: 112px;
		height: 112px;
		border-radius: 50%;
		background: conic-gradient(var(--c-mint) calc(var(--p) * 1%), var(--surface-2) 0);
		display: grid;
		place-items: center;
		flex: none;
	}
	.ring > div {
		width: 88px;
		height: 88px;
		border-radius: 50%;
		background: var(--surface);
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		font: 600 24px/1 var(--font-display);
	}
	.ring small {
		font: 600 11.5px var(--font-body);
		color: var(--ink-2);
		margin-top: 3px;
	}
	.chart {
		display: block;
		width: 100%;
		height: auto;
		overflow: visible;
	}
	.chart text {
		font: 600 11.5px var(--font-body);
		fill: var(--ink-3);
	}
	.lg {
		display: flex;
		gap: 16px;
		flex-wrap: wrap;
		font-size: 12.5px;
		font-weight: 600;
		color: var(--ink-2);
	}
	.lg i {
		display: inline-block;
		width: 14px;
		height: 4px;
		border-radius: 2px;
		margin-right: 6px;
		vertical-align: middle;
	}
	.lg .sq {
		width: 10px;
		height: 10px;
		border-radius: 3px;
		background: var(--p-peach);
	}
	.lg .dash {
		background: repeating-linear-gradient(90deg, var(--ink-3) 0 4px, transparent 4px 7px);
	}
	.note.butter {
		background: var(--p-butter);
	}
	details {
		background: var(--surface-2);
		border-radius: var(--r-lg);
		padding: 14px 18px;
	}
	summary {
		font-weight: 600;
		cursor: pointer;
		list-style: none;
		display: flex;
		align-items: center;
		gap: 8px;
	}
	summary::-webkit-details-marker {
		display: none;
	}
	details ul {
		margin: 10px 0 0;
		padding-left: 18px;
		color: var(--ink-2);
		max-width: 70ch;
	}
	details li {
		margin: 4px 0;
	}
	@media (max-width: 1180px) {
		.grid2 {
			grid-template-columns: minmax(0, 1fr);
		}
	}
</style>
