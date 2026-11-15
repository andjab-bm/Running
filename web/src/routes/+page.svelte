<script lang="ts">
	import { api, type ActivitySummary } from '#lib/api.ts';
	import { app, errorText } from '#lib/app.svelte.ts';
	import Icon from '#lib/components/Icon.svelte';
	import Trace from '#lib/components/Trace.svelte';
	import * as f from '#lib/format.ts';

	type Period = 'all' | 'month' | '3m';
	const PERIODS: [Period, string][] = [
		['all', 'Tout'],
		['month', 'Ce mois'],
		['3m', '3 mois']
	];
	const PAGE = 24;

	const profile = $derived(app.active!);
	const units = $derived(profile.units);
	const tz = $derived(profile.timezone);

	let tag = $state<string | null>(null);
	let period = $state<Period>('all');
	let items = $state<ActivitySummary[]>([]);
	let cursor = $state<string | null>(null);
	let loading = $state(true);
	let error = $state('');
	let tags = $state<string[]>([]);
	let month = $state<ActivitySummary[]>([]);
	let sentinel = $state<HTMLElement | null>(null);
	// Guards against a slow response overwriting a newer filter.
	let generation = 0;

	const monthStart = () => {
		const d = new Date();
		return new Date(d.getFullYear(), d.getMonth(), 1).getTime() / 1000;
	};
	const from = $derived(period === 'month' ? monthStart() : period === '3m' ? Date.now() / 1000 - 90 * 86400 : null);

	async function loadPage(reset: boolean) {
		const mine = reset ? ++generation : generation;
		loading = true;
		try {
			const page = await api.activities(profile.id, { limit: PAGE, tag, from, cursor: reset ? null : cursor });
			if (mine !== generation) return;
			items = reset ? page.items : [...items, ...page.items];
			cursor = page.next_cursor;
			error = '';
		} catch (e) {
			if (mine === generation) error = errorText(e);
		} finally {
			if (mine === generation) loading = false;
		}
	}

	// Reload from the top whenever a filter changes or activities were added or removed.
	$effect(() => {
		void [tag, from, app.revision];
		loadPage(true);
	});

	$effect(() => {
		void app.revision;
		api.tags(profile.id).then((t) => (tags = t), () => {});
		api.activities(profile.id, { limit: 100, from: monthStart() }).then((p) => (month = p.items), () => {});
	});

	// Older activities load as the end of the list scrolls into view.
	$effect(() => {
		if (!sentinel) return;
		const io = new IntersectionObserver((entries) => {
			if (entries[0].isIntersecting && cursor && !loading) loadPage(false);
		});
		io.observe(sentinel);
		return () => io.disconnect();
	});

	const totals = $derived({
		dist: month.reduce((s, a) => s + a.distance, 0),
		time: month.reduce((s, a) => s + a.moving_time, 0)
	});
	const monthName = new Date().toLocaleDateString('fr-FR', { month: 'long' });

	const groups = $derived.by(() => {
		const out: { key: string; items: ActivitySummary[]; dist: number }[] = [];
		for (const a of items) {
			const key = f.monthKey(a.start_time, tz);
			let g = out[out.length - 1];
			if (!g || g.key !== key) out.push((g = { key, items: [], dist: 0 }));
			g.items.push(a);
			g.dist += a.distance;
		}
		return out;
	});
	const filtered = $derived(tag !== null || period !== 'all');
</script>

<div class="view">
	<div class="spread">
		<div>
			<div class="eyebrow">Profil de {profile.name}</div>
			<h1>Activités</h1>
		</div>
		<a class="btn primary" href="/import"><Icon name="upload" />Importer</a>
	</div>

	{#if error}
		<div class="note warn"><Icon name="x" /><span>{error}</span></div>
	{/if}

	{#if !loading && items.length === 0 && !filtered && !error}
		<div class="emptybox">
			<div class="big"><Icon name="route" size={30} /></div>
			<h2>Aucune sortie pour {profile.name}</h2>
			<p class="muted">Importez un fichier .gpx ou .fit, ou copiez une sortie depuis un autre profil du foyer.</p>
			<a class="btn primary" href="/import"><Icon name="upload" />Importer des fichiers</a>
		</div>
	{:else}
		<div class="tiles">
			<div class="tile sky">
				<div class="top"><span class="chip"><Icon name="route" size={16} /></span>Distance en {monthName}</div>
				<div class="v">{f.distance(totals.dist, units, 1)}<small>{f.distanceUnit(units)}</small></div>
			</div>
			<div class="tile lav">
				<div class="top"><span class="chip"><Icon name="calendar" size={16} /></span>Sorties en {monthName}</div>
				<div class="v">{month.length}</div>
			</div>
			<div class="tile mint">
				<div class="top"><span class="chip"><Icon name="timer" size={16} /></span>Temps de course</div>
				<div class="v">{f.duration(totals.time)}</div>
			</div>
			<div class="tile peach">
				<div class="top"><span class="chip"><Icon name="gauge" size={16} /></span>Allure moyenne</div>
				<div class="v">{f.pace(totals.time ? totals.dist / totals.time : null, units)}<small>{f.paceUnit(units)}</small></div>
			</div>
		</div>

		<div class="spread" style="align-items:center">
			<div class="filters" role="group" aria-label="Filtrer par tag">
				<button class="fchip" type="button" aria-pressed={tag === null} onclick={() => (tag = null)}>Toutes</button>
				{#each tags as t (t)}
					<button class="fchip" type="button" aria-pressed={tag === t} onclick={() => (tag = t)}>{t}</button>
				{/each}
			</div>
			<div class="seg" role="group" aria-label="Période">
				{#each PERIODS as [value, label] (value)}
					<button type="button" aria-pressed={period === value} onclick={() => (period = value)}>{label}</button>
				{/each}
			</div>
		</div>

		{#if !loading && items.length === 0}
			<div class="emptybox">
				<div class="big"><Icon name="list" size={28} /></div>
				<h2>Aucune sortie ne correspond</h2>
				<button class="btn" type="button" onclick={() => ((tag = null), (period = 'all'))}>Réinitialiser les filtres</button>
			</div>
		{/if}

		{#each groups as g (g.key)}
			<div class="month">
				<h2>{g.key}</h2>
				<span class="muted">{f.distance(g.dist, units, 1)} {f.distanceUnit(units)}</span>
			</div>
			<div class="acts">
				{#each g.items as a (a.id)}
					{@const c = f.activityColor(a)}
					<a class="act" href="/activities/{a.id}">
						<div class="thumb" style:background="var(--p-{c})">
							<Trace points={a.preview} color={c} />
							<div class="km" style:color="var(--i-{c})">{f.distance(a.distance, units)}<small>{f.distanceUnit(units)}</small></div>
							{#if a.copied_from}
								<div class="cp" data-tip="Copiée depuis un autre profil" data-tip-pos="end"><Icon name="copy" size={13} />Copie</div>
							{/if}
						</div>
						<div class="body">
							<div>
								<div class="when">{f.dateShort(a.start_time, tz)} · {f.clock(a.start_time, tz)}</div>
								<h3>{a.title}</h3>
							</div>
							<div class="mini">
								<span data-tip="Temps en mouvement" data-tip-pos="start"><Icon name="timer" size={15} /><b>{f.duration(a.moving_time)}</b></span>
								<span data-tip="Allure moyenne"><Icon name="gauge" size={15} /><b>{f.pace(a.speed, units)}</b>{f.paceUnit(units)}</span>
								<span data-tip="Fréquence cardiaque moyenne"><Icon name="heart" size={15} /><b>{f.whole(a.avg_hr)}</b>{a.avg_hr ? 'bpm' : ''}</span>
							</div>
							{#if a.tags.length}
								<div class="row" style="gap:6px">
									{#each a.tags as t (t)}<span class="tag {f.tagColor(t)}">{t}</span>{/each}
								</div>
							{/if}
						</div>
					</a>
				{/each}
			</div>
		{/each}

		{#if loading}
			<div class="acts">
				{#each [0, 1, 2] as i (i)}<div class="skeleton" style="min-height:230px;border-radius:var(--r-xl)"></div>{/each}
			</div>
		{/if}
		<div bind:this={sentinel}></div>
	{/if}
</div>

<style>
	.filters {
		display: flex;
		gap: 8px;
		flex-wrap: wrap;
	}
	.fchip {
		padding: 7px 14px;
		border-radius: 999px;
		background: var(--surface);
		font-weight: 600;
		font-size: 13.5px;
		color: var(--ink-2);
		box-shadow: var(--shadow);
		transition:
			background 0.15s,
			color 0.15s;
	}
	.fchip:hover {
		color: var(--ink);
	}
	.fchip[aria-pressed='true'] {
		background: var(--ink);
		color: var(--bg);
	}
	.month {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 12px;
		margin-top: 6px;
	}
	.month h2 {
		font-size: 17px;
		text-transform: capitalize;
	}
	.acts {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
		gap: 16px;
	}
	.act {
		background: var(--surface);
		border-radius: var(--r-xl);
		box-shadow: var(--shadow);
		padding: 10px;
		display: flex;
		flex-direction: column;
		gap: 12px;
		transition:
			transform 0.18s,
			box-shadow 0.18s;
	}
	.act:hover {
		transform: translateY(-3px);
		box-shadow: 0 20px 44px rgba(70, 58, 140, 0.13);
	}
	.thumb {
		border-radius: 20px;
		aspect-ratio: 16/8;
		position: relative;
		overflow: hidden;
	}
	.km {
		position: absolute;
		left: 14px;
		bottom: 10px;
		font: 600 26px/1 var(--font-display);
		font-variant-numeric: tabular-nums;
	}
	.km small {
		font: 600 13px var(--font-body);
		margin-left: 3px;
	}
	.cp {
		position: absolute;
		right: 10px;
		top: 10px;
		background: var(--surface);
		border-radius: 999px;
		padding: 3px 9px;
		font-size: 11.5px;
		font-weight: 600;
		display: flex;
		gap: 5px;
		align-items: center;
	}
	.body {
		padding: 0 10px 10px;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.when {
		color: var(--ink-2);
		font-size: 13px;
		font-weight: 600;
	}
	h3 {
		font-size: 18px;
		font-weight: 600;
		line-height: 1.2;
	}
	.mini {
		display: flex;
		gap: 16px;
		flex-wrap: wrap;
		color: var(--ink-2);
		font-size: 13.5px;
	}
	.mini span {
		display: inline-flex;
		align-items: center;
		gap: 5px;
	}
	.mini b {
		font: 600 15.5px var(--font-display);
		color: var(--ink);
		font-variant-numeric: tabular-nums;
	}
	@media (max-width: 420px) {
		.acts {
			grid-template-columns: minmax(0, 1fr);
		}
	}
</style>
