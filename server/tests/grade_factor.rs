//! The phone paces a hilly route with the same energy-cost factor as the server's grade-adjusted
//! pace. This test writes the factor at every percent of grade to `tests/fixtures/grade-factor.json`,
//! which `android/core` checks its own port against.
//!
//! To refresh the committed file after an intentional change: `UPDATE_FIXTURES=1 cargo test --test grade_factor`
use running_server::pipeline::metrics::grade_factor;

fn table() -> String {
    let rows: Vec<String> = (-40..=40)
        .map(|p| {
            let g = f64::from(p) / 100.0;
            format!("  {{ \"grade\": {g:.2}, \"factor\": {:.9} }}", grade_factor(g))
        })
        .collect();
    format!("[\n{}\n]\n", rows.join(",\n"))
}

#[test]
fn committed_grade_factors_match_the_server() {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/grade-factor.json");
    if std::env::var_os("UPDATE_FIXTURES").is_some() {
        std::fs::write(&path, table()).unwrap();
    }
    let committed = std::fs::read_to_string(&path).unwrap_or_default().replace("\r\n", "\n");
    assert!(
        committed == table(),
        "tests/fixtures/grade-factor.json is out of date: run `UPDATE_FIXTURES=1 cargo test --test grade_factor`"
    );
}
