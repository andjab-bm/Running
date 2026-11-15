<script lang="ts">
	import { api } from '#lib/api.ts';
	import { app, errorText } from '#lib/app.svelte.ts';
	import Icon from './Icon.svelte';
	import Modal from './Modal.svelte';

	let { onclose }: { onclose: () => void } = $props();

	let adding = $state(false);
	let name = $state('');
	let error = $state('');
	let busy = $state(false);

	function choose(id: number) {
		app.setActive(id);
		app.notify(`Profil actif : ${app.active?.name}`);
		onclose();
	}

	async function create(e: SubmitEvent) {
		e.preventDefault();
		if (!name.trim()) return;
		busy = true;
		try {
			const p = await api.createProfile(name);
			await app.refreshProfiles();
			app.setActive(p.id);
			app.notify(`Profil créé : ${p.name}`);
			onclose();
		} catch (err) {
			error = errorText(err);
		} finally {
			busy = false;
		}
	}
</script>

<Modal title={adding ? 'Nouveau profil' : 'Changer de profil'} {onclose}>
	{#if adding}
		<form onsubmit={create} style="display:contents">
			<div class="field" style="margin:0">
				<label for="np-name">Prénom</label>
				<input id="np-name" type="text" maxlength="24" placeholder="Par exemple : Sam" bind:value={name} />
				{#if error}<span class="err">{error}</span>{/if}
			</div>
			<div class="row" style="justify-content:flex-end">
				<button class="btn" type="button" onclick={() => (adding = false)}>Retour</button>
				<button class="btn primary" type="submit" disabled={busy || !name.trim()}>Créer</button>
			</div>
		</form>
	{:else}
		<div>
			{#each app.profiles as p (p.id)}
				<button class="pick" type="button" aria-pressed={p.id === app.activeId} onclick={() => choose(p.id)}>
					<span class="avatar" style:background={p.color}>{p.name.charAt(0).toUpperCase()}</span>
					<span class="grow">{p.name}<br /><small class="muted">{p.activity_count} sortie{p.activity_count > 1 ? 's' : ''}</small></span>
					{#if p.id === app.activeId}<Icon name="check" />{/if}
				</button>
			{/each}
		</div>
		<div class="row" style="justify-content:space-between">
			<button class="btn" type="button" onclick={() => (adding = true)}><Icon name="plus" />Nouveau profil</button>
			<button class="btn" type="button" onclick={onclose}>Fermer</button>
		</div>
	{/if}
</Modal>

<style>
	small {
		font-weight: 500;
	}
	.err {
		color: var(--i-rose);
		font-size: 13px;
		font-weight: 600;
	}
</style>
