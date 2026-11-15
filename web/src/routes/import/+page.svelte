<script lang="ts">
	import { api, type ImportOutcome } from '#lib/api.ts';
	import { app, errorText } from '#lib/app.svelte.ts';
	import Icon, { type IconName } from '#lib/components/Icon.svelte';
	import ProfileSheet from '#lib/components/ProfileSheet.svelte';

	const profile = $derived(app.active!);

	let over = $state(false);
	let busy = $state(false);
	let pending = $state<string[]>([]);
	let results = $state<ImportOutcome[]>([]);
	let error = $state('');
	let sheet = $state(false);
	let input = $state<HTMLInputElement | null>(null);

	const count = (s: ImportOutcome['status']) => results.filter((r) => r.status === s).length;
	const plural = (n: number, word: string) => `${n} ${word}${n > 1 ? 's' : ''}`;
	const LOOK: Record<ImportOutcome['status'], { icon: IconName; cls: string; text: string }> = {
		imported: { icon: 'check', cls: 'ok', text: 'Importée' },
		duplicate: { icon: 'copy', cls: 'dup', text: 'Déjà dans ce profil' },
		failed: { icon: 'x', cls: 'ko', text: 'Échec' }
	};

	async function send(files: File[]) {
		if (!files.length || busy) return;
		busy = true;
		error = '';
		results = [];
		pending = files.map((f) => f.name);
		try {
			results = await api.importFiles(profile.id, files);
			const n = count('imported');
			if (n) {
				app.changed();
				app.refreshProfiles();
			}
			app.notify(n ? `${plural(n, 'sortie')} importée${n > 1 ? 's' : ''}` : 'Aucune nouvelle sortie');
		} catch (e) {
			error = errorText(e);
		} finally {
			pending = [];
			busy = false;
			if (input) input.value = '';
		}
	}

	function drop(e: DragEvent) {
		e.preventDefault();
		over = false;
		send([...(e.dataTransfer?.files ?? [])]);
	}
</script>

<div class="view">
	<div>
		<div class="eyebrow">Fichiers .gpx et .fit</div>
		<h1>Importer</h1>
	</div>

	<div class="igrid">
		<div class="col">
			<!-- svelte-ignore a11y_no_static_element_interactions -->
			<div
				class="drop"
				class:over
				ondragover={(e) => (e.preventDefault(), (over = true))}
				ondragleave={() => (over = false)}
				ondrop={drop}
			>
				<div class="big"><Icon name="upload" size={34} /></div>
				<h2>Déposez vos fichiers ici</h2>
				<p class="muted">Plusieurs fichiers à la fois, par exemple tout votre historique exporté d'une autre application.</p>
				<label class="btn primary" class:disabled={busy} for="files"><Icon name="file" />{busy ? 'Import en cours…' : 'Choisir des fichiers'}</label>
				<input
					id="files"
					class="hidden"
					type="file"
					multiple
					accept=".gpx,.fit"
					disabled={busy}
					bind:this={input}
					onchange={(e) => send([...(e.currentTarget.files ?? [])])}
				/>
				<button class="target" type="button" onclick={() => (sheet = true)} data-tip="Changer de profil">
					<span class="avatar sm" style:background={profile.color}>{profile.name.charAt(0).toUpperCase()}</span>
					Ajoutés au profil de {profile.name}<Icon name="chev" size={15} />
				</button>
			</div>

			{#if error}
				<div class="note warn"><Icon name="x" /><span>{error}</span></div>
			{/if}

			{#if pending.length || results.length}
				<section class="card" aria-live="polite">
					<div class="card-h">
						<h2>{busy ? 'Import en cours' : 'Résultat'}</h2>
						{#if results.length}
							<div class="row" style="gap:6px">
								<span class="tag mint">{plural(count('imported'), 'importée')}</span>
								<span class="tag">{plural(count('duplicate'), 'doublon')}</span>
								<span class="tag rose">{plural(count('failed'), 'échec')}</span>
							</div>
						{/if}
					</div>
					<div class="card-b">
						{#each pending as name, i (i)}
							<div class="frow">
								<span class="st"><Icon name="file" /></span>
								<div class="grow">
									<div class="fn">{name}</div>
									<div class="prog"><span></span></div>
								</div>
							</div>
						{/each}
						{#each results as r, i (i)}
							{@const look = LOOK[r.status]}
							<div class="frow">
								<span class="st {look.cls}"><Icon name={look.icon} /></span>
								<div class="grow">
									<div class="fn">{r.file}</div>
									<div class="fs">{r.status === 'failed' ? (r.reason ?? look.text) : look.text}</div>
								</div>
								{#if r.activity_id}<a class="btn sm" href="/activities/{r.activity_id}">Voir</a>{/if}
							</div>
						{/each}
					</div>
				</section>
			{/if}
		</div>

		<section class="card">
			<div class="card-h"><h2>Bon à savoir</h2></div>
			<div class="card-b facts">
				<div class="fact">
					<span class="chip" style="background:var(--p-mint);color:var(--i-mint)"><Icon name="copy" /></span>
					<div><b>Doublons écartés</b><div class="muted">Une sortie déjà présente dans le profil n'est pas importée deux fois, même depuis un autre format.</div></div>
				</div>
				<div class="fact">
					<span class="chip" style="background:var(--p-sky);color:var(--i-sky)"><Icon name="file" /></span>
					<div><b>Fichier d'origine conservé</b><div class="muted">Vous pouvez le retélécharger à tout moment depuis la sortie.</div></div>
				</div>
				<div class="fact">
					<span class="chip" style="background:var(--p-peach);color:var(--i-peach)"><Icon name="heart" /></span>
					<div><b>Données manquantes tolérées</b><div class="muted">Sans fréquence cardiaque ni cadence, la sortie est importée et les mesures absentes sont signalées.</div></div>
				</div>
				<div class="fact">
					<span class="chip" style="background:var(--p-lav);color:var(--i-lav)"><Icon name="shield" /></span>
					<div><b>Tout reste chez vous</b><div class="muted">Les fichiers ne quittent pas votre serveur domestique.</div></div>
				</div>
			</div>
		</section>
	</div>
</div>

{#if sheet}<ProfileSheet onclose={() => (sheet = false)} />{/if}

<style>
	.igrid {
		display: grid;
		grid-template-columns: minmax(0, 1fr) 340px;
		gap: 20px;
		align-items: start;
	}
	.col {
		display: flex;
		flex-direction: column;
		gap: 20px;
		min-width: 0;
	}
	.drop {
		border-radius: var(--r-xl);
		background: var(--p-lav);
		padding: 44px 24px;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 12px;
		text-align: center;
		transition:
			transform 0.2s,
			background 0.2s;
	}
	.drop.over {
		transform: scale(1.01);
		background: var(--p-mint);
	}
	.drop .big {
		width: 76px;
		height: 76px;
		border-radius: 26px;
		background: var(--surface);
		color: var(--i-lav);
		display: grid;
		place-items: center;
	}
	.drop h2 {
		font-size: 23px;
	}
	.drop p {
		margin: 0;
		max-width: 46ch;
	}
	label.btn {
		cursor: pointer;
	}
	label.disabled {
		opacity: 0.6;
		cursor: progress;
	}
	.hidden {
		position: absolute;
		opacity: 0;
		width: 1px;
		height: 1px;
	}
	.hidden:focus-visible + .target {
		outline: 2px solid var(--accent);
	}
	.target {
		display: flex;
		align-items: center;
		gap: 8px;
		font-weight: 600;
		font-size: 14px;
		margin-top: 4px;
		padding: 4px 10px;
		border-radius: 999px;
	}
	.target:hover {
		background: var(--surface);
	}
	.frow {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 12px;
		border-radius: var(--r-md);
		transition: background 0.15s;
	}
	.frow:hover {
		background: var(--surface-2);
	}
	.st {
		width: 38px;
		height: 38px;
		border-radius: 12px;
		display: grid;
		place-items: center;
		flex: none;
		background: var(--surface-2);
		color: var(--ink-2);
	}
	.st.ok {
		background: var(--p-mint);
		color: var(--i-mint);
	}
	.st.dup {
		background: var(--p-butter);
		color: var(--i-butter);
	}
	.st.ko {
		background: var(--p-rose);
		color: var(--i-rose);
	}
	.fn {
		font-weight: 600;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.fs {
		font-size: 13px;
		color: var(--ink-2);
	}
	.prog {
		height: 6px;
		border-radius: 3px;
		background: var(--surface-2);
		overflow: hidden;
		margin-top: 6px;
	}
	.prog span {
		display: block;
		height: 100%;
		width: 40%;
		border-radius: 3px;
		background: var(--accent);
		animation: slide 1s ease-in-out infinite;
	}
	@keyframes slide {
		from {
			transform: translateX(-100%);
		}
		to {
			transform: translateX(260%);
		}
	}
	.facts {
		display: flex;
		flex-direction: column;
		gap: 14px;
	}
	.fact {
		display: flex;
		gap: 12px;
		align-items: flex-start;
	}
	.fact .chip {
		width: 36px;
		height: 36px;
		border-radius: 12px;
		display: grid;
		place-items: center;
		flex: none;
	}
	.fact .muted {
		font-size: 14px;
	}
	@media (max-width: 1180px) {
		.igrid {
			grid-template-columns: minmax(0, 1fr);
		}
	}
</style>
