<script lang="ts">
	// Editor of a structured session. Fields hold what the runner types; the session is read from
	// them on every change for the outline and totals, and checked as a whole before saving.
	import { untrack } from 'svelte';
	import { goto } from '$app/navigation';
	import { api, type Step, type StepKind, type Workout, type WorkoutItem } from '#lib/api.ts';
	import { app, errorText } from '#lib/app.svelte.ts';
	import Icon from '#lib/components/Icon.svelte';
	import Outline from '#lib/components/Outline.svelte';
	import * as f from '#lib/format.ts';
	import { distanceInput, KINDS, ordered, paceText, parseDistance, parseDuration, parsePace, problem, totals } from '#lib/workout.ts';

	let { workout = null }: { workout?: Workout | null } = $props();

	const profile = $derived(app.profiles.find((p) => p.id === workout?.profile_id) ?? app.active!);
	const units = $derived(profile.units);
	const bounds = $derived(profile.zones.bounds);

	interface Row {
		key: number;
		kind: StepKind;
		end: 'time' | 'distance' | 'lap';
		endText: string;
		target: 'none' | 'pace' | 'hr_zone';
		fast: string;
		slow: string;
		zone: number;
	}
	type Block = { key: number; type: 'step'; row: Row } | { key: number; type: 'repeat'; count: number; skip: boolean; rows: Row[] };

	let next = 0;
	function toRow(s: Omit<Step, 'type'>): Row {
		return {
			key: next++,
			kind: s.kind,
			end: s.end.type,
			endText: s.end.type === 'time' ? f.duration(s.end.value) : s.end.type === 'distance' ? distanceInput(s.end.value, units) : '',
			target: s.target.type,
			fast: s.target.type === 'pace' ? paceText(s.target.fast, units) : '',
			slow: s.target.type === 'pace' ? paceText(s.target.slow, units) : '',
			zone: s.target.type === 'hr_zone' ? s.target.zone : 2
		};
	}
	const fresh = (kind: StepKind): Row => toRow({ kind, end: { type: 'time', value: kind === 'effort' ? 300 : 120 }, target: { type: 'none' } });
	// The session being edited is loaded once; later prop changes do not reset what was typed.
	const initial = untrack(() => $state.snapshot(workout));
	let name = $state(initial?.name ?? '');
	let note = $state(initial?.note ?? '');
	let blocks = $state<Block[]>(
		(initial?.steps ?? []).map((it) =>
			it.type === 'step' ? { key: next++, type: 'step', row: toRow(it) } : { key: next++, type: 'repeat', count: it.count, skip: it.skip_last_recovery, rows: it.steps.map(toRow) }
		)
	);
	let tried = $state(false);
	let saving = $state(false);

	/** A row as a step, or the reason it cannot be read. */
	function read(r: Row, label: string): Step | string {
		let end: Step['end'];
		if (r.end === 'lap') end = { type: 'lap' };
		else if (r.end === 'time') {
			const v = parseDuration(r.endText);
			if (v === null) return `${label} : durée illisible, écrivez-la en minutes et secondes (8:00).`;
			end = { type: 'time', value: v };
		} else {
			const v = parseDistance(r.endText, units);
			if (v === null) return `${label} : distance illisible, écrivez-la en ${units === 'imperial' ? 'miles (0,25)' : 'mètres (400)'}.`;
			end = { type: 'distance', value: v };
		}
		let target: Step['target'] = { type: 'none' };
		if (r.target === 'pace') {
			const [fast, slow] = [parsePace(r.fast, units), parsePace(r.slow, units)];
			if (fast === null || slow === null) return `${label} : allure illisible, écrivez-la en minutes et secondes ${f.paceUnit(units)} (5:00).`;
			target = ordered({ type: 'pace', fast, slow });
		} else if (r.target === 'hr_zone') target = { type: 'hr_zone', zone: r.zone };
		return { type: 'step', kind: r.kind, end, target };
	}

	/** The session as typed: the steps that can be read, and the first reason one cannot. */
	const parsed = $derived.by(() => {
		const items: WorkoutItem[] = [];
		let error: string | null = null;
		let n = 0;
		const take = (r: Row) => {
			n++;
			const s = read(r, `Étape ${n}`);
			if (typeof s === 'string') {
				error ??= s;
				return null;
			}
			return s;
		};
		for (const b of blocks) {
			if (b.type === 'step') {
				const s = take(b.row);
				if (s) items.push(s);
			} else {
				const steps = b.rows.map(take).filter((s): s is Step => s !== null);
				if (steps.length || !b.rows.length) items.push({ type: 'repeat', count: b.count, skip_last_recovery: b.skip, steps });
			}
		}
		return { items, error: error ?? problem(items) };
	});
	const t = $derived(totals(parsed.items, units));

	function move<T>(list: T[], i: number, by: number) {
		const j = i + by;
		if (j < 0 || j >= list.length) return;
		[list[i], list[j]] = [list[j], list[i]];
	}
	const addStep = () => blocks.push({ key: next++, type: 'step', row: fresh(blocks.length ? 'effort' : 'warmup') });
	const addRepeat = () =>
		blocks.push({ key: next++, type: 'repeat', count: 6, skip: false, rows: [toRow({ kind: 'effort', end: { type: 'distance', value: 400 }, target: { type: 'none' } }), fresh('recovery')] });

	async function save() {
		tried = true;
		if (!name.trim() || parsed.error) return;
		saving = true;
		try {
			const body = { name: name.trim(), note: note.trim(), steps: parsed.items };
			if (workout) await api.updateWorkout(workout.id, body);
			else await api.createWorkout(profile.id, body);
			app.notify(workout ? 'Séance enregistrée' : `« ${body.name} » ajoutée à vos séances`);
			goto('/plan');
		} catch (e) {
			app.notify(errorText(e));
		} finally {
			saving = false;
		}
	}
</script>

{#snippet row(r: Row, list: unknown[], i: number, inBlock: boolean)}
	<div class="step">
		<i class="bar k-{r.kind}"></i>
		<select aria-label="Type d'étape" bind:value={r.kind}>
			{#each Object.entries(KINDS) as [k, label] (k)}<option value={k}>{label}</option>{/each}
		</select>
		<span class="pair">
			<select aria-label="Fin de l'étape" bind:value={r.end} onchange={() => (r.endText = r.end === 'time' ? '5:00' : r.end === 'distance' ? (units === 'imperial' ? '0,25' : '400') : '')}>
				<option value="time">Durée</option>
				<option value="distance">Distance</option>
				<option value="lap">Jusqu'à Tour</option>
			</select>
			{#if r.end !== 'lap'}
				<input type="text" inputmode="decimal" aria-label={r.end === 'time' ? 'Durée en minutes et secondes' : `Distance en ${units === 'imperial' ? 'miles' : 'mètres'}`} bind:value={r.endText} />
				<span class="small muted">{r.end === 'time' ? 'min:s' : units === 'imperial' ? 'mi' : 'm'}</span>
			{/if}
		</span>
		<span class="pair">
			<select aria-label="Cible" bind:value={r.target} onchange={() => r.target === 'pace' && !r.fast && ((r.fast = units === 'imperial' ? '8:00' : '5:00'), (r.slow = units === 'imperial' ? '8:30' : '5:15'))}>
				<option value="none">Sans cible</option>
				<option value="pace">Allure</option>
				<option value="hr_zone">Zone cardio</option>
			</select>
			{#if r.target === 'pace'}
				<input type="text" aria-label="Allure la plus rapide" bind:value={r.fast} /><span class="small muted">à</span>
				<input type="text" aria-label="Allure la plus lente" bind:value={r.slow} /><span class="small muted">{f.paceUnit(units)}</span>
			{:else if r.target === 'hr_zone'}
				<select aria-label="Zone cardiaque" bind:value={r.zone}>
					{#each [1, 2, 3, 4, 5] as z (z)}<option value={z}>Z{z} · {bounds[z - 1]}–{bounds[z]} bpm</option>{/each}
				</select>
			{/if}
		</span>
		<span class="acts">
			<button class="iconbtn flat sm" type="button" data-tip="Monter" aria-label="Monter l'étape" disabled={i === 0} onclick={() => move(list, i, -1)}><Icon name="up" size={16} /></button>
			<button class="iconbtn flat sm" type="button" data-tip="Descendre" aria-label="Descendre l'étape" disabled={i === list.length - 1} onclick={() => move(list, i, 1)}><Icon name="chev" size={16} /></button>
			<button class="iconbtn flat sm" type="button" data-tip="Retirer" data-tip-pos="end" aria-label="Retirer l'étape{inBlock ? ' du bloc' : ''}" onclick={() => list.splice(i, 1)}><Icon name="x" size={16} /></button>
		</span>
	</div>
{/snippet}

<div class="view">
	<a class="back" href="/plan"><Icon name="back" size={17} />Séances</a>
	<div class="spread">
		<h1>{workout ? 'Modifier la séance' : 'Nouvelle séance'}</h1>
		<div class="row">
			<a class="btn" href="/plan">Annuler</a>
			<button class="btn primary" type="button" disabled={saving} onclick={save}><Icon name="check" />Enregistrer</button>
		</div>
	</div>

	<div class="dgrid">
		<div class="col">
			<section class="card">
				<div class="card-b fields">
					<div class="field">
						<label for="w-name">Nom</label>
						<input id="w-name" type="text" maxlength="80" placeholder="Par exemple : 6 × 400 m" bind:value={name} />
						{#if tried && !name.trim()}<span class="err">Donnez un nom à la séance.</span>{/if}
					</div>
					<div class="field">
						<label for="w-note">Note (facultatif)</label>
						<input id="w-note" type="text" maxlength="500" bind:value={note} />
					</div>
					{#if tried && parsed.error}
						<div class="note butter" role="alert"><Icon name="info" /><span>{parsed.error}</span></div>
					{/if}
				</div>
			</section>

			<section class="card">
				<div class="card-h">
					<h2>Étapes</h2>
					<div class="row">
						<button class="btn sm" type="button" onclick={addStep}><Icon name="plus" size={15} />Étape</button>
						<button class="btn sm" type="button" onclick={addRepeat}><Icon name="repeat" size={15} />Bloc répété</button>
					</div>
				</div>
				<div class="card-b list">
					{#each blocks as b, i (b.key)}
						{#if b.type === 'step'}
							{@render row(b.row, blocks, i, false)}
						{:else}
							<div class="repeat">
								<div class="head">
									<Icon name="repeat" size={17} />Répéter
									<span class="stepper">
										<button type="button" aria-label="Une répétition de moins" disabled={b.count <= 2} onclick={() => b.count--}><Icon name="minus" size={15} /></button>
										<b aria-live="polite">{b.count}×</b>
										<button type="button" aria-label="Une répétition de plus" disabled={b.count >= 50} onclick={() => b.count++}><Icon name="plus" size={15} /></button>
									</span>
									<button class="switch skip" type="button" aria-pressed={b.skip} onclick={() => (b.skip = !b.skip)}><span class="tr"></span>Sans la dernière récupération</button>
									<span class="grow"></span>
									<button class="iconbtn flat sm" type="button" data-tip="Monter le bloc" aria-label="Monter le bloc" disabled={i === 0} onclick={() => move(blocks, i, -1)}><Icon name="up" size={16} /></button>
									<button class="iconbtn flat sm" type="button" data-tip="Descendre le bloc" aria-label="Descendre le bloc" disabled={i === blocks.length - 1} onclick={() => move(blocks, i, 1)}><Icon name="chev" size={16} /></button>
									<button class="iconbtn flat sm" type="button" data-tip="Retirer le bloc" data-tip-pos="end" aria-label="Retirer le bloc" onclick={() => blocks.splice(i, 1)}><Icon name="x" size={16} /></button>
								</div>
								{#each b.rows as r, j (r.key)}
									{@render row(r, b.rows, j, true)}
								{/each}
								<div><button class="btn sm inblock" type="button" onclick={() => b.rows.push(fresh('effort'))}><Icon name="plus" size={15} />Étape dans le bloc</button></div>
							</div>
						{/if}
					{:else}
						<p class="muted" style="margin:0">Aucune étape. Ajoutez-en une pour pouvoir enregistrer.</p>
					{/each}
				</div>
			</section>
		</div>

		<div class="col">
			<section class="card">
				<div class="card-h"><h2>Déroulé</h2></div>
				<div class="card-b fields">
					{#if parsed.items.length}<Outline items={parsed.items} {units} />{/if}
					<div class="tiles two">
						<div class="tile mint"><div class="top">Durée</div><div class="v sm">{parsed.items.length ? t.time : '—'}</div></div>
						<div class="tile sky"><div class="top">Distance</div><div class="v sm">{parsed.items.length ? t.distance : '—'}</div></div>
					</div>
					<p class="small muted" style="margin:0">
						{t.steps} étape{t.steps > 1 ? 's' : ''} une fois les répétitions déroulées. « Au moins » : une étape dure jusqu'à l'appui sur Tour. « Inconnue » : rien ne permet de la calculer
						sans inventer.
					</p>
				</div>
			</section>
		</div>
	</div>
</div>

<style>
	.fields {
		padding-top: 22px;
		display: flex;
		flex-direction: column;
		gap: 14px;
	}
	.fields .field {
		margin: 0;
	}
	.err {
		color: var(--i-rose);
		font-size: 13px;
		font-weight: 600;
	}
	.list {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.step {
		display: grid;
		grid-template-columns: 8px 172px minmax(0, 1fr) minmax(0, 1.5fr) auto;
		gap: 10px;
		align-items: center;
		padding: 10px;
		border-radius: var(--r-md);
		transition: background 0.15s;
	}
	.step:hover {
		background: var(--surface-2);
	}
	.step .bar {
		width: 8px;
		align-self: stretch;
		border-radius: 4px;
		height: auto;
	}
	.pair {
		display: flex;
		gap: 6px;
		align-items: center;
		min-width: 0;
		flex-wrap: wrap;
	}
	.step select,
	.step input {
		min-width: 0;
	}
	.pair select {
		width: auto;
		flex: 0 1 auto;
	}
	.step input[type='text'] {
		width: 74px;
		text-align: center;
		font-family: var(--font-display);
		font-variant-numeric: tabular-nums;
	}
	.acts {
		display: flex;
		gap: 2px;
	}
	.acts button:disabled,
	.head .iconbtn:disabled,
	.stepper button:disabled {
		opacity: 0.35;
		cursor: not-allowed;
	}
	.repeat {
		background: var(--p-lav);
		border-radius: var(--r-lg);
		padding: 10px;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.repeat .step:hover {
		background: color-mix(in srgb, var(--surface) 60%, transparent);
	}
	.repeat .step select,
	.repeat .step input {
		background: var(--surface);
	}
	.head {
		display: flex;
		align-items: center;
		gap: 10px;
		flex-wrap: wrap;
		padding: 4px 8px 6px;
		font-weight: 600;
	}
	.skip {
		width: auto;
	}
	.stepper {
		display: inline-flex;
		align-items: center;
		background: var(--surface);
		border-radius: 999px;
		padding: 3px;
	}
	.stepper button {
		width: 30px;
		height: 30px;
		border-radius: 50%;
		display: grid;
		place-items: center;
	}
	.stepper button:hover {
		background: var(--surface-2);
	}
	.stepper b {
		font: 600 17px var(--font-display);
		min-width: 34px;
		text-align: center;
	}
	.inblock {
		background: var(--surface);
	}
	.two {
		grid-template-columns: repeat(2, minmax(0, 1fr));
	}
	.v.sm {
		font-size: 22px;
		white-space: normal;
	}
	@media (max-width: 900px) {
		.step {
			grid-template-columns: 8px minmax(0, 1fr) auto;
		}
		.step .pair {
			grid-column: 2 / 4;
		}
		.step .acts {
			grid-row: 1;
			grid-column: 3;
			gap: 0;
		}
		.step .acts .iconbtn {
			width: 30px;
			height: 30px;
		}
		.step select {
			padding: 10px 8px;
		}
	}
</style>
