<script lang="ts">
	// The steps of a session side by side, each as wide as it lasts and as tall as it is hard.
	import type { Units, WorkoutItem } from '#lib/api.ts';
	import { endText, KINDS, outlineWeight, unfold } from '#lib/workout.ts';

	let { items, units }: { items: WorkoutItem[]; units: Units } = $props();
	const steps = $derived(unfold(items));
	const total = $derived(steps.reduce((a, s) => a + outlineWeight(s), 0) || 1);
</script>

<div class="outline" role="img" aria-label="Déroulé : {steps.length} étape{steps.length > 1 ? 's' : ''}">
	{#each steps as s, i (i)}
		<i class="k-{s.kind}" style:flex={((outlineWeight(s) / total) * 100).toFixed(2)} title="{KINDS[s.kind]} · {endText(s.end, units)}"></i>
	{/each}
</div>
