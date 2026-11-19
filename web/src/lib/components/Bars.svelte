<script lang="ts">
	// Vertical bars for periods: rounded tops, the period in progress hatched, empty periods as a stub.
	export interface Bar {
		label: string;
		value: number;
		/** What the bar says on hover and to screen readers. */
		text: string;
		current?: boolean;
	}
	let { items, color = 'var(--accent)', title }: { items: Bar[]; color?: string; title: string } = $props();
	const max = $derived(Math.max(1e-9, ...items.map((i) => i.value)));
</script>

<div class="bars" role="img" aria-label="{title} : {items.map((i) => `${i.label} ${i.text}`).join(', ')}" style:--bar={color}>
	{#each items as i, n (n)}
		{@const h = Math.round((i.value / max) * 150)}
		<div class="b" class:cur={i.current} class:zero={!i.value} class:start={n < 3} class:end={n >= items.length - 3} style:--h="{h}px">
			<em>{i.text}</em>
			<i style:height="{h}px"></i>
			<span>{i.label}</span>
		</div>
	{/each}
</div>

<style>
	.bars {
		display: flex;
		align-items: flex-end;
		gap: 6px;
		height: 204px;
		padding-top: 26px;
	}
	.b {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: flex-end;
		gap: 6px;
		height: 100%;
		position: relative;
	}
	.b i {
		display: block;
		width: 100%;
		max-width: 44px;
		border-radius: 10px 10px 4px 4px;
		background: var(--bar);
		min-height: 3px;
		transition:
			filter 0.15s,
			transform 0.15s;
		transform-origin: bottom;
	}
	.b:hover i {
		transform: scaleY(1.04);
		filter: brightness(1.08);
	}
	.b.cur i {
		background: repeating-linear-gradient(135deg, var(--bar) 0 5px, color-mix(in srgb, var(--bar) 55%, var(--surface)) 5px 10px);
	}
	.b.zero i {
		background: var(--surface-3);
	}
	.b span {
		font-size: 11px;
		font-weight: 600;
		color: var(--ink-3);
		white-space: nowrap;
	}
	.b em {
		font: 600 12px var(--font-display);
		font-style: normal;
		position: absolute;
		bottom: calc(var(--h) + 28px);
		opacity: 0;
		transition: opacity 0.12s;
		background: var(--ink);
		color: var(--bg);
		padding: 2px 7px;
		border-radius: 7px;
		white-space: nowrap;
		z-index: 5;
		pointer-events: none;
	}
	.b:hover em {
		opacity: 1;
	}
	/* Bubbles near the edges open inwards, so that none reaches past the chart. */
	.b.start em {
		left: 0;
	}
	.b.end em {
		right: 0;
	}
	@media (max-width: 900px) {
		.bars {
			gap: 3px;
		}
		.b span {
			font-size: 9.5px;
		}
	}
</style>
