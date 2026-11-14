<script lang="ts">
	// Placeholder shell: it proves the app loads and reaches the API.
	// The real navigation and screens arrive with task group 7.
	type Health = { status: string; version: string; database: string };

	let health = $state<Health | null>(null);
	let failed = $state(false);

	$effect(() => {
		fetch('/api/v1/health')
			.then((r) => r.json())
			.then((h: Health) => (health = h))
			.catch(() => (failed = true));
	});
</script>

<main>
	<div class="mark" aria-hidden="true">
		<svg width="26" height="26" viewBox="0 0 24 24"><path d="M3 17c4 0 4-10 9-10s4 10 9 10" /></svg>
	</div>
	<h1>Running</h1>
	<p>
		{#if health}
			Serveur opérationnel · version {health.version}
		{:else if failed}
			Le serveur ne répond pas. Vérifiez qu'il est démarré.
		{:else}
			Connexion au serveur…
		{/if}
	</p>
</main>

<style>
	main {
		min-height: 100vh;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 12px;
		padding: 24px;
		text-align: center;
	}
	.mark {
		width: 56px;
		height: 56px;
		border-radius: 18px;
		background: var(--accent);
		color: var(--on-accent);
		display: grid;
		place-items: center;
	}
	.mark svg {
		fill: none;
		stroke: currentColor;
		stroke-width: 2.6;
		stroke-linecap: round;
	}
	p {
		margin: 0;
		color: var(--ink-2);
	}
</style>
