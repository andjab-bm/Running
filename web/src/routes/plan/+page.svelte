<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, type RouteSummary, type Workout, type WorkoutItem } from '#lib/api.ts';
	import { app, errorText } from '#lib/app.svelte.ts';
	import CopyDialog from '#lib/components/CopyDialog.svelte';
	import Icon from '#lib/components/Icon.svelte';
	import Modal from '#lib/components/Modal.svelte';
	import Outline from '#lib/components/Outline.svelte';
	import PhonePill from '#lib/components/PhonePill.svelte';
	import Trace from '#lib/components/Trace.svelte';
	import * as f from '#lib/format.ts';
	import { STARTERS, totals } from '#lib/workout.ts';

	const profile = $derived(app.active!);
	const units = $derived(profile.units);
	const tz = $derived(profile.timezone);
	// The tab lives in the address, so the route page can send back to the right one.
	const tab = $derived(page.url.searchParams.get('tab') === 'routes' ? 'routes' : 'workouts');
	const open = (t: string) => goto(t === 'routes' ? '/plan?tab=routes' : '/plan', { replace: true, reset: false });

	let workouts = $state<Workout[] | null>(null);
	let routes = $state<RouteSummary[] | null>(null);
	let lastSync = $state<number | null>(null);
	let error = $state('');
	let deleting = $state<Workout | null>(null);
	let copying = $state<Workout | null>(null);
	let picker = $state<HTMLInputElement | null>(null);
	let importing = $state(false);
	let failures = $state<{ file: string; reason: string }[]>([]);

	const fail = (e: unknown) => (error = errorText(e));
	function load() {
		api.workouts(profile.id).then((r) => ((workouts = r.items), (lastSync = r.last_phone_sync), (error = '')), fail);
		api.routes(profile.id).then((r) => ((routes = r.items), (lastSync = r.last_phone_sync)), fail);
	}
	$effect(() => {
		void app.revision;
		load();
	});

	async function act(run: () => Promise<unknown>, done: string) {
		try {
			await run();
			app.notify(done);
			load();
		} catch (e) {
			app.notify(errorText(e));
		}
	}
	const togglePhone = (w: Workout) =>
		act(() => api.updateWorkout(w.id, { on_phone: !w.on_phone }), w.on_phone ? `« ${w.name} » sera retiré du téléphone à la prochaine synchro` : `« ${w.name} » partira à la prochaine synchro du téléphone`);
	const toggleRoute = (r: RouteSummary) =>
		act(() => api.updateRoute(r.id, { on_phone: !r.on_phone }), r.on_phone ? `« ${r.name} » sera retiré du téléphone à la prochaine synchro` : `« ${r.name} » partira à la prochaine synchro du téléphone`);
	const duplicate = (w: Workout) => act(() => api.copyWorkout(w.id, profile.id, `${w.name} (copie)`.slice(0, 80)), `« ${w.name} » dupliquée`);
	const addStarter = (s: { name: string; note: string; steps: WorkoutItem[] }) =>
		act(() => api.createWorkout(profile.id, { name: s.name, note: s.note, steps: s.steps }), `« ${s.name} » ajoutée à vos séances`);
	async function remove() {
		const w = deleting!;
		deleting = null;
		await act(() => api.deleteWorkout(w.id), `« ${w.name} » supprimée`);
	}
	async function copyTo(target: number) {
		const w = copying!;
		copying = null;
		const who = app.profiles.find((p) => p.id === target)?.name ?? '';
		await act(() => api.copyWorkout(w.id, target), `« ${w.name} » copiée dans les séances de ${who}`);
	}

	async function upload(files: File[]) {
		if (!files.length) return;
		importing = true;
		failures = [];
		try {
			const results = await api.importRoutes(profile.id, files);
			const ok = results.filter((r) => r.status === 'imported');
			failures = results.filter((r) => r.status === 'failed').map((r) => ({ file: r.file, reason: r.reason ?? 'Fichier illisible.' }));
			if (ok.length === 1 && !failures.length) return goto(`/plan/routes/${ok[0].route_id}`);
			if (ok.length) app.notify(`${ok.length} parcours ajouté${ok.length > 1 ? 's' : ''}`);
			load();
		} catch (e) {
			app.notify(errorText(e));
		} finally {
			importing = false;
			if (picker) picker.value = '';
		}
	}
</script>

<div class="view">
	<div class="spread">
		<div>
			<div class="eyebrow">Profil de {profile.name}</div>
			<h1>Planifier</h1>
			<p class="muted" style="margin:6px 0 0">
				Préparez ici, courez avec le téléphone.
				{#if lastSync}Dernière synchro du téléphone : <b>{f.since(lastSync, tz)}</b>.{:else}Le téléphone ne s'est pas encore synchronisé avec ce profil.{/if}
			</p>
		</div>
		{#if tab === 'workouts'}
			<a class="btn primary" href="/plan/workouts/new"><Icon name="plus" />Nouvelle séance</a>
		{:else}
			<button class="btn primary" type="button" disabled={importing} onclick={() => picker?.click()}><Icon name="upload" />{importing ? 'Import…' : 'Importer un .gpx'}</button>
		{/if}
		<input class="hidden" type="file" accept=".gpx" multiple bind:this={picker} onchange={(e) => upload([...(e.currentTarget.files ?? [])])} />
	</div>

	<div class="tabs" role="group" aria-label="Rubriques de la planification">
		<button type="button" aria-pressed={tab === 'workouts'} onclick={() => open('workouts')}><Icon name="timer" size={17} />Séances</button>
		<button type="button" aria-pressed={tab === 'routes'} onclick={() => open('routes')}><Icon name="route" size={17} />Parcours</button>
	</div>

	{#if error}
		<div class="note warn"><Icon name="x" /><span>{error}</span></div>
	{/if}

	{#if tab === 'workouts'}
		{#if workouts === null}
			<div class="skeleton" style="min-height:220px"></div>
		{:else if workouts.length}
			<div class="libs">
				{#each workouts as w (w.id)}
					{@const t = totals(w.steps, units)}
					<div class="lib">
						<div class="row" style="justify-content:space-between;flex-wrap:nowrap;align-items:flex-start">
							<h3 class="grow"><a href="/plan/workouts/{w.id}">{w.name}</a></h3>
							<span class="row" style="flex-wrap:nowrap;gap:4px">
								<a class="iconbtn flat sm" href="/plan/workouts/{w.id}" data-tip="Modifier" aria-label="Modifier {w.name}"><Icon name="edit" size={16} /></a>
								<button class="iconbtn flat sm" type="button" data-tip="Dupliquer" aria-label="Dupliquer {w.name}" onclick={() => duplicate(w)}><Icon name="copy" size={16} /></button>
								{#if app.profiles.length > 1}
									<button class="iconbtn flat sm" type="button" data-tip="Copier vers un profil" aria-label="Copier {w.name} vers un profil" onclick={() => (copying = w)}><Icon name="users" size={16} /></button>
								{/if}
								<button class="iconbtn flat sm" type="button" data-tip="Supprimer" data-tip-pos="end" aria-label="Supprimer {w.name}" onclick={() => (deleting = w)}><Icon name="trash" size={16} /></button>
							</span>
						</div>
						<Outline items={w.steps} {units} />
						<div class="mini"><span>Durée <b>{t.time}</b></span><span>Distance <b>{t.distance}</b></span><span><b>{t.steps}</b> étape{t.steps > 1 ? 's' : ''}</span></div>
						{#if w.note}<p class="small muted" style="margin:0">{w.note}</p>{/if}
						<div><PhonePill delivery={w.delivery} timezone={tz} label="« {w.name} »" ontoggle={() => togglePhone(w)} /></div>
					</div>
				{/each}
			</div>
		{:else}
			<div class="emptybox">
				<div class="big"><Icon name="timer" size={28} /></div>
				<h2>Aucune séance</h2>
				<p class="muted">Créez votre première séance, ou partez d'une des séances pour débuter ci-dessous.</p>
			</div>
		{/if}

		<section class="card">
			<div class="card-h">
				<h2>Pour débuter</h2>
				<span class="small muted">Des séances prêtes à l'emploi, à ajouter puis à adapter</span>
			</div>
			<div class="card-b">
				{#each STARTERS as s (s.name)}
					<div class="prow starter">
						<div class="grow"><b>{s.name}</b><Outline items={s.steps} {units} /></div>
						<button class="btn sm" type="button" aria-label="Ajouter {s.name}" onclick={() => addStarter(s)}><Icon name="plus" size={16} />Ajouter</button>
					</div>
				{/each}
				<div class="lg" style="margin-top:12px">
					<span><i style="background:var(--c-mint)"></i>Échauffement</span>
					<span><i style="background:var(--accent)"></i>Effort</span>
					<span><i style="background:var(--c-sky)"></i>Récupération</span>
					<span><i style="background:var(--c-peach)"></i>Retour au calme</span>
				</div>
			</div>
		</section>
	{:else}
		{#if failures.length}
			<div class="note warn">
				<Icon name="x" />
				<span>{#each failures as x (x.file)}<b>{x.file}</b> : {x.reason}<br />{/each}</span>
			</div>
		{/if}
		{#if routes === null}
			<div class="skeleton" style="min-height:220px"></div>
		{:else if routes.length}
			<div class="libs">
				{#each routes as r (r.id)}
					<div class="lib">
						<a class="thumb" href="/plan/routes/{r.id}" aria-label="Ouvrir {r.name}"><Trace points={r.preview} color="sky" /></a>
						<h3><a href="/plan/routes/{r.id}">{r.name}</a></h3>
						<div class="mini">
							<span><b>{f.distance(r.distance, units, 1)}</b> {f.distanceUnit(units)}</span>
							<span>D+ <b>{r.gain === null ? '—' : `${f.elevation(r.gain, units)} ${f.elevationUnit(units)}`}</b></span>
							{#if r.is_loop}<span class="tag sky">Boucle</span>{/if}
						</div>
						<div><PhonePill delivery={r.delivery} timezone={tz} label="« {r.name} »" ontoggle={() => toggleRoute(r)} /></div>
					</div>
				{/each}
			</div>
		{:else}
			<div class="emptybox">
				<div class="big"><Icon name="route" size={28} /></div>
				<h2>Aucun parcours</h2>
				<p class="muted">Importez un fichier .gpx préparé sur un autre site, ou créez un parcours depuis une de vos courses.</p>
				<button class="btn primary" type="button" onclick={() => picker?.click()}><Icon name="upload" />Importer un .gpx</button>
			</div>
		{/if}
		<div class="note"><Icon name="info" /><span>Un parcours peut aussi être créé depuis une de vos courses : bouton « Créer un parcours » sur la page de l'activité.</span></div>
	{/if}
</div>

{#if deleting}
	<Modal title="Supprimer cette séance ?" onclose={() => (deleting = null)}>
		<p class="muted" style="margin:0">« {deleting.name} » quitte votre bibliothèque{deleting.on_phone ? ' et le téléphone à la prochaine synchro' : ''}. Les courses déjà faites avec elle gardent leurs étapes.</p>
		<div class="row" style="justify-content:flex-end">
			<button class="btn" type="button" onclick={() => (deleting = null)}>Annuler</button>
			<button class="btn danger" type="button" onclick={remove}><Icon name="trash" />Supprimer</button>
		</div>
	</Modal>
{/if}
{#if copying}
	<CopyDialog
		title="Copier vers un profil"
		text="L'autre personne obtient sa propre séance : les changements de l'une ne touchent pas l'autre. Une cible de zone cardiaque suit les zones de son profil."
		from={profile.id}
		onconfirm={copyTo}
		onclose={() => (copying = null)}
	/>
{/if}

<style>
	.hidden {
		display: none;
	}
	.lib h3 a:hover {
		color: var(--accent);
	}
	.starter .grow {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
</style>
