<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, ApiError, type RouteDetail, type RoutePoints } from '#lib/api.ts';
	import { app, errorText } from '#lib/app.svelte.ts';
	import ActivityMap from '#lib/components/ActivityMap.svelte';
	import CopyDialog from '#lib/components/CopyDialog.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import MetricChart from '#lib/components/MetricChart.svelte';
	import Modal from '#lib/components/Modal.svelte';
	import PhonePill from '#lib/components/PhonePill.svelte';
	import * as f from '#lib/format.ts';
	import { themeColors } from '#lib/theme.ts';

	const id = $derived(Number(page.params.id));
	let r = $state<RouteDetail | null>(null);
	let pts = $state<RoutePoints | null>(null);
	let missing = $state(false);
	let error = $state('');
	let cursor = $state<number | null>(null);
	let renaming = $state(false);
	let name = $state('');
	let dialog = $state<'copy' | 'delete' | null>(null);

	$effect(() => {
		const rid = id;
		Promise.all([api.route(rid), api.routePoints(rid)]).then(
			([d, p]) => ((r = d), (pts = p), (missing = false), (error = '')),
			(e) => (e instanceof ApiError && (e.status === 404 || e.status === 400) ? (missing = true) : (error = errorText(e)))
		);
	});

	const owner = $derived(app.profiles.find((p) => p.id === r?.profile_id) ?? app.active!);
	const units = $derived(owner.units);
	const theme = $derived.by(() => {
		void app.dark;
		void app.theme;
		return themeColors();
	});
	const x = $derived(pts ? pts.dist.map((d) => d / f.unitLength(units)) : []);
	const ele = $derived(pts?.ele ? pts.ele.map((v) => (units === 'imperial' ? v * 3.28084 : v)) : null);

	async function act<T>(run: () => Promise<T>, done: string): Promise<T | null> {
		try {
			const out = await run();
			app.notify(done);
			return out;
		} catch (e) {
			app.notify(errorText(e));
			return null;
		}
	}
	async function rename(e: SubmitEvent) {
		e.preventDefault();
		const d = await act(() => api.updateRoute(id, { name }), 'Parcours renommé');
		if (d) ((r = d), (renaming = false));
	}
	async function togglePhone() {
		const on = !r!.on_phone;
		const d = await act(() => api.updateRoute(id, { on_phone: on }), on ? 'Le parcours partira à la prochaine synchro du téléphone' : 'Le parcours sera retiré du téléphone à la prochaine synchro');
		if (d) r = d;
	}
	async function reverse() {
		const d = await act(() => api.reverseRoute(id), `Parcours inverse créé : « ${r!.name} (inverse) »`);
		if (d) goto(`/plan/routes/${d.id}`);
	}
	async function copyTo(target: number) {
		dialog = null;
		const who = app.profiles.find((p) => p.id === target)?.name ?? '';
		await act(() => api.copyRoute(id, target), `Copié dans les parcours de ${who}`);
	}
	async function remove() {
		dialog = null;
		if ((await act(() => api.deleteRoute(id), `« ${r!.name} » supprimé`)) !== null) goto('/plan?tab=routes');
	}
	const tip = (i: number) => ({
		title: `${f.distance(pts!.dist[i], units)} ${f.distanceUnit(units)}`,
		rows: [{ color: theme.sky, label: 'Altitude', value: `${Math.round(ele![i])} ${f.elevationUnit(units)}` }]
	});
</script>

{#if missing}
	<div class="emptybox">
		<div class="big"><Icon name="route" size={28} /></div>
		<h2>Ce parcours n'existe pas</h2>
		<a class="btn primary" href="/plan?tab=routes"><Icon name="back" />Tous les parcours</a>
	</div>
{:else if error}
	<div class="note warn"><Icon name="x" /><span>{error}</span></div>
{:else if !r || !pts}
	<div class="view"><div class="skeleton" style="height:90px"></div><div class="skeleton" style="height:420px"></div></div>
{:else}
	<div class="view">
		<div>
			<a class="back" href="/plan?tab=routes"><Icon name="back" size={17} />Parcours</a>
			<div class="spread" style="align-items:flex-start;margin-top:6px">
				<div style="min-width:0;flex:1 1 320px">
					{#if renaming}
						<form class="row" onsubmit={rename}>
							<input class="rename" type="text" maxlength="80" aria-label="Nom du parcours" bind:value={name} />
							<button class="btn primary sm" type="submit" disabled={!name.trim()}>Enregistrer</button>
							<button class="btn sm" type="button" onclick={() => (renaming = false)}>Annuler</button>
						</form>
					{:else}
						<h1>{r.name}</h1>
					{/if}
					<div class="row muted" style="margin-top:8px">
						<span class="ic"><Icon name="route" size={16} />{f.distance(r.distance, units)} {f.distanceUnit(units)}</span>
						<span class="ic"
							><Icon name="mountain" size={16} />{r.gain === null
								? 'D+ indisponible'
								: `${f.elevation(r.gain, units)} ${f.elevationUnit(units)} D+ · ${f.elevation(r.loss, units)} ${f.elevationUnit(units)} D−`}</span
						>
						{#if r.is_loop}<span class="tag sky">Boucle</span>{/if}
					</div>
				</div>
				<div class="row">
					<PhonePill delivery={r.delivery} timezone={owner.timezone} label="le parcours" ontoggle={togglePhone} />
					<button class="iconbtn" type="button" data-tip="Renommer" aria-label="Renommer" onclick={() => ((name = r!.name), (renaming = true))}><Icon name="edit" /></button>
					<button class="iconbtn" type="button" data-tip="Créer le parcours inverse" aria-label="Créer le parcours inverse" onclick={reverse}><Icon name="swap" /></button>
					<a class="iconbtn" href={api.routeExportUrl(r.id)} download data-tip="Exporter en .gpx" aria-label="Exporter en .gpx"><Icon name="download" /></a>
					{#if app.profiles.length > 1}
						<button class="iconbtn" type="button" data-tip="Copier vers un profil" aria-label="Copier vers un profil" onclick={() => (dialog = 'copy')}><Icon name="users" /></button>
					{/if}
					<button class="iconbtn" type="button" data-tip="Supprimer" data-tip-pos="end" aria-label="Supprimer" onclick={() => (dialog = 'delete')}><Icon name="trash" /></button>
				</div>
			</div>
		</div>

		<div class="dgrid">
			<div class="col">
				<section class="card mapcard" aria-label="Tracé du parcours">
					<ActivityMap
						lat={pts.lat}
						lon={pts.lon}
						colors={pts.lat.map(() => theme.sky)}
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
						<h2>Profil d'altitude</h2>
						{#if ele}<span class="small muted">Survolez : le point suit sur la carte</span>{/if}
					</div>
					<div class="card-b">
						{#if ele}
							{#key `${app.dark}${units}`}
								<MetricChart
									{x}
									y={ele}
									inverted={false}
									color={theme.sky}
									format={(v) => String(Math.round(v))}
									unit={f.elevationUnit(units)}
									xUnit={f.distanceUnit(units)}
									blocks={null}
									relief={null}
									average={null}
									ink={theme.ink3}
									grid={theme.grid}
									surface={theme.surface}
									{cursor}
									sel={null}
									onhover={(i) => (cursor = i)}
									{tip}
									label="Altitude"
								/>
							{/key}
						{:else}
							<p class="muted" style="margin:0">
								Ce fichier ne contient pas d'altitude : dénivelé indisponible, et l'allure ajustée à la pente ne sera pas proposée sur ce parcours.
							</p>
						{/if}
					</div>
				</section>
			</div>
			<div class="col">
				<section class="card">
					<div class="card-h"><h2>Courses faites avec ce parcours</h2></div>
					<div class="card-b">
						{#each r.activities as a (a.id)}
							<a class="prow" href="/activities/{a.id}">
								<span class="grow"><b>{a.title}</b><br /><span class="small muted">{f.dateShort(a.start_time, owner.timezone)} · {f.duration(a.moving_time)}</span></span>
								<span class="chev"><Icon name="chev" size={16} /></span>
							</a>
						{:else}
							<p class="muted" style="margin:0">Pas encore couru. Envoyez-le sur le téléphone pour le suivre pendant une course.</p>
						{/each}
					</div>
				</section>
			</div>
		</div>
	</div>
{/if}

{#if dialog === 'delete' && r}
	<Modal title="Supprimer ce parcours ?" onclose={() => (dialog = null)}>
		<p class="muted" style="margin:0">« {r.name} » quitte vos parcours{r.on_phone ? ' et le téléphone à la prochaine synchro' : ''}. Les courses faites avec lui gardent son nom.</p>
		<div class="row" style="justify-content:flex-end">
			<button class="btn" type="button" onclick={() => (dialog = null)}>Annuler</button>
			<button class="btn danger" type="button" onclick={remove}><Icon name="trash" />Supprimer</button>
		</div>
	</Modal>
{:else if dialog === 'copy' && r}
	<CopyDialog
		title="Copier vers un profil"
		text="L'autre personne obtient son propre parcours : chacun peut supprimer le sien sans toucher à l'autre."
		from={r.profile_id}
		onconfirm={copyTo}
		onclose={() => (dialog = null)}
	/>
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
	.chev {
		display: inline-grid;
		transform: rotate(-90deg);
		color: var(--ink-3);
	}
</style>
