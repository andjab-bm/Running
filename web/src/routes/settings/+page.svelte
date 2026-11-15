<script lang="ts">
	import { api, type Profile } from '#lib/api.ts';
	import { app, errorText, type Theme } from '#lib/app.svelte.ts';
	import Icon from '#lib/components/Icon.svelte';
	import Modal from '#lib/components/Modal.svelte';
	import ProfileSheet from '#lib/components/ProfileSheet.svelte';
	import { ZONE_NAMES } from '#lib/format.ts';

	const SWATCHES = ['#B9AFFF', '#F7AFC3', '#8FE0BB', '#FFC39C', '#9CCBF7', '#EFD877'];
	const THEMES: [Theme, string, string][] = [
		['light', 'Clair', '#F4F3F9'],
		['dark', 'Sombre', '#121118'],
		['system', 'Système', 'linear-gradient(105deg,#F4F3F9 50%,#121118 50%)']
	];
	const profile = $derived(app.active!);

	// Local drafts, so typing does not fight with the saved values.
	let name = $state(app.active!.name);
	let hrMax = $state(app.active!.hr_max ?? app.active!.zones.default_hr_max);
	let hrRest = $state<number | null>(app.active!.hr_rest);
	let custom = $state(app.active!.zones.custom);
	let bounds = $state([...app.active!.zones.bounds]);
	let error = $state('');
	let adding = $state(false);
	let doomed = $state<Profile | null>(null);

	const zones = $derived(custom ? bounds : [0.5, 0.6, 0.7, 0.8, 0.9, 1].map((k) => Math.round(k * hrMax)));
	const timezones: string[] = (() => {
		try {
			return Intl.supportedValuesOf('timeZone');
		} catch {
			return ['Europe/Paris', 'UTC'];
		}
	})();

	async function save(changes: Parameters<typeof api.updateProfile>[1], done?: string) {
		try {
			const p = await api.updateProfile(profile.id, changes);
			app.patchProfile(p);
			bounds = [...p.zones.bounds];
			custom = p.zones.custom;
			error = '';
			if (done) app.notify(done);
			// Zone times and splits of existing activities were recomputed by the server.
			app.changed();
		} catch (e) {
			error = errorText(e);
			name = profile.name;
		}
	}

	async function remove() {
		if (!doomed) return;
		try {
			await api.deleteProfile(doomed.id);
			const gone = doomed.name;
			doomed = null;
			await app.refreshProfiles();
			app.notify(`Profil de ${gone} supprimé`);
		} catch (e) {
			error = errorText(e);
			doomed = null;
		}
	}
</script>

<div class="view">
	<div>
		<div class="eyebrow">Profil de {profile.name}</div>
		<h1>Réglages</h1>
	</div>

	{#if error}
		<div class="note warn"><Icon name="x" /><span>{error}</span></div>
	{/if}

	<div class="sgrid">
		<section class="card">
			<div class="card-h"><h2>Profil</h2></div>
			<div class="card-b">
				<div class="field">
					<label for="s-name">Prénom</label>
					<input id="s-name" type="text" maxlength="24" bind:value={name} onchange={() => name.trim() && name.trim() !== profile.name && save({ name }, 'Prénom enregistré')} />
				</div>
				<div class="flabel" style="margin-bottom:8px">Couleur</div>
				<div class="swatches">
					{#each SWATCHES as c (c)}
						<button type="button" style:background={c} aria-pressed={profile.color === c} aria-label="Couleur {c}" onclick={() => save({ color: c })}></button>
					{/each}
				</div>
				<div class="flabel" style="margin:18px 0 8px">Unités</div>
				<div class="seg" role="group" aria-label="Unités">
					<button type="button" aria-pressed={profile.units === 'metric'} onclick={() => save({ units: 'metric' }, 'Distances en kilomètres')}>Kilomètres</button>
					<button type="button" aria-pressed={profile.units === 'imperial'} onclick={() => save({ units: 'imperial' }, 'Distances en miles')}>Miles</button>
				</div>
				<div class="field" style="margin:18px 0 0">
					<label for="s-tz">Fuseau horaire</label>
					<select id="s-tz" value={profile.timezone ?? ''} onchange={(e) => save({ timezone: e.currentTarget.value || null }, 'Fuseau enregistré')}>
						<option value="">Celui de ce navigateur</option>
						{#each timezones as z (z)}<option value={z}>{z}</option>{/each}
					</select>
				</div>
			</div>
		</section>

		<section class="card">
			<div class="card-h">
				<h2>Fréquence cardiaque</h2>
				<span class="tag rose"><Icon name="heart" size={13} />Zones</span>
			</div>
			<div class="card-b">
				{#if profile.zones.uses_default_hr_max}
					<div class="note" style="margin-bottom:14px"><Icon name="heart" /><span>Les zones reposent sur une valeur par défaut de {profile.zones.default_hr_max} bpm. Indiquez votre fréquence maximale pour des zones justes.</span></div>
				{/if}
				<div class="row" style="align-items:baseline;gap:8px">
					<span class="hrbig">{hrMax}</span><span class="muted">bpm max</span>
				</div>
				<input type="range" min="100" max="230" bind:value={hrMax} aria-label="Fréquence cardiaque maximale" onchange={() => save({ hr_max: hrMax }, 'Zones recalculées')} />
				<div class="field" style="margin:14px 0 0">
					<label for="s-rest">Fréquence au repos (facultatif)</label>
					<input id="s-rest" type="number" min="25" max="120" bind:value={hrRest} onchange={() => save({ hr_rest: hrRest || null })} />
				</div>

				<div class="zprev">
					{#each [4, 3, 2, 1, 0] as i (i)}
						<span class="num zl"><span class="dot" style:background="var(--z{i + 1})"></span>Z{i + 1}</span>
						<span class="muted zn">{ZONE_NAMES[i]}</span>
						{#if custom}
							<span class="zin">
								<input type="number" aria-label="Zone {i + 1}, borne basse" bind:value={bounds[i]} />–<input type="number" aria-label="Zone {i + 1}, borne haute" bind:value={bounds[i + 1]} />
							</span>
						{:else}
							<span class="num zr">{zones[i]}–{zones[i + 1]} bpm</span>
						{/if}
					{/each}
				</div>
				<div class="row" style="margin-top:14px">
					{#if custom}
						<button class="btn primary sm" type="button" onclick={() => save({ zone_bounds: bounds }, 'Zones personnalisées enregistrées')}>Enregistrer les zones</button>
						<button class="btn sm" type="button" onclick={() => save({ zone_bounds: null }, 'Zones par défaut rétablies')}>Rétablir les zones par défaut</button>
					{:else}
						<button class="btn sm" type="button" onclick={() => (custom = true)}><Icon name="edit" size={15} />Personnaliser les zones</button>
					{/if}
				</div>
				<p class="muted small">Les zones et l'effort relatif de toutes les sorties du profil sont recalculés quand ces valeurs changent.</p>
			</div>
		</section>

		<section class="card">
			<div class="card-h"><h2>Apparence</h2></div>
			<div class="card-b">
				<div class="themes">
					{#each THEMES as [value, label, bg] (value)}
						<button type="button" aria-pressed={app.theme === value} onclick={() => app.setTheme(value)}>
							<span class="pv" style:background={bg}><i style="height:40%;background:#B9AFFF"></i><i style="height:75%;background:#8FE0BB"></i><i style="height:55%;background:#FFC39C"></i><i style="height:90%;background:#F7AFC3"></i></span>
							{label}
						</button>
					{/each}
				</div>
				<div class="flabel" style="margin:18px 0 8px">Fond de carte</div>
				<button class="switch" type="button" aria-pressed={app.basemap} onclick={() => app.setBasemap(!app.basemap)}>
					<span class="tr"></span>Afficher les rues, parcs et cours d'eau
				</button>
				<p class="muted small">Désactivé, ce navigateur n'envoie aucune requête au fournisseur de tuiles : le tracé s'affiche sur fond uni.</p>
			</div>
		</section>

		<section class="card">
			<div class="card-h">
				<h2>Profils du foyer</h2>
				<button class="iconbtn flat" type="button" aria-label="Ajouter un profil" data-tip="Ajouter un profil" data-tip-pos="end" onclick={() => (adding = true)}><Icon name="plus" /></button>
			</div>
			<div class="card-b">
				{#each app.profiles as p (p.id)}
					<div class="prow">
						<span class="avatar" style:background={p.color}>{p.name.charAt(0).toUpperCase()}</span>
						<div class="grow">
							<b>{p.name}</b>
							<div class="muted" style="font-size:13px">{p.activity_count} sortie{p.activity_count > 1 ? 's' : ''} · FC max {p.hr_max ?? `${p.zones.default_hr_max} (défaut)`}</div>
						</div>
						{#if p.id === app.activeId}
							<span class="tag lav">Actif</span>
						{:else}
							<button class="btn sm" type="button" onclick={() => app.setActive(p.id)}>Activer</button>
						{/if}
						{#if app.profiles.length > 1}
							<button class="iconbtn flat sm" type="button" aria-label="Supprimer le profil de {p.name}" data-tip="Supprimer ce profil" data-tip-pos="end" onclick={() => (doomed = p)}><Icon name="trash" size={16} /></button>
						{/if}
					</div>
				{/each}
				<div class="note" style="margin-top:14px"><Icon name="shield" /><span>Pas de mot de passe : toute personne connectée à votre réseau domestique peut ouvrir l'application et changer de profil.</span></div>
			</div>
		</section>
	</div>
</div>

{#if adding}<ProfileSheet onclose={() => (adding = false)} />{/if}
{#if doomed}
	<Modal title="Supprimer le profil de {doomed.name} ?" onclose={() => (doomed = null)}>
		<p class="muted" style="margin:0">
			{doomed.activity_count
				? `${doomed.activity_count} sortie${doomed.activity_count > 1 ? 's seront définitivement supprimées' : ' sera définitivement supprimée'} avec ce profil.`
				: 'Ce profil ne contient aucune sortie.'}
			Les copies déjà faites vers d'autres profils sont conservées.
		</p>
		<div class="row" style="justify-content:flex-end">
			<button class="btn" type="button" onclick={() => (doomed = null)}>Annuler</button>
			<button class="btn danger" type="button" onclick={remove}><Icon name="trash" />Supprimer</button>
		</div>
	</Modal>
{/if}

<style>
	.sgrid {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 20px;
		align-items: start;
	}
	.swatches {
		display: flex;
		gap: 10px;
		flex-wrap: wrap;
	}
	.swatches button {
		width: 34px;
		height: 34px;
		border-radius: 50%;
		transition: transform 0.15s;
	}
	.swatches button:hover {
		transform: scale(1.12);
	}
	.swatches button[aria-pressed='true'] {
		box-shadow:
			0 0 0 3px var(--surface),
			0 0 0 5px var(--ink);
	}
	.hrbig {
		font: 600 44px/1 var(--font-display);
		font-variant-numeric: tabular-nums;
	}
	.zprev {
		display: grid;
		grid-template-columns: auto minmax(0, 1fr) auto;
		gap: 8px 12px;
		align-items: center;
		margin-top: 16px;
	}
	.zl {
		font-weight: 600;
		display: flex;
		align-items: center;
	}
	.dot {
		width: 10px;
		height: 10px;
		border-radius: 50%;
		margin-right: 6px;
	}
	.zn {
		font-size: 13.5px;
	}
	.zr {
		font-size: 15px;
	}
	.zin {
		display: flex;
		align-items: center;
		gap: 4px;
	}
	.zin input {
		width: 68px;
		padding: 6px 8px;
		text-align: center;
	}
	.small {
		font-size: 13.5px;
		margin: 12px 0 0;
	}
	.themes {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 12px;
	}
	.themes button {
		border-radius: var(--r-lg);
		padding: 10px;
		background: var(--surface-2);
		display: flex;
		flex-direction: column;
		gap: 8px;
		align-items: stretch;
		font-weight: 600;
		transition: transform 0.15s;
	}
	.themes button:hover {
		transform: translateY(-2px);
	}
	.themes button[aria-pressed='true'] {
		background: var(--accent-soft);
		color: var(--accent);
	}
	.pv {
		height: 58px;
		border-radius: 12px;
		display: flex;
		align-items: flex-end;
		gap: 5px;
		padding: 9px;
	}
	.pv i {
		flex: 1;
		border-radius: 4px;
	}
	.prow {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 10px;
		border-radius: var(--r-md);
	}
	.prow:hover {
		background: var(--surface-2);
	}
	@media (max-width: 900px) {
		.sgrid {
			grid-template-columns: minmax(0, 1fr);
		}
	}
</style>
