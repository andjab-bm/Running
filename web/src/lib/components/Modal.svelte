<script lang="ts">
	import type { Snippet } from 'svelte';

	let { title, onclose, children }: { title: string; onclose: () => void; children: Snippet } = $props();

	let sheet = $state<HTMLElement | null>(null);

	$effect(() => {
		// Move focus into the dialog and give it back on close.
		const previous = document.activeElement as HTMLElement | null;
		sheet?.querySelector<HTMLElement>('input, button')?.focus();
		return () => previous?.focus?.();
	});

	function keydown(e: KeyboardEvent) {
		if (e.key === 'Escape') onclose();
	}
</script>

<svelte:window onkeydown={keydown} />

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="overlay" onclick={(e) => e.target === e.currentTarget && onclose()}>
	<div class="sheet" role="dialog" aria-modal="true" aria-label={title} bind:this={sheet}>
		<h2>{title}</h2>
		{@render children()}
	</div>
</div>

<style>
	.overlay {
		position: fixed;
		inset: 0;
		background: rgba(20, 16, 40, 0.45);
		display: grid;
		place-items: center;
		z-index: 80;
		padding: 16px;
	}
	.sheet {
		background: var(--surface);
		border-radius: var(--r-xl);
		padding: 24px;
		width: min(440px, 100%);
		max-height: calc(100vh - 32px);
		overflow-y: auto;
		box-shadow: var(--pop);
		display: flex;
		flex-direction: column;
		gap: 16px;
	}
</style>
