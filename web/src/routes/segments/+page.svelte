<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, type SegmentSummary } from '#lib/api.ts';
	import { app, errorText } from '#lib/app.svelte.ts';
	import Icon from '#lib/components/Icon.svelte';
	import Trace from '#lib/components/Trace.svelte';
	import * as f from '#lib/format.ts';

	const profile = $derived(app.active!);
	const units = $derived(profile.units);

	let sort = $state<'count' | 'recent'>('count');
	let hidden = $state(false);
	let items = $state<SegmentSummary[] | null>(null);
	let hiddenCount = $state(0);
	let processing = $state(false);
	let error = $state('');

	function load() {
		const [s, h] = [sort, hidden];
		api.segments(profile.id, s, h).then(
			(r) => {
				items = r.items;
				hiddenCount = r.hidden_count;
				processing = r.processing;
				error = '';
			},
			(e) => (error = errorText(e))
		);
	}
	$effect(() => {
		void app.revision;
		load();
	});

	async function restore(e: Event, s: SegmentSummary) {
		e.stopPropagation();
		try {
			await api.updateSegment(s.id, { hidden: false });
			app.notify(`« ${s.name} » est de nouveau visible`);
			load();
		} catch (err) {
			app.notify(errorText(err));
		}
	}
	// Nothing at all yet: neither visible nor hidden.
	const none = $derived(items !== null && !hidden && items.length === 0 && hiddenCount === 0);
</script>

<div class="view">
	<div>
		<div class="eyebrow">Profil de {profile.name}</div>
		<h1>Segments</h1>
		<p class="muted" style="margin:6px 0 0">Les portions que vous courez régulièrement, et vos chronos dessus.</p>
	</div>

	{#if error}
		<div class="note warn"><Icon name="x" /><span>{error}</span></div>
	{/if}
	{#if processing}
		<div class="note butter"><Icon name="clock" /><span>Détection en cours sur votre historique : les segments et les chronos se complètent tout seuls.</span></div>
	{/if}

	{#if items === null}
		<div class="skeleton" style="min-height:320px"></div>
	{:else if none}
		<div class="emptybox">
			<div class="big"><Icon name="flag" size={28} /></div>
			<h2>Pas encore de segment</h2>
			<p class="muted">
				Les segments apparaissent tout seuls : dès que vous avez couru trois fois la même portion, dans le même sens, elle devient un segment et tous vos
				passages sont chronométrés.
			</p>
		</div>
	{:else}
		<section class="card">
			<div class="card-h">
				<div class="seg" role="group" aria-label="Tri des segments">
					<button type="button" aria-pressed={sort === 'count'} onclick={() => (sort = 'count')}>Les plus courus</button>
					<button type="button" aria-pressed={sort === 'recent'} onclick={() => (sort = 'recent')}>Les plus récents</button>
				</div>
				<button class="switch hid" type="button" aria-pressed={hidden} onclick={() => (hidden = !hidden)}>
					<span class="tr"></span>Segments masqués ({hiddenCount})
				</button>
			</div>
			<div class="card-b">
				{#if items.length}
					<div class="tablebox">
						<table>
							<thead>
								<tr><th>Segment</th><th>Longueur</th><th>D+</th><th>Passages</th><th>Meilleur</th><th>Dernier</th><th><span class="sr">Action</span></th></tr>
							</thead>
							<tbody>
								{#each items as s (s.id)}
									<tr class="go" onclick={() => goto(`/segments/${s.id}`)}>
										<td class="name">
											<a class="nm" href="/segments/{s.id}" onclick={(e) => e.stopPropagation()}>
												<span class="th"><Trace points={s.preview} color="lav" /></span>{s.name}
											</a>
										</td>
										<td>{f.distance(s.distance, units)} {f.distanceUnit(units)}</td>
										<td>{s.gain === null ? '—' : `${f.elevation(s.gain, units)} ${f.elevationUnit(units)}`}</td>
										<td>{s.efforts}</td>
										<td>{s.best_time === null ? '—' : f.duration(s.best_time)}</td>
										<td>{s.latest_effort === null ? '—' : f.dateShort(s.latest_effort, profile.timezone)}</td>
										<td>
											{#if hidden}
												<button class="btn sm" type="button" onclick={(e) => restore(e, s)}>Rétablir</button>
											{:else}
												<span class="chev"><Icon name="chev" size={16} /></span>
											{/if}
										</td>
									</tr>
								{/each}
							</tbody>
						</table>
					</div>
				{:else}
					<p class="muted" style="margin:0">{hidden ? 'Aucun segment masqué.' : 'Tous vos segments sont masqués.'}</p>
				{/if}
			</div>
		</section>
	{/if}
</div>

<style>
	.hid {
		width: auto;
	}
	tr.go {
		cursor: pointer;
	}
	.nm {
		display: flex;
		align-items: center;
		gap: 12px;
	}
	.nm:hover {
		color: var(--accent);
	}
	.th {
		width: 64px;
		height: 44px;
		border-radius: 12px;
		background: var(--p-lav);
		flex: none;
		overflow: hidden;
		position: relative;
	}
	.chev {
		display: inline-grid;
		transform: rotate(-90deg);
		color: var(--ink-3);
	}
	.sr {
		position: absolute;
		width: 1px;
		height: 1px;
		overflow: hidden;
		clip-path: inset(50%);
	}
</style>
