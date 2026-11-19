<script lang="ts">
	import { api, tzOffsetMinutes, type Calendar, type CalendarDay, type ProgressMonth, type VolumePeriod } from '#lib/api.ts';
	import { app, errorText } from '#lib/app.svelte.ts';
	import Bars from '#lib/components/Bars.svelte';
	import Heatmap from '#lib/components/Heatmap.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import Spark from '#lib/components/Spark.svelte';
	import * as f from '#lib/format.ts';

	type Measure = 'distance' | 'time' | 'load';
	const MEASURES: { key: Measure; label: string; color: string }[] = [
		{ key: 'distance', label: 'Distance', color: 'var(--accent)' },
		{ key: 'time', label: 'Durée', color: 'var(--c-mint)' },
		{ key: 'load', label: 'Charge', color: 'var(--c-peach)' }
	];

	const profile = $derived(app.active!);
	const units = $derived(profile.units);
	const tz = $derived(tzOffsetMinutes(profile.timezone));
	const thisYear = new Date().getFullYear();
	const YEARS = [thisYear - 2, thisYear - 1, thisYear];

	let year = $state(thisYear);
	let measure = $state<Measure>('distance');
	let calendar = $state<Calendar | null>(null);
	let day = $state<string | null>(null);
	let unit = $state<'week' | 'month'>('week');
	// The periods come with the unit they were asked in, so that a switch never labels weeks as months.
	let volume = $state<{ unit: 'week' | 'month'; periods: VolumePeriod[] }>({ unit: 'week', periods: [] });
	let tag = $state<string | null>(null);
	let tags = $state<string[]>([]);
	let months = $state<ProgressMonth[]>([]);
	let error = $state('');

	const fail = (e: unknown) => (error = errorText(e));

	$effect(() => {
		void app.revision;
		calendar = null;
		api.calendar(profile.id, year, tz).then((c) => ((calendar = c), (error = '')), fail);
	});
	$effect(() => {
		void app.revision;
		const asked = unit;
		api.volume(profile.id, asked, 12, tz).then((v) => (volume = { unit: asked, periods: v }), fail);
	});
	$effect(() => {
		void app.revision;
		api.progress(profile.id, 12, tag, tz).then((m) => (months = m), fail);
	});
	$effect(() => {
		api.tags(profile.id).then((t) => (tags = t), () => {});
	});

	const m = $derived(MEASURES.find((x) => x.key === measure)!);
	const weigh = (d: CalendarDay) => (measure === 'distance' ? d.distance : measure === 'time' ? d.moving_time : d.load);
	const picked = $derived(calendar?.days.find((d) => d.date === day) ?? null);
	const dayLabel = (date: Date, d: CalendarDay | undefined) => {
		const when = date.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' });
		return d ? `${when} : ${f.distance(d.distance, units, 1)} ${f.distanceUnit(units)}` : `${when} : repos`;
	};

	const bars = $derived(
		volume.periods.map((p) => ({
			label: f.isoDayShort(volume.unit === 'week' ? p.start : p.start.slice(0, 7)),
			value: p.distance,
			text: `${f.distance(p.distance, units, 1)} ${f.distanceUnit(units)} · ${p.runs} sortie${p.runs > 1 ? 's' : ''}`,
			current: p.current
		}))
	);
	const labels = $derived(months.map((x) => f.isoDayShort(x.month)));
	const latest = <K extends 'avg_speed' | 'longest' | 'avg_hr'>(key: K) => [...months].reverse().find((x) => x[key] !== null)?.[key] ?? null;
</script>

{#if error}
	<div class="note warn"><Icon name="x" /><span>{error}</span></div>
{/if}

<section class="card">
	<div class="card-h">
		<div class="row">
			<h2>Calendrier</h2>
			<div class="seg" role="group" aria-label="Année">
				{#each YEARS as y (y)}
					<button type="button" aria-pressed={year === y} onclick={() => ((year = y), (day = null))}>{y}</button>
				{/each}
			</div>
		</div>
		<div class="seg" role="group" aria-label="Mesure du calendrier">
			{#each MEASURES as x (x.key)}
				<button type="button" aria-pressed={measure === x.key} onclick={() => (measure = x.key)}>{x.label}</button>
			{/each}
		</div>
	</div>
	<div class="card-b">
		{#if calendar}
			{@const t = calendar.totals}
			<div class="tiles" style="margin-bottom:18px">
				<div class="tile lav">
					<div class="top"><span class="chip"><Icon name="calendar" size={16} /></span>Sorties</div>
					<div class="v">{t.runs}</div>
					<div class="s">{t.active_days} jour{t.active_days > 1 ? 's' : ''} couru{t.active_days > 1 ? 's' : ''}</div>
				</div>
				<div class="tile sky">
					<div class="top"><span class="chip"><Icon name="route" size={16} /></span>Distance</div>
					<div class="v">{f.distance(t.distance, units, 0)}<small>{f.distanceUnit(units)}</small></div>
					<div class="s">en {year}</div>
				</div>
				<div class="tile mint">
					<div class="top"><span class="chip"><Icon name="clock" size={16} /></span>Temps</div>
					<div class="v">{f.hoursMinutes(t.moving_time)}</div>
					<div class="s">en mouvement</div>
				</div>
				<div class="tile peach">
					<div class="top"><span class="chip"><Icon name="mountain" size={16} /></span>Dénivelé</div>
					<div class="v">{f.elevation(t.gain, units)}<small>{f.elevationUnit(units)}</small></div>
					<div class="s">D+ cumulé</div>
				</div>
			</div>
			<Heatmap {year} days={calendar.days} value={weigh} color={m.color} bind:selected={day} label={dayLabel} />
			<div class="daybox" aria-live="polite">
				{#if !day}
					<Icon name="info" size={17} /><span class="muted">{t.runs ? 'Survolez ou touchez un jour pour voir ses courses.' : `Aucune course en ${year}.`}</span>
				{:else if picked}
					<b>{f.isoDayLong(picked.date)}</b>
					{#each picked.activities as a (a.id)}
						<a class="tag lav" href="/activities/{a.id}">{a.title} · {f.distance(a.distance, units, 1)} {f.distanceUnit(units)}</a>
					{/each}
					{#if picked.activities.length > 1}
						<span class="muted small">Total du jour : {f.distance(picked.distance, units, 1)} {f.distanceUnit(units)}</span>
					{:else}
						<span class="muted small">{f.duration(picked.moving_time)}</span>
					{/if}
				{:else}
					<b>{f.isoDayLong(day)}</b><span class="muted">Repos.</span>
				{/if}
			</div>
		{:else}
			<div class="skeleton" style="min-height:260px"></div>
		{/if}
	</div>
</section>

<section class="card">
	<div class="card-h">
		<h2>Volume</h2>
		<div class="seg" role="group" aria-label="Période du volume">
			<button type="button" aria-pressed={unit === 'week'} onclick={() => (unit = 'week')}>Semaines</button>
			<button type="button" aria-pressed={unit === 'month'} onclick={() => (unit = 'month')}>Mois</button>
		</div>
	</div>
	<div class="card-b">
		<Bars items={bars} title="Distance par {volume.unit === 'week' ? 'semaine' : 'mois'}" />
		<p class="muted small" style="margin:10px 0 0">La barre hachurée est la période en cours. Les semaines commencent le lundi.</p>
	</div>
</section>

<section class="card">
	<div class="card-h">
		<h2>Courbes de progression</h2>
		<div class="field" style="margin:0;flex-direction:row;align-items:center;gap:8px">
			<label for="progress-tag">Courses</label>
			<select id="progress-tag" style="width:auto" bind:value={tag}>
				<option value={null}>Toutes</option>
				{#each tags as t (t)}<option value={t}>{t}</option>{/each}
			</select>
		</div>
	</div>
	<div class="card-b">
		<div class="mini3">
			<div>
				<div class="eyebrow">Allure moyenne</div>
				<div class="big num">{f.pace(latest('avg_speed'), units)}<small>{f.paceUnit(units)}</small></div>
				<Spark
					title="Allure moyenne par mois"
					values={months.map((x) => f.paceSeconds(x.avg_speed, units))}
					{labels}
					color="var(--accent)"
					invert
					format={(v) => `${f.duration(v)} ${f.paceUnit(units)}`}
				/>
				<span class="muted small">Plus haut = plus rapide</span>
			</div>
			<div>
				<div class="eyebrow">Sortie la plus longue</div>
				<div class="big num">{latest('longest') === null ? '—' : f.distance(latest('longest')!, units, 1)}<small>{f.distanceUnit(units)}</small></div>
				<Spark
					title="Sortie la plus longue par mois"
					values={months.map((x) => (x.longest === null ? null : x.longest / f.unitLength(units)))}
					{labels}
					color="var(--c-sky)"
					format={(v) => `${v.toFixed(1).replace('.', ',')} ${f.distanceUnit(units)}`}
				/>
				<span class="muted small">Par mois</span>
			</div>
			<div>
				<div class="eyebrow">Fréquence cardiaque moyenne</div>
				<div class="big num">{f.whole(latest('avg_hr'))}<small>bpm</small></div>
				<Spark title="Fréquence cardiaque moyenne par mois" values={months.map((x) => x.avg_hr)} {labels} color="var(--c-rose)" format={(v) => `${Math.round(v)} bpm`} />
				<span class="muted small">Un trou = mois sans cardio</span>
			</div>
		</div>
	</div>
</section>

<style>
	.daybox {
		background: var(--surface-2);
		border-radius: var(--r-lg);
		padding: 12px 16px;
		display: flex;
		gap: 12px;
		align-items: center;
		flex-wrap: wrap;
		min-height: 54px;
		margin-top: 14px;
	}
	.small {
		font-size: 13px;
	}
	.mini3 {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 14px;
	}
	.mini3 > div {
		background: var(--surface-2);
		border-radius: var(--r-lg);
		padding: 14px 16px 10px;
		min-width: 0;
	}
	.big {
		font-size: 26px;
		font-weight: 600;
	}
	.big small {
		font: 600 13px var(--font-body);
		color: var(--ink-2);
		margin-left: 4px;
	}
	@media (max-width: 900px) {
		.mini3 {
			grid-template-columns: minmax(0, 1fr);
		}
	}
</style>
