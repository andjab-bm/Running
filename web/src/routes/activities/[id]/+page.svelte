<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, ApiError, type Activity, type Segment, type Streams } from '#lib/api.ts';
	import { app, errorText } from '#lib/app.svelte.ts';
	import ActivityMap from '#lib/components/ActivityMap.svelte';
	import Icon, { type IconName } from '#lib/components/Icon.svelte';
	import MetricChart, { type Block, type TipRow } from '#lib/components/MetricChart.svelte';
	import Modal from '#lib/components/Modal.svelte';
	import * as f from '#lib/format.ts';

	type Measure = 'pace' | 'hr' | 'cad' | 'ele';
	const MEASURES: { key: Measure; label: string; icon: IconName }[] = [
		{ key: 'pace', label: 'Allure', icon: 'gauge' },
		{ key: 'hr', label: 'FC', icon: 'heart' },
		{ key: 'cad', label: 'Cadence', icon: 'steps' },
		{ key: 'ele', label: 'Altitude', icon: 'mountain' }
	];

	const id = $derived(Number(page.params.id));
	let a = $state<Activity | null>(null);
	let s = $state<Streams | null>(null);
	let missing = $state(false);
	let error = $state('');

	let colorBy = $state<'pace' | 'hr'>('pace');
	let measure = $state<Measure>('pace');
	let showBlocks = $state(true);
	let showRelief = $state(true);
	let table = $state<'split' | 'lap'>('split');
	let cursor = $state<number | null>(null);
	let sel = $state<{ i0: number; i1: number } | null>(null);
	let selRow = $state(-1);
	let menu = $state(false);
	let dialog = $state<'copy' | 'move' | 'delete' | null>(null);
	let target = $state<number | null>(null);
	let keepHr = $state(false);

	let editing = $state(false);
	let draft = $state({ title: '', description: '', rpe: 0, tags: [] as string[] });
	let suggestions = $state<string[]>([]);
	let newTag = $state('');

	// The activity's own profile decides units and time zone, even if another one is active.
	const owner = $derived(app.profiles.find((p) => p.id === a?.profile_id) ?? app.active!);
	const units = $derived(owner.units);
	const tz = $derived(owner.timezone);
	const others = $derived(app.profiles.filter((p) => p.id !== a?.profile_id));
	const hasHr = $derived(!!a && a.avg_hr !== null);
	const source = $derived(a?.copied_from ? app.profiles.find((p) => p.id !== a!.profile_id) : null);

	async function load() {
		missing = false;
		error = '';
		try {
			const [act, st] = await Promise.all([api.activity(id), api.streams(id)]);
			a = act;
			s = st;
			if (act.avg_hr === null) {
				if (colorBy === 'hr') colorBy = 'pace';
				if (measure === 'hr') measure = 'pace';
			}
			if (act.laps.length < 2) table = 'split';
		} catch (e) {
			if (e instanceof ApiError && e.status === 404) missing = true;
			else error = errorText(e);
		}
	}
	$effect(() => {
		void id;
		load();
	});

	// ---- theme colours, re-read when the palette changes ----
	const css = (name: string) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
	const theme = $derived.by(() => {
		void app.dark;
		void app.theme;
		const z = [1, 2, 3, 4, 5].map((n) => css(`--z${n}`));
		return {
			z,
			fast: css('--pace-fast'),
			mid: css('--pace-mid'),
			slow: css('--pace-slow'),
			accent: css('--accent'),
			rose: css('--c-rose'),
			mint: css('--c-mint'),
			sky: css('--c-sky'),
			ink3: css('--ink-3'),
			grid: css('--surface-2'),
			surface: css('--surface'),
			land: css('--map-land')
		};
	});

	const hex = (c: string) => [1, 3, 5].map((i) => parseInt(c.slice(i, i + 2), 16));
	const mix = (c1: string, c2: string, t: number) => {
		const [p, q] = [hex(c1), hex(c2)];
		return `rgb(${p.map((v, i) => Math.round(v + (q[i] - v) * t)).join(',')})`;
	};
	const zoneOf = (h: number) => {
		const b = a!.zones!.bounds;
		for (let z = 4; z > 0; z--) if (h >= b[z]) return z;
		return 0;
	};

	// ---- series in display units ----
	const unitLen = $derived(f.unitLength(units));
	const x = $derived(s ? s.dist.map((d) => d / unitLen) : []);
	/** Seconds per km or mile; null while stopped. */
	/** Speed averaged over about 100 m of running, so the pace curve reads as effort rather than GPS noise. */
	const SMOOTH_M = 50;
	const smoothSpeed = $derived.by(() => {
		if (!s) return [];
		const { dist, t, moving } = s;
		const n = dist.length;
		// Cumulative moving time, so any window is one subtraction.
		const mt = new Array<number>(n).fill(0);
		for (let i = 1; i < n; i++) mt[i] = mt[i - 1] + (moving[i] ? t[i] - t[i - 1] : 0);
		const out = new Array<number>(n).fill(0);
		let lo = 0;
		let hi = 0;
		for (let i = 0; i < n; i++) {
			while (dist[lo] < dist[i] - SMOOTH_M) lo++;
			while (hi + 1 < n && dist[hi + 1] <= dist[i] + SMOOTH_M) hi++;
			const dt = mt[hi] - mt[lo];
			out[i] = dt > 0 ? (dist[hi] - dist[lo]) / dt : 0;
		}
		return out;
	});
	const paceSeries = $derived(smoothSpeed.map((v) => f.paceSeconds(v, units)));
	const paceRange = $derived.by(() => {
		const v = (paceSeries.filter((p) => p !== null) as number[]).sort((p, q) => p - q);
		return v.length ? [v[Math.floor(v.length * 0.03)], v[Math.floor(v.length * 0.97)]] : [300, 400];
	});

	const colors = $derived.by(() => {
		if (!s) return [];
		if (colorBy === 'hr' && s.hr && a?.zones) return s.hr.map((h) => (h === null ? theme.z[0] : theme.z[zoneOf(h)]));
		const [lo, hi] = paceRange;
		return paceSeries.map((p) => {
			const t = p === null ? 1 : Math.min(1, Math.max(0, (p - lo) / (hi - lo || 1)));
			return t < 0.5 ? mix(theme.fast, theme.mid, t * 2) : mix(theme.mid, theme.slow, (t - 0.5) * 2);
		});
	});

	const chart = $derived.by(() => {
		if (!s || !a) return null;
		const whole = a;
		const blocksOf = (value: (seg: Segment) => number | null, color: (seg: Segment) => string): Block[] => {
			let from = 0;
			const out: Block[] = [];
			for (const seg of whole.splits) {
				const to = from + seg.distance / unitLen;
				const v = value(seg);
				if (v !== null) out.push({ x0: from, x1: to, value: v, color: color(seg) });
				from = to;
			}
			return out;
		};
		switch (measure) {
			case 'hr':
				return {
					y: s.hr ?? [],
					inverted: false,
					color: theme.rose,
					format: (v: number) => String(Math.round(v)),
					unit: 'bpm',
					average: whole.avg_hr,
					blocks: blocksOf((g) => g.avg_hr, (g) => (whole.zones && g.avg_hr ? theme.z[zoneOf(g.avg_hr)] : theme.rose))
				};
			case 'cad':
				return {
					y: s.cad ?? [],
					inverted: false,
					color: theme.mint,
					format: (v: number) => String(Math.round(v)),
					unit: 'ppm',
					average: whole.avg_cad,
					blocks: blocksOf((g) => g.avg_cad, () => theme.mint)
				};
			case 'ele':
				return {
					y: (s.ele ?? []).map((e) => (units === 'imperial' ? e * 3.28084 : e)),
					inverted: false,
					color: theme.sky,
					format: (v: number) => String(Math.round(v)),
					unit: f.elevationUnit(units),
					average: null,
					blocks: null
				};
			default:
				return {
					y: paceSeries,
					inverted: true,
					color: theme.accent,
					format: (v: number) => f.duration(v),
					unit: f.paceUnit(units),
					average: f.paceSeconds(whole.speed, units),
					blocks: blocksOf((g) => f.paceSeconds(g.speed, units), () => theme.accent)
				};
		}
	});
	const available = $derived<Record<Measure, boolean>>({ pace: true, hr: !!s?.hr, cad: !!s?.cad, ele: !!s?.ele });

	function tip(i: number): { title: string; rows: TipRow[] } {
		const rows: TipRow[] = [{ color: theme.accent, label: 'Allure', value: `${f.pace(smoothSpeed[i], units)} ${f.paceUnit(units)}` }];
		const h = s!.hr?.[i];
		if (h !== null && h !== undefined) rows.push({ color: a!.zones ? theme.z[zoneOf(h)] : theme.rose, label: a!.zones ? `FC · Z${zoneOf(h) + 1}` : 'FC', value: `${Math.round(h)} bpm` });
		const c = s!.cad?.[i];
		if (c) rows.push({ color: theme.mint, label: 'Cadence', value: `${Math.round(c)} ppm` });
		if (s!.ele) rows.push({ color: theme.sky, label: 'Altitude', value: `${f.elevation(s!.ele[i], units)} ${f.elevationUnit(units)}` });
		return { title: `${f.distanceUnit(units)} ${f.distance(s!.dist[i], units)}`, rows };
	}

	// ---- splits / laps ----
	const rows = $derived(a ? (table === 'split' ? a.splits : a.laps) : []);
	const fastest = $derived(Math.max(...rows.map((r) => r.speed)));
	const slowest = $derived(Math.min(...rows.map((r) => r.speed)));
	function barWidth(r: Segment) {
		// Compare on pace, so the bar lengths read like the pace column.
		const [pf, ps, p] = [1 / fastest, 1 / slowest, 1 / r.speed];
		return ps === pf ? 100 : 36 + (64 * (ps - p)) / (ps - pf);
	}
	function selectRow(r: Segment | null, n = -1) {
		selRow = n;
		if (!r || !s) return (sel = null);
		const at = (t: number) => {
			let lo = 0;
			let hi = s!.t.length - 1;
			while (lo < hi) {
				const m = (lo + hi) >> 1;
				if (s!.t[m] < t) lo = m + 1;
				else hi = m;
			}
			return lo;
		};
		sel = { i0: at(r.start_t), i1: at(r.start_t + r.elapsed_time) };
	}

	// ---- zones ----
	const zoneTotal = $derived(a?.zones ? a.zones.seconds.reduce((p, q) => p + q, 0) : 0);

	// ---- journal ----
	async function startEdit() {
		draft = { title: a!.title, description: a!.description, rpe: a!.rpe ?? 0, tags: [...a!.tags] };
		editing = true;
		suggestions = await api.tags(a!.profile_id).catch(() => []);
	}
	const tagChoices = $derived([...new Set([...suggestions, ...draft.tags])]);
	function toggleTag(t: string) {
		draft.tags = draft.tags.includes(t) ? draft.tags.filter((q) => q !== t) : [...draft.tags, t];
	}
	function addTag(e: SubmitEvent) {
		e.preventDefault();
		const t = newTag.trim();
		if (t && !draft.tags.some((q) => q.toLowerCase() === t.toLowerCase())) draft.tags = [...draft.tags, t];
		newTag = '';
	}
	async function saveJournal() {
		try {
			a = await api.updateJournal(id, { title: draft.title, description: draft.description, rpe: draft.rpe || null, tags: draft.tags });
			editing = false;
			app.changed();
			app.notify('Journal enregistré');
		} catch (e) {
			app.notify(errorText(e));
		}
	}

	// ---- actions ----
	function open(kind: 'copy' | 'move' | 'delete') {
		menu = false;
		target = others[0]?.id ?? null;
		keepHr = false;
		dialog = kind;
	}
	const targetName = $derived(app.profiles.find((p) => p.id === target)?.name ?? '');
	async function confirm() {
		try {
			if (dialog === 'copy' && target) {
				await api.copyActivity(id, target, keepHr);
				app.notify(`Ajoutée au profil de ${targetName}`);
			} else if (dialog === 'move' && target) {
				await api.moveActivity(id, target);
				app.notify(`Déplacée vers le profil de ${targetName}`);
				await app.refreshProfiles();
				app.changed();
				dialog = null;
				return goto('/');
			} else if (dialog === 'delete') {
				await api.deleteActivity(id);
				app.notify('Sortie supprimée');
				await app.refreshProfiles();
				app.changed();
				dialog = null;
				return goto('/');
			}
			await app.refreshProfiles();
		} catch (e) {
			app.notify(e instanceof ApiError && e.code === 'already_present' ? `Cette sortie est déjà dans le profil de ${targetName}` : errorText(e));
		}
		dialog = null;
	}
</script>

<svelte:window onclick={() => (menu = false)} />

{#if missing}
	<div class="emptybox">
		<div class="big"><Icon name="route" size={30} /></div>
		<h2>Cette sortie n'existe pas</h2>
		<p class="muted">Elle a peut-être été supprimée ou déplacée vers un autre profil.</p>
		<a class="btn primary" href="/"><Icon name="back" />Toutes les activités</a>
	</div>
{:else if error}
	<div class="note warn"><Icon name="x" /><span>{error}</span></div>
{:else if !a || !s || !chart}
	<div class="view">
		<div class="skeleton" style="height:90px"></div>
		<div class="tiles">{#each [0, 1, 2, 3] as i (i)}<div class="skeleton" style="height:112px"></div>{/each}</div>
		<div class="skeleton" style="height:420px;border-radius:var(--r-xl)"></div>
	</div>
{:else}
	<div class="view">
		<div>
			<a class="back" href="/"><Icon name="back" size={17} />Toutes les activités</a>
			<div class="spread" style="align-items:flex-start;margin-top:6px">
				<div style="min-width:0;flex:1 1 320px">
					<h1>{a.title}</h1>
					<div class="meta">
						<span><Icon name="calendar" size={16} />{f.dateLong(a.start_time, tz)}</span>
						<span><Icon name="clock" size={16} />{f.clock(a.start_time, tz)}</span>
						{#each a.tags as t (t)}<span class="tag {f.tagColor(t)}">{t}</span>{/each}
					</div>
				</div>
				<div class="row" style="position:relative">
					<a class="iconbtn" href={api.exportUrl(a.id)} download data-tip="Exporter en GPX" aria-label="Exporter en GPX"><Icon name="download" /></a>
					{#if others.length}
						<button class="iconbtn" type="button" data-tip="Copier vers un profil" aria-label="Copier vers un profil" onclick={() => open('copy')}><Icon name="users" /></button>
					{/if}
					<button class="iconbtn" type="button" data-tip="Autres actions" data-tip-pos="end" aria-label="Autres actions" aria-expanded={menu} onclick={(e) => (e.stopPropagation(), (menu = !menu))}><Icon name="dots" /></button>
					{#if menu}
						<div class="pop">
							{#if a.has_original}<a href={api.originalUrl(a.id)} download><Icon name="file" />Fichier d'origine (.{a.source_format})</a>{/if}
							{#if others.length}<button type="button" onclick={() => open('move')}><Icon name="swap" />Déplacer vers un profil</button>{/if}
							<button type="button" class="dng" onclick={() => open('delete')}><Icon name="trash" />Supprimer</button>
						</div>
					{/if}
				</div>
			</div>
		</div>

		{#if a.copied_from}
			<div class="note"><Icon name="copy" /><span>Copiée depuis {source ? `le profil de ${source.name}` : 'un autre profil'}{hasHr ? '' : ', sans fréquence cardiaque : elle appartient à la personne qui portait la montre'}.</span></div>
		{/if}

		<div class="tiles">
			<div class="tile sky">
				<div class="top"><span class="chip"><Icon name="route" size={16} /></span>Distance</div>
				<div class="v">{f.distance(a.distance, units)}<small>{f.distanceUnit(units)}</small></div>
				<div class="s">{a.gain === null ? 'dénivelé non enregistré' : `dénivelé +${f.elevation(a.gain, units)} / −${f.elevation(a.loss, units)} ${f.elevationUnit(units)}`}</div>
			</div>
			<div class="tile mint">
				<div class="top"><span class="chip"><Icon name="timer" size={16} /></span>Temps en mouvement</div>
				<div class="v">{f.duration(a.moving_time)}</div>
				<div class="s">écoulé {f.duration(a.elapsed_time)}</div>
			</div>
			<div class="tile lav">
				<div class="top"><span class="chip"><Icon name="gauge" size={16} /></span>Allure moyenne</div>
				<div class="v">{f.pace(a.speed, units)}<small>{f.paceUnit(units)}</small></div>
				<div class="s">{a.gap_speed === null ? `cadence ${f.whole(a.avg_cad)} ppm` : `ajustée à la pente ${f.pace(a.gap_speed, units)} · cadence ${f.whole(a.avg_cad)}`}</div>
			</div>
			<div class="tile rose">
				<div class="top"><span class="chip"><Icon name="heart" size={16} /></span>Fréquence cardiaque</div>
				<div class="v">{f.whole(a.avg_hr)}{#if hasHr}<small>bpm</small>{/if}</div>
				<div class="s">{hasHr ? `max ${f.whole(a.max_hr)} bpm` : 'non enregistrée'}</div>
			</div>
		</div>

		<div class="dgrid">
			<div class="col">
				<section class="card mapcard" aria-label="Parcours">
					<ActivityMap lat={s.lat} lon={s.lon} {colors} styleUrl={app.mapStyle} land={theme.land} casing={theme.surface} highlight={theme.accent} {cursor} {sel} onhover={(i) => (cursor = i)} />
					<div class="float seg" role="group" aria-label="Couleur du tracé">
						<button type="button" aria-pressed={colorBy === 'pace'} onclick={() => (colorBy = 'pace')}><Icon name="gauge" size={15} />Allure</button>
						<button type="button" aria-pressed={colorBy === 'hr'} disabled={!hasHr || !a.zones} onclick={() => (colorBy = 'hr')}><Icon name="heart" size={15} />FC</button>
					</div>
					<div class="float right">
						<button class="iconbtn" type="button" aria-pressed={app.basemap} data-tip="Fond de carte" data-tip-pos="end" aria-label="Afficher ou masquer le fond de carte" onclick={() => app.setBasemap(!app.basemap)}><Icon name="layers" /></button>
					</div>
					<div class="legend">
						{#if colorBy === 'hr'}
							<div class="zs">{#each [1, 2, 3, 4, 5] as z (z)}<span><i style:background="var(--z{z})"></i>Z{z}</span>{/each}</div>
						{:else}
							<div class="ends"><span>Rapide</span><span>Lent</span></div>
							<div class="ramp"></div>
							<div class="ends num"><span>{f.duration(paceRange[0])}</span><span>{f.duration(paceRange[1])} {f.paceUnit(units)}</span></div>
						{/if}
					</div>
				</section>

				<section class="card" aria-label="Courbes">
					<div class="card-h">
						<div class="seg compact" role="group" aria-label="Donnée affichée">
							{#each MEASURES as m (m.key)}
								<button type="button" aria-pressed={measure === m.key} disabled={!available[m.key]} aria-label={m.label} onclick={() => (measure = m.key)}><Icon name={m.icon} size={15} /><span class="lb">{m.label}</span></button>
							{/each}
						</div>
						<div class="row" style="gap:8px">
							<button class="iconbtn flat" type="button" aria-pressed={showBlocks} disabled={measure === 'ele'} data-tip="Moyenne par {units === 'imperial' ? 'mile' : 'kilomètre'}" aria-label="Moyenne par unité de distance" onclick={() => (showBlocks = !showBlocks)}><Icon name="bars" /></button>
							<button class="iconbtn flat" type="button" aria-pressed={showRelief} disabled={!s.ele || measure === 'ele'} data-tip="Relief en filigrane" data-tip-pos="end" aria-label="Relief en filigrane" onclick={() => (showRelief = !showRelief)}><Icon name="mountain" /></button>
						</div>
					</div>
					<div class="card-b">
						{#key `${measure}${showBlocks}${showRelief}${app.dark}${units}`}
							<MetricChart
								{x}
								y={chart.y}
								inverted={chart.inverted}
								color={chart.color}
								format={chart.format}
								unit={chart.unit}
								xUnit={f.distanceUnit(units)}
								blocks={showBlocks ? chart.blocks : null}
								relief={showRelief && measure !== 'ele' ? s.ele : null}
								average={chart.average}
								ink={theme.ink3}
								grid={theme.grid}
								surface={theme.surface}
								{cursor}
								{sel}
								onhover={(i) => (cursor = i)}
								{tip}
								label={MEASURES.find((m) => m.key === measure)!.label}
							/>
						{/key}
					</div>
				</section>
			</div>

			<div class="col">
				<section class="card" aria-label="Zones de fréquence cardiaque">
					<div class="card-h">
						<h2>Zones cardiaques</h2>
						{#if a.zones}<a class="muted small" href="/settings" data-tip="Modifier dans Réglages" data-tip-pos="end">FC max {a.zones.bounds[5]}</a>{/if}
					</div>
					<div class="card-b">
						{#if !a.zones}
							<p class="muted" style="margin:0">Aucune fréquence cardiaque pour cette sortie : les zones et l'effort relatif ne peuvent pas être calculés.</p>
						{:else}
							{#if a.zones.uses_default_hr_max}
								<div class="note" style="margin-bottom:12px"><Icon name="heart" /><span>Zones calculées avec une FC max par défaut. <a href="/settings"><b>Indiquez la vôtre</b></a> pour des zones justes.</span></div>
							{/if}
							<div class="zbar">
								{#each a.zones.seconds as sec, i (i)}
									{#if sec > 0}<span style:flex="{sec} 1 0" style:background="var(--z{i + 1})" data-tip="Z{i + 1} · {f.duration(sec)} · {Math.round((sec / zoneTotal) * 100)} %" data-tip-pos={i < 2 ? 'start' : i > 2 ? 'end' : undefined}></span>{/if}
								{/each}
							</div>
							<div style="margin-top:10px">
								{#each [4, 3, 2, 1, 0] as i (i)}
									<div class="zrow">
										<span class="zn"><i style:background="var(--z{i + 1})"></i>Z{i + 1}</span>
										<span class="zr">{f.ZONE_NAMES[i]} · {a.zones.bounds[i]}–{a.zones.bounds[i + 1]} bpm</span>
										<b>{f.duration(a.zones.seconds[i])}</b>
										<span class="pc">{Math.round((a.zones.seconds[i] / zoneTotal) * 100)} %</span>
									</div>
								{/each}
							</div>
							{#if a.effort !== null}
								<div class="effort">
									<span class="ring"><Icon name="flame" size={24} /></span>
									<div><div class="eyebrow">Effort relatif</div><span class="ev num">{Math.round(a.effort)}</span> <span class="muted">· {f.effortWord(a.effort)}</span></div>
								</div>
							{/if}
						{/if}
					</div>
				</section>

				<section class="card journal" aria-label="Journal">
					{#if editing}
						<div class="card-h"><h2>Journal</h2></div>
						<div class="card-b">
							<div class="field"><label for="j-title">Titre</label><input id="j-title" type="text" maxlength="200" bind:value={draft.title} /></div>
							<div class="field"><label for="j-desc">Description</label><textarea id="j-desc" bind:value={draft.description}></textarea></div>
							<div class="flabel">Ressenti de l'effort</div>
							<div class="rpe">
								{#each Array(10) as _, i (i)}
									<button type="button" class:on={i < draft.rpe} aria-label="{i + 1} sur 10" data-tip="{i + 1} · {f.RPE_WORDS[i + 1]}" onclick={() => (draft.rpe = draft.rpe === i + 1 ? 0 : i + 1)}></button>
								{/each}
							</div>
							<div class="flabel" style="margin-top:14px">Tags</div>
							<div class="row" style="gap:6px;margin:6px 0 10px">
								{#each tagChoices as t (t)}
									<button type="button" class="tag {draft.tags.includes(t) ? f.tagColor(t) : 'off'}" aria-pressed={draft.tags.includes(t)} onclick={() => toggleTag(t)}>{t}</button>
								{/each}
							</div>
							<form class="row" style="margin-bottom:18px;flex-wrap:nowrap" onsubmit={addTag}>
								<input type="text" maxlength="40" placeholder="Nouveau tag" aria-label="Nouveau tag" bind:value={newTag} />
								<button class="iconbtn flat sm" type="submit" aria-label="Ajouter le tag" disabled={!newTag.trim()}><Icon name="plus" size={16} /></button>
							</form>
							<div class="row">
								<button class="btn primary" type="button" disabled={!draft.title.trim()} onclick={saveJournal}><Icon name="check" />Enregistrer</button>
								<button class="btn" type="button" onclick={() => (editing = false)}>Annuler</button>
							</div>
						</div>
					{:else}
						<div class="card-h">
							<h2>Journal</h2>
							<button class="iconbtn flat" type="button" data-tip="Modifier" data-tip-pos="end" aria-label="Modifier le journal" onclick={startEdit}><Icon name="edit" /></button>
						</div>
						<div class="card-b">
							<p>{#if a.description}{a.description}{:else}<span class="muted">Pas encore de note pour cette sortie.</span>{/if}</p>
							<div class="eyebrow">Ressenti de l'effort</div>
							{#if a.rpe}
								<div class="rpe" aria-hidden="true">{#each Array(10) as _, i (i)}<i class:on={i < a.rpe}></i>{/each}</div>
								<div class="muted" style="margin-bottom:14px"><b class="num rv">{a.rpe}</b> / 10 · {f.RPE_WORDS[a.rpe]}</div>
							{:else}
								<p class="muted">Non renseigné</p>
							{/if}
							<div class="eyebrow" style="margin-bottom:6px">Tags</div>
							<div class="row" style="gap:6px">
								{#each a.tags as t (t)}<span class="tag {f.tagColor(t)}">{t}</span>{:else}<span class="muted">Aucun</span>{/each}
							</div>
						</div>
					{/if}
				</section>
			</div>
		</div>

		<section class="card" aria-label="Découpage">
			<div class="card-h">
				<h2>Découpage</h2>
				{#if a.laps.length > 1}
					<div class="seg" role="group" aria-label="Type de découpage">
						<button type="button" aria-pressed={table === 'split'} onclick={() => ((table = 'split'), selectRow(null))}>{units === 'imperial' ? 'Miles' : 'Kilomètres'}</button>
						<button type="button" aria-pressed={table === 'lap'} onclick={() => ((table = 'lap'), selectRow(null))}>Tours</button>
					</div>
				{/if}
			</div>
			<div class="card-b">
				<div class="tablebox">
					<table onpointerleave={() => selectRow(null)}>
						<thead>
							<tr>
								<th>{table === 'split' ? f.distanceUnit(units) : 'Tour'}</th><th>Distance</th><th>Temps</th><th>Allure</th><th></th>
								<th><span data-tip="Allure ajustée à la pente">Ajustée</span></th><th>Dénivelé</th><th>FC</th><th>Cadence</th>
							</tr>
						</thead>
						<tbody>
							{#each rows as r, n (n)}
								<tr class:on={selRow === n} onpointerenter={() => selectRow(r, n)} onclick={() => selectRow(r, n)}>
									<td>{n + 1}</td>
									<td>{f.distance(r.distance, units)}</td>
									<td>{f.duration(r.moving_time)}</td>
									<td>{f.pace(r.speed, units)}</td>
									<td class="bar">
										<div class="pb"><div class="t"><span style:width="{barWidth(r).toFixed(0)}%"></span></div>{#if r.speed === fastest && rows.length > 1}<span class="best">plus rapide</span>{/if}</div>
									</td>
									<td>{f.pace(r.gap_speed, units)}</td>
									<td class="muted">{r.gain === null ? '—' : `+${f.elevation(r.gain, units)} / −${f.elevation(r.loss, units)}`}</td>
									<td>{f.whole(r.avg_hr)}</td>
									<td>{f.whole(r.avg_cad)}</td>
								</tr>
							{/each}
						</tbody>
					</table>
				</div>
			</div>
		</section>
	</div>
{/if}

{#if dialog === 'delete' && a}
	<Modal title="Supprimer cette sortie ?" onclose={() => (dialog = null)}>
		<p class="muted" style="margin:0">« {a.title} », son journal et son fichier d'origine seront définitivement supprimés.</p>
		<div class="row" style="justify-content:flex-end">
			<button class="btn" type="button" onclick={() => (dialog = null)}>Annuler</button>
			<button class="btn danger" type="button" onclick={confirm}><Icon name="trash" />Supprimer</button>
		</div>
	</Modal>
{:else if dialog && a}
	<Modal title={dialog === 'copy' ? 'Copier vers un profil' : 'Déplacer vers un profil'} onclose={() => (dialog = null)}>
		<p class="muted" style="margin:0">
			{dialog === 'copy'
				? "Pour une sortie faite à deux : l'autre personne obtient sa propre sortie, avec son journal."
				: 'La sortie quitte ce profil. Ses zones sont recalculées avec la FC max du nouveau profil.'}
		</p>
		<div>
			{#each others as p (p.id)}
				<button class="pick" type="button" aria-pressed={target === p.id} onclick={() => (target = p.id)}>
					<span class="avatar" style:background={p.color}>{p.name.charAt(0).toUpperCase()}</span><span class="grow">{p.name}</span>
					{#if target === p.id}<Icon name="check" />{/if}
				</button>
			{/each}
		</div>
		{#if dialog === 'copy' && hasHr}
			<button class="switch" type="button" aria-pressed={keepHr} onclick={() => (keepHr = !keepHr)}>
				<span class="tr"></span>
				<span>Conserver la fréquence cardiaque<br /><small class="muted">Désactivé par défaut : c'est la FC de la personne qui portait la montre.</small></span>
			</button>
		{/if}
		<div class="row" style="justify-content:flex-end">
			<button class="btn" type="button" onclick={() => (dialog = null)}>Annuler</button>
			<button class="btn primary" type="button" disabled={!target} onclick={confirm}>{dialog === 'copy' ? 'Copier' : 'Déplacer'}</button>
		</div>
	</Modal>
{/if}

<style>
	.back {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		color: var(--ink-2);
		font-weight: 600;
		font-size: 14px;
		padding: 6px 12px 6px 8px;
		border-radius: 999px;
		margin-left: -8px;
	}
	.back:hover {
		background: var(--surface-2);
		color: var(--ink);
	}
	.meta {
		display: flex;
		gap: 14px;
		flex-wrap: wrap;
		align-items: center;
		color: var(--ink-2);
		margin-top: 8px;
	}
	.meta > span {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.pop {
		position: absolute;
		right: 0;
		top: calc(100% + 8px);
		background: var(--surface);
		border-radius: var(--r-lg);
		box-shadow: var(--pop);
		padding: 8px;
		min-width: 250px;
		z-index: 40;
	}
	.pop button,
	.pop a {
		display: flex;
		align-items: center;
		gap: 10px;
		width: 100%;
		padding: 10px 12px;
		border-radius: 12px;
		font-weight: 600;
		text-align: left;
	}
	.pop button:hover,
	.pop a:hover {
		background: var(--surface-2);
	}
	.pop .dng {
		color: var(--i-rose);
	}
	.dgrid {
		display: grid;
		grid-template-columns: minmax(0, 1fr) 370px;
		gap: 20px;
		align-items: start;
	}
	.col {
		display: flex;
		flex-direction: column;
		gap: 20px;
		min-width: 0;
	}
	.mapcard {
		position: relative;
		overflow: hidden;
	}
	.float {
		position: absolute;
		left: 14px;
		top: 14px;
		z-index: 5;
	}
	.float.seg {
		background: var(--surface);
		box-shadow: var(--pop);
	}
	.float.right {
		left: auto;
		right: 14px;
	}
	.legend {
		position: absolute;
		left: 14px;
		bottom: 14px;
		z-index: 5;
		background: var(--surface);
		border-radius: 16px;
		padding: 10px 14px;
		font-size: 12.5px;
		font-weight: 600;
		color: var(--ink-2);
		box-shadow: var(--pop);
		max-width: calc(100% - 28px);
	}
	.ramp {
		height: 8px;
		width: 170px;
		max-width: 100%;
		border-radius: 4px;
		margin: 5px 0;
		background: linear-gradient(90deg, var(--pace-fast), var(--pace-mid), var(--pace-slow));
	}
	.ends {
		display: flex;
		justify-content: space-between;
		gap: 12px;
	}
	.zs {
		display: flex;
		gap: 12px;
		flex-wrap: wrap;
	}
	.zs i,
	.zn i {
		display: inline-block;
		width: 9px;
		height: 9px;
		border-radius: 50%;
		margin-right: 5px;
	}
	.small {
		font-size: 13px;
	}
	.zbar {
		display: flex;
		gap: 4px;
		height: 40px;
	}
	.zbar span {
		border-radius: 10px;
		min-width: 6px;
		transition: transform 0.15s;
	}
	.zbar span:hover {
		transform: scaleY(1.12);
	}
	.zrow {
		display: grid;
		grid-template-columns: auto minmax(0, 1fr) auto auto;
		gap: 10px;
		align-items: center;
		padding: 8px;
		border-radius: 12px;
	}
	.zrow:hover {
		background: var(--surface-2);
	}
	.zn {
		font: 600 15px var(--font-display);
		display: flex;
		align-items: center;
	}
	.zr {
		font-size: 12.5px;
		color: var(--ink-2);
	}
	.zrow b {
		font: 600 16px var(--font-display);
		font-variant-numeric: tabular-nums;
	}
	.pc {
		font-size: 12.5px;
		color: var(--ink-2);
		min-width: 36px;
		text-align: right;
	}
	.effort {
		display: flex;
		align-items: center;
		gap: 14px;
		background: var(--p-peach);
		border-radius: var(--r-lg);
		padding: 14px 16px;
		margin-top: 12px;
	}
	.ring {
		width: 48px;
		height: 48px;
		border-radius: 16px;
		background: var(--surface);
		color: var(--i-peach);
		display: grid;
		place-items: center;
	}
	.ev {
		font: 600 30px/1 var(--font-display);
	}
	.journal p {
		margin: 0 0 14px;
		max-width: 60ch;
		white-space: pre-wrap;
	}
	.rpe {
		display: flex;
		gap: 4px;
		margin: 6px 0 4px;
	}
	.rpe i,
	.rpe button {
		flex: 1;
		height: 26px;
		border-radius: 8px;
		background: var(--surface-2);
		max-width: 30px;
		transition:
			transform 0.12s,
			background 0.12s;
	}
	.rpe .on {
		background: var(--accent);
	}
	.rpe button:hover {
		transform: scaleY(1.15);
	}
	.rv {
		font-size: 18px;
		color: var(--ink);
	}
	.tablebox {
		overflow-x: auto;
		margin: 0 -8px;
	}
	table {
		border-collapse: separate;
		border-spacing: 0 2px;
		width: 100%;
		min-width: 660px;
	}
	th,
	td {
		padding: 9px 12px;
		text-align: right;
		white-space: nowrap;
	}
	th {
		font-size: 12px;
		font-weight: 700;
		letter-spacing: 0.07em;
		text-transform: uppercase;
		color: var(--ink-3);
	}
	td {
		font: 500 16px var(--font-display);
		font-variant-numeric: tabular-nums;
	}
	th:first-child,
	td:first-child {
		text-align: left;
	}
	td:first-child {
		border-radius: 12px 0 0 12px;
	}
	td:last-child {
		border-radius: 0 12px 12px 0;
	}
	tbody tr:hover td,
	tbody tr.on td {
		background: var(--surface-2);
	}
	td.bar {
		width: 32%;
		min-width: 150px;
	}
	.pb {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.pb .t {
		flex: 1;
		height: 12px;
		border-radius: 6px;
		background: var(--surface-2);
	}
	tbody tr:hover .pb .t,
	tbody tr.on .pb .t {
		background: var(--surface-3);
	}
	.pb .t span {
		display: block;
		height: 100%;
		border-radius: 6px;
		background: var(--pace-fast);
	}
	.best {
		font: 700 11px var(--font-body);
		color: var(--i-lav);
		background: var(--p-lav);
		padding: 2px 8px;
		border-radius: 999px;
	}
	small {
		font-weight: 500;
	}
	@media (max-width: 1180px) {
		.dgrid {
			grid-template-columns: minmax(0, 1fr);
		}
	}
</style>
