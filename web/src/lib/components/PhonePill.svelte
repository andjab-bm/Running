<script lang="ts">
	// Whether a session or route is marked for the phone, and whether the phone has it yet.
	// The phone fetches its plan when it syncs at home; the server only knows what it last said it holds.
	import type { Delivery } from '#lib/api.ts';
	import * as f from '#lib/format.ts';
	import Icon from './Icon.svelte';

	let { delivery, timezone, ontoggle, label }: { delivery: Delivery; timezone: string | null; ontoggle: () => void; label: string } = $props();
</script>

{#if delivery.state === 'delivered'}
	<button class="phone ok" type="button" data-tip="Retirer du téléphone" aria-label="{label} : sur le téléphone. Retirer du téléphone" onclick={ontoggle}>
		<Icon name="phone" size={15} />Sur le téléphone depuis {f.since(delivery.delivered_at!, timezone)}
	</button>
{:else if delivery.state === 'waiting'}
	<button class="phone wait" type="button" data-tip="Arrivera à la prochaine synchro à la maison" aria-label="{label} : en attente du téléphone. Retirer du téléphone" onclick={ontoggle}>
		<Icon name="clock" size={15} />En attente du téléphone
	</button>
{:else}
	<button class="phone" type="button" aria-label="Envoyer {label} sur le téléphone" onclick={ontoggle}><Icon name="phone" size={15} />Envoyer sur le téléphone</button>
{/if}
