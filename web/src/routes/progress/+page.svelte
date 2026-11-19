<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { app } from '#lib/app.svelte.ts';
	import Icon, { type IconName } from '#lib/components/Icon.svelte';
	import LoadTab from './LoadTab.svelte';
	import RecordsTab from './RecordsTab.svelte';
	import StatsTab from './StatsTab.svelte';

	const TABS: { key: string; label: string; icon: IconName }[] = [
		{ key: 'stats', label: 'Statistiques', icon: 'bars' },
		{ key: 'records', label: 'Records', icon: 'trophy' },
		{ key: 'load', label: 'Charge', icon: 'zap' }
	];

	// The tab lives in the address, so a reload or a shared link opens the same one.
	const tab = $derived(TABS.find((t) => t.key === page.url.searchParams.get('tab'))?.key ?? 'stats');
	const open = (key: string) => goto(key === 'stats' ? '/progress' : `/progress?tab=${key}`, { replace: true, reset: false });
</script>

<div class="view">
	<div>
		<div class="eyebrow">Profil de {app.active!.name}</div>
		<h1>Progression</h1>
	</div>

	<div class="tabs" role="group" aria-label="Rubriques de la progression">
		{#each TABS as t (t.key)}
			<button type="button" aria-pressed={tab === t.key} onclick={() => open(t.key)}><Icon name={t.icon} size={17} />{t.label}</button>
		{/each}
	</div>

	{#if tab === 'stats'}
		<StatsTab />
	{:else if tab === 'records'}
		<RecordsTab />
	{:else}
		<LoadTab />
	{/if}
</div>

<style>
	.tabs {
		display: inline-flex;
		background: var(--surface);
		border-radius: 999px;
		padding: 5px;
		gap: 4px;
		box-shadow: var(--shadow);
		align-self: flex-start;
		max-width: 100%;
		overflow-x: auto;
		scrollbar-width: none;
	}
	.tabs button {
		padding: 9px 18px;
		border-radius: 999px;
		color: var(--ink-2);
		font-weight: 600;
		display: inline-flex;
		gap: 8px;
		align-items: center;
		white-space: nowrap;
		transition:
			background 0.15s,
			color 0.15s;
	}
	.tabs button:hover {
		background: var(--surface-2);
		color: var(--ink);
	}
	.tabs button[aria-pressed='true'] {
		background: var(--accent);
		color: var(--on-accent);
	}
</style>
