<script lang="ts">
	import '../app.css';
	import { untrack } from 'svelte';
	import favicon from '#lib/assets/favicon.svg';
	import { page } from '$app/state';
	import { api } from '#lib/api.ts';
	import { app, errorText, type Theme } from '#lib/app.svelte.ts';
	import Icon, { type IconName } from '#lib/components/Icon.svelte';
	import ProfileSheet from '#lib/components/ProfileSheet.svelte';
	import type { LayoutProps } from './$types';

	let { children }: LayoutProps = $props();

	const NAV: { href: string; label: string; icon: IconName }[] = [
		{ href: '/', label: 'Activités', icon: 'list' },
		{ href: '/import', label: 'Importer', icon: 'upload' },
		{ href: '/settings', label: 'Réglages', icon: 'sliders' }
	];
	const THEMES: { value: Theme; label: string; icon: IconName }[] = [
		{ value: 'light', label: 'Thème clair', icon: 'sun' },
		{ value: 'dark', label: 'Thème sombre', icon: 'moon' },
		{ value: 'system', label: 'Suivre le système', icon: 'monitor' }
	];

	let sheet = $state(false);
	let firstName = $state('');
	let firstError = $state('');
	let toastVisible = $state(false);

	// Load once: the call reads state it also sets, which must not re-trigger it.
	$effect(() => {
		untrack(() => app.load());
	});

	$effect(() => {
		if (!app.toast) return;
		toastVisible = true;
		const timer = setTimeout(() => (toastVisible = false), 2600);
		return () => clearTimeout(timer);
	});

	// The activity page belongs to the "Activités" section.
	const current = $derived(page.url.pathname.startsWith('/activities') ? '/' : page.url.pathname);

	async function createFirst(e: SubmitEvent) {
		e.preventDefault();
		try {
			const p = await api.createProfile(firstName);
			await app.refreshProfiles();
			app.setActive(p.id);
		} catch (err) {
			firstError = errorText(err);
		}
	}
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
	<title>Running</title>
</svelte:head>

{#snippet themeSeg()}
	<div class="themeseg" role="group" aria-label="Thème">
		{#each THEMES as t (t.value)}
			<button type="button" aria-pressed={app.theme === t.value} aria-label={t.label} data-tip={t.label} data-tip-pos="start" onclick={() => app.setTheme(t.value)}>
				<Icon name={t.icon} size={17} />
			</button>
		{/each}
	</div>
{/snippet}

{#snippet mark(size: number)}
	<span class="brand-mark" style:width="{size}px" style:height="{size}px"><Icon name="logo" size={size * 0.55} stroke={2.6} /></span>
{/snippet}

{#if !app.ready}
	<div class="center"><p class="muted">Chargement…</p></div>
{:else if app.loadError}
	<div class="center">
		<div class="emptybox">
			<div class="big"><Icon name="x" size={28} /></div>
			<h2>Le serveur ne répond pas</h2>
			<p class="muted">{app.loadError}</p>
			<button class="btn primary" type="button" onclick={() => app.load()}>Réessayer</button>
		</div>
	</div>
{:else if app.profiles.length === 0}
	<!-- First start: a profile must exist before anything else. -->
	<div class="center">
		<form class="emptybox welcome" onsubmit={createFirst}>
			{@render mark(56)}
			<h1>Bienvenue</h1>
			<p class="muted">Créez votre profil pour commencer. Pas de mot de passe : l'application reste sur votre réseau domestique.</p>
			<div class="field" style="margin:0;width:100%;text-align:left">
				<label for="first-name">Votre prénom</label>
				<input id="first-name" type="text" maxlength="24" bind:value={firstName} />
				{#if firstError}<span class="err">{firstError}</span>{/if}
			</div>
			<button class="btn primary" type="submit" disabled={!firstName.trim()}>Créer mon profil</button>
		</form>
	</div>
{:else}
	<div class="app">
		<aside class="rail">
			<div class="brand">{@render mark(36)}Running</div>
			<nav aria-label="Navigation principale">
				{#each NAV as n (n.href)}
					<a class="navlink" href={n.href} aria-current={current === n.href ? 'page' : undefined}><Icon name={n.icon} size={20} />{n.label}</a>
				{/each}
			</nav>
			<div class="grow"></div>
			{@render themeSeg()}
			<button class="who" type="button" onclick={() => (sheet = true)}>
				<span class="avatar" style:background={app.active?.color}>{app.active?.name.charAt(0).toUpperCase()}</span>
				<span><b>{app.active?.name}</b><small>Changer de profil</small></span>
			</button>
		</aside>
		<div class="content">
			<header class="topbar">
				<div class="brand">{@render mark(32)}Running</div>
				{@render themeSeg()}
				<button class="who" type="button" aria-label="Changer de profil" onclick={() => (sheet = true)}>
					<span class="avatar" style:background={app.active?.color}>{app.active?.name.charAt(0).toUpperCase()}</span>
				</button>
			</header>
			<main>
				<!-- Re-create the page when the active profile changes: everything shown belongs to it. -->
				{#key app.activeId}
					{@render children()}
				{/key}
			</main>
		</div>
	</div>
{/if}

{#if sheet}<ProfileSheet onclose={() => (sheet = false)} />{/if}
<div class="toast" class:on={toastVisible} role="status">{app.toast?.text}</div>

<style>
	.center {
		min-height: 100vh;
		display: grid;
		place-items: center;
		padding: 24px;
	}
	.welcome {
		width: min(420px, 100%);
	}
	.err {
		color: var(--i-rose);
		font-size: 13px;
		font-weight: 600;
	}
	.app {
		display: grid;
		grid-template-columns: 236px minmax(0, 1fr);
		min-height: 100vh;
	}
	.rail {
		position: sticky;
		top: 0;
		height: 100vh;
		padding: 22px 16px;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	nav {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.brand {
		font-family: var(--font-display);
		font-weight: 700;
		font-size: 23px;
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 4px 10px 20px;
	}
	.brand-mark {
		border-radius: 32%;
		background: var(--accent);
		color: var(--on-accent);
		display: grid;
		place-items: center;
		flex: none;
	}
	.navlink {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 11px 12px;
		border-radius: var(--r-md);
		color: var(--ink-2);
		font-weight: 600;
		transition:
			background 0.15s,
			color 0.15s;
	}
	.navlink:hover {
		background: var(--surface-2);
		color: var(--ink);
	}
	.navlink[aria-current='page'] {
		background: var(--surface);
		color: var(--accent);
		box-shadow: var(--shadow);
	}
	.themeseg {
		display: flex;
		background: var(--surface-2);
		border-radius: 999px;
		padding: 4px;
		gap: 2px;
		align-self: flex-start;
	}
	.themeseg button {
		width: 36px;
		height: 32px;
		border-radius: 999px;
		display: grid;
		place-items: center;
		color: var(--ink-3);
	}
	.themeseg button:hover {
		color: var(--ink);
	}
	.themeseg button[aria-pressed='true'] {
		background: var(--surface);
		color: var(--accent);
		box-shadow: var(--shadow);
	}
	.who {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 8px 10px;
		border-radius: var(--r-md);
		width: 100%;
		text-align: left;
		margin-top: 8px;
		transition: background 0.15s;
	}
	.who:hover {
		background: var(--surface-2);
	}
	.who b {
		font-weight: 600;
		line-height: 1.2;
		display: block;
	}
	.who small {
		display: block;
		color: var(--ink-3);
		font-size: 12px;
		line-height: 1.2;
	}
	.content {
		min-width: 0;
	}
	.topbar {
		display: none;
	}
	main {
		padding: 28px 32px 56px;
		max-width: 1280px;
		width: 100%;
	}
	.toast {
		position: fixed;
		left: 50%;
		bottom: 28px;
		transform: translate(-50%, 20px);
		background: var(--ink);
		color: var(--bg);
		padding: 11px 18px;
		border-radius: 999px;
		font-weight: 600;
		font-size: 14px;
		opacity: 0;
		pointer-events: none;
		transition:
			opacity 0.2s,
			transform 0.2s;
		z-index: 90;
		max-width: calc(100% - 32px);
		text-align: center;
	}
	.toast.on {
		opacity: 1;
		transform: translate(-50%, 0);
	}
	@media (max-width: 900px) {
		.app {
			grid-template-columns: minmax(0, 1fr);
		}
		.rail {
			position: fixed;
			top: auto;
			bottom: 0;
			left: 0;
			right: 0;
			height: auto;
			padding: 8px 12px calc(8px + env(safe-area-inset-bottom));
			background: var(--surface);
			box-shadow: 0 -8px 30px rgba(40, 30, 90, 0.1);
			z-index: 50;
			border-radius: 22px 22px 0 0;
		}
		.rail > :not(nav) {
			display: none;
		}
		nav {
			flex-direction: row;
			justify-content: space-around;
		}
		.navlink {
			flex-direction: column;
			gap: 3px;
			font-size: 11.5px;
			padding: 8px 16px;
		}
		.navlink[aria-current='page'] {
			box-shadow: none;
			background: var(--accent-soft);
			/* The lavender ink: the accent itself reaches only 4.44:1 on this fill in the light theme. */
			color: var(--i-lav);
		}
		.topbar {
			display: flex;
			align-items: center;
			gap: 10px;
			padding: 12px 16px 0;
		}
		.topbar .brand {
			padding: 0;
			flex: 1;
			font-size: 20px;
		}
		.topbar .who {
			width: auto;
			margin: 0;
			padding: 4px;
		}
		main {
			padding: 16px 16px 110px;
		}
		.toast {
			bottom: 96px;
		}
	}
</style>
