<script lang="ts">
	// Pick another profile of the household to receive an independent copy.
	import { app } from '#lib/app.svelte.ts';
	import Icon from './Icon.svelte';
	import Modal from './Modal.svelte';

	let { title, text, from, onconfirm, onclose }: { title: string; text: string; from: number; onconfirm: (profileId: number) => void; onclose: () => void } = $props();
	const others = $derived(app.profiles.filter((p) => p.id !== from));
	let target = $state<number | null>(null);
	$effect(() => {
		if (target === null && others.length) target = others[0].id;
	});
</script>

<Modal {title} {onclose}>
	<p class="muted" style="margin:0">{text}</p>
	<div>
		{#each others as p (p.id)}
			<button class="pick" type="button" aria-pressed={target === p.id} onclick={() => (target = p.id)}>
				<span class="avatar" style:background={p.color}>{p.name.charAt(0).toUpperCase()}</span><span class="grow">{p.name}</span>
				{#if target === p.id}<Icon name="check" />{/if}
			</button>
		{/each}
	</div>
	<div class="row" style="justify-content:flex-end">
		<button class="btn" type="button" onclick={onclose}>Annuler</button>
		<button class="btn primary" type="button" disabled={!target} onclick={() => onconfirm(target!)}>Copier</button>
	</div>
</Modal>
