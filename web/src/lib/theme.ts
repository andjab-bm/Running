// Concrete colours of the current palette, for the canvas and map libraries that cannot read CSS variables.
// Call it inside a `$derived` that also reads `app.dark` and `app.theme`, so it is read again when the palette changes.
export function themeColors() {
	const css = (name: string) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
	return {
		accent: css('--accent'),
		sky: css('--c-sky'),
		rose: css('--c-rose'),
		mint: css('--c-mint'),
		fast: css('--pace-fast'),
		ink3: css('--ink-3'),
		grid: css('--surface-2'),
		surface: css('--surface'),
		land: css('--map-land')
	};
}
