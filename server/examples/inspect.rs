//! Development aid: summarises the messages of a FIT file without printing positions.
//! Usage: cargo run --example inspect -- path/to/file.fit
use std::collections::BTreeMap;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    for path in std::env::args().skip(1) {
        let bytes = std::fs::read(&path)?;
        let records = fitparser::from_bytes(&bytes)?;
        println!("== {path} ({} bytes, {} messages)", bytes.len(), records.len());
        let mut kinds: BTreeMap<String, (usize, BTreeMap<String, String>)> = BTreeMap::new();
        for r in &records {
            let e = kinds.entry(r.kind().to_string()).or_default();
            e.0 += 1;
            for f in r.fields() {
                let shown = if f.name().contains("position") || f.name().contains("_lat") || f.name().contains("_long") {
                    "<hidden>".to_string()
                } else {
                    format!("{} [{}]", f.value(), f.units())
                };
                e.1.entry(f.name().to_string()).or_insert(shown);
            }
        }
        for (kind, (n, fields)) in &kinds {
            println!("{kind} x{n}");
            if ["record", "lap", "event", "session", "sport", "file_id", "activity", "device_info"].contains(&kind.as_str()) {
                for (k, v) in fields {
                    println!("    {k} = {v}");
                }
            }
        }
        let mut ev: BTreeMap<String, usize> = BTreeMap::new();
        for r in records.iter().filter(|r| r.kind().to_string() == "event") {
            let get = |n: &str| {
                r.fields()
                    .iter()
                    .find(|f| f.name() == n)
                    .map(|f| f.value().to_string())
                    .unwrap_or_default()
            };
            *ev.entry(format!("{}/{}", get("event"), get("event_type"))).or_default() += 1;
        }
        println!("events: {ev:?}");
    }
    Ok(())
}
