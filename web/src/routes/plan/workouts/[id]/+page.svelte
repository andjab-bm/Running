<script lang="ts">
	import { page } from '$app/state';
	import { api, ApiError, type Workout } from '#lib/api.ts';
	import { errorText } from '#lib/app.svelte.ts';
	import Icon from '#lib/components/Icon.svelte';
	import WorkoutEditor from '../../WorkoutEditor.svelte';

	let workout = $state<Workout | null>(null);
	let missing = $state(false);
	let error = $state('');
	$effect(() => {
		api.workout(Number(page.params.id)).then(
			(w) => (workout = w),
			(e) => (e instanceof ApiError && (e.status === 404 || e.status === 400) ? (missing = true) : (error = errorText(e)))
		);
	});
</script>

{#if missing}
	<div class="emptybox">
		<div class="big"><Icon name="timer" size={28} /></div>
		<h2>Cette séance n'existe pas</h2>
		<a class="btn primary" href="/plan"><Icon name="back" />Toutes les séances</a>
	</div>
{:else if error}
	<div class="note warn"><Icon name="x" /><span>{error}</span></div>
{:else if workout}
	<WorkoutEditor {workout} />
{:else}
	<div class="skeleton" style="min-height:360px"></div>
{/if}
