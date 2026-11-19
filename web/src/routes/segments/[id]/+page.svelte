<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, ApiError, type SegmentDetail } from '#lib/api.ts';
	import { app, errorText } from '#lib/app.svelte.ts';
	import ActivityMap from '#lib/components/ActivityMap.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import Spark from '#lib/components/Spark.svelte';
	import * as f from '#lib/format.ts';
	import { themeColors } from '#lib/theme.ts';

	const id = $derived(Number(page.params.id));
	let s = $state<SegmentDetail | null>(null);
	let missing = $state(false);
	let error = $state('');
	let renaming = $state(false);
	let name = $state('');
	let cursor = $state<number | null>(null);

	$effect(() => {
		void id;
		api.segment(id).then(
			(d) => ((s = d), (missing = false), (error = '')),
			(e) => (e instanceof ApiError && (e.status === 404 || e.status === 400) ? (missing = true) : (error = errorText(e)))
		);
	});

	const owner = $derived(app.profiles.find((p) => p.id === s?.profile_id) ?? app.active!);
	const units = $derived(owner.units);
	const theme = $derived.by(() => {
		void app.dark;
		void app.theme;
		return themeColors();
	});
	const best = $derived(s?.efforts[0] ?? null);
	// The chronological chart: the same efforts in the order they were run.
	const byDate = $derived(s ? [...s.efforts].sort((p, q) => p.start_time - q.start_time) : []);
	const perUnit = (seconds: number) => (seconds / s!.distance) * f.unitLength(units);

	async function save(changes: { name?: string; hidden?: boolean }) {
		try {
			s = await api.updateSegment(id, changes);
			return true;
		} catch (e) {
			app.notify(errorText(e));
			return false;
		}
	}
	async function rename(e: SubmitEvent) {
		e.preventDefault();
		if (await save({ name })) {
			renaming = false;
			app.notify('Segment renommé');
		}
	}
	async function hide() {
		if (await save({ hidden: true })) {
			app.notify(`« ${s!.name} » est masqué. Retrouvez-le dans les segments masqués.`);
			goto('/segments');
		}
	}
	async function restore() {
		if (await save({ hidden: false })) app.notify('Segment de nouveau visible');
	}
</script>

{#if missing}
	<div class="emptybox">
		<div class="big"><Icon name="flag" size={28} /></div>
		<h2>Ce segment n'existe pas</h2>
		<a class="btn primary" href="/segments"><Icon name="back" />Tous les segments</a>
	</div>
{:else if error}
	<div class="note warn"><Icon name="x" /><span>{error}</span></div>
{:else if !s}
	<div class="view"><div class="skeleton" style="height:90px"></div><div class="skeleton" style="height:420px"></div></div>
{:else}
	<div class="view">
		<div>
			<a class="back" href="/segments"><Icon name="back" size={17} />Segments</a>
			<div class="spread" style="align-items:flex-start;margin-top:6px">
				<div style="min-width:0;flex:1 1 320px">
					{#if renaming}
						<form class="row" onsubmit={rename}>
							<input class="rename" type="text" maxlength="80" aria-label="Nom du segment" bind:value={name} />
							<button class="btn primary sm" type="submit" disabled={!name.trim()}>Enregistrer</button>
							<button class="btn sm" type="button" onclick={() => (renaming = false)}>Annuler</button>
						</form>
					{:else}
						<h1>{s.name}</h1>
					{/if}
					<div class="row muted" style="margin-top:8px">
						<span class="ic"><Icon name="route" size={16} />{f.distance(s.distance, units)} {f.distanceUnit(units)}</span>
						<span class="ic"><Icon name="mountain" size={16} />{s.gain === null ? 'D+ inconnu' : `${f.elevation(s.gain, units)} ${f.elevationUnit(units)} D+`}</span>
						<span class="ic"><Icon name="flag" size={16} />{s.efforts.length} passage{s.efforts.length > 1 ? 's' : ''}</span>
					</div>
				</div>
				{#if !renaming}
					<div class="row">
						<button class="iconbtn" type="button" data-tip="Renommer" aria-label="Renommer" onclick={() => ((name = s!.name), (renaming = true))}><Icon name="edit" /></button>
						{#if !s.hidden}
							<button class="iconbtn" type="button" data-tip="Masquer ce segment" data-tip-pos="end" aria-label="Masquer ce segment" onclick={hide}><Icon name="eyeoff" /></button>
						{/if}
					</div>
				{/if}
			</div>
		</div>

		{#if s.hidden}
			<div class="note butter">
				<Icon name="eyeoff" /><span class="grow">Ce segment est masqué : il n'apparaît ni dans la liste ni sur les pages des courses. Ses passages restent chronométrés.</span>
				<button class="btn sm" type="button" onclick={restore}>Rétablir</button>
			</div>
		{/if}

		<div class="dgrid">
			<div class="col">
				<section class="card mapcard" aria-label="Tracé du segment">
					<ActivityMap
						lat={s.points.lat}
						lon={s.points.lon}
						colors={s.points.lat.map(() => theme.fast)}
						styleUrl={app.mapStyle}
						land={theme.land}
						casing={theme.surface}
						highlight={theme.accent}
						{cursor}
						sel={null}
						onhover={(i) => (cursor = i)}
					/>
				</section>
				<section class="card">
					<div class="card-h">
						<h2>Tous les passages</h2>
						<span class="small muted">Temps écoulé sur le segment, du plus rapide au plus lent</span>
					</div>
					<div class="card-b">
						<div class="tablebox">
							<table>
								<thead><tr><th>#</th><th class="name">Date</th><th>Temps</th><th>Allure</th><th>FC moy.</th><th>Écart</th></tr></thead>
								<tbody>
									{#each s.efforts as e (e.id)}
										<tr>
											<td><span class="medal" class:m1={e.rank === 1} class:m2={e.rank === 2} class:m3={e.rank === 3}>{e.rank}</span></td>
											<td class="name"><a class="lnk" href="/activities/{e.activity_id}">{f.dateLong(e.start_time, owner.timezone)}</a></td>
											<td>{f.duration(e.elapsed_time)}</td>
											<td>{f.duration(perUnit(e.elapsed_time))} {f.paceUnit(units)}</td>
											<td>{e.avg_hr === null ? '—' : `${Math.round(e.avg_hr)} bpm`}</td>
											<td>{#if e.rank === 1}<span class="best">Meilleur</span>{:else}+{f.duration(e.gap_to_best ?? 0)}{/if}</td>
										</tr>
									{/each}
								</tbody>
							</table>
						</div>
					</div>
				</section>
			</div>
			<div class="col">
				{#if best}
					<div class="tile lav">
						<div class="top"><span class="chip"><Icon name="trophy" size={16} /></span>Meilleur temps</div>
						<div class="v">{f.duration(best.elapsed_time)}</div>
						<div class="s">{f.duration(perUnit(best.elapsed_time))} {f.paceUnit(units)} · {f.dateLong(best.start_time, owner.timezone)}</div>
					</div>
				{/if}
				<section class="card">
					<div class="card-h"><h2>Au fil du temps</h2></div>
					<div class="card-b">
						<Spark
							title="Temps sur le segment, dans l'ordre des dates"
							values={byDate.map((e) => e.elapsed_time)}
							labels={byDate.map((e) => f.dateShort(e.start_time, owner.timezone))}
							color="var(--accent)"
							invert
							width={320}
							height={150}
							format={(v) => f.duration(v)}
						/>
						<p class="small muted" style="margin:8px 0 0">Dans l'ordre des dates. Plus haut = plus rapide.</p>
					</div>
				</section>
				<section class="card">
					<div class="card-h"><h2>Profil</h2></div>
					<div class="card-b">
						{#if s.points.ele}
							<Spark
								title="Profil d'altitude du segment"
								values={s.points.ele.map((v) => (units === 'imperial' ? v * 3.28084 : v))}
								labels={s.points.dist.map((d) => `${f.distance(d, units)} ${f.distanceUnit(units)}`)}
								color="var(--c-sky)"
								width={320}
								height={100}
								format={(v) => `${Math.round(v)} ${f.elevationUnit(units)}`}
							/>
						{:else}
							<p class="muted" style="margin:0">Pas d'altitude enregistrée sur ce segment.</p>
						{/if}
					</div>
				</section>
			</div>
		</div>
	</div>
{/if}

<style>
	.rename {
		max-width: 420px;
		font: 600 22px var(--font-display);
	}
	.ic {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.mapcard {
		position: relative;
		overflow: hidden;
	}
	.lnk:hover {
		color: var(--accent);
	}
</style>
