//! GPX reading and writing.
//!
//! Reading is done on the XML events directly so that per-point extensions
//! (heart rate, cadence) written by Garmin, Strava and others are understood
//! whatever namespace prefix they use.
use quick_xml::{Reader, events::Event};

use super::{ParseError, Parsed, Sample, time};

fn text(raw: &[u8]) -> String {
    let s = String::from_utf8_lossy(raw);
    if s.contains('&') {
        s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    } else {
        s.into_owned()
    }
}

fn local(name: &[u8]) -> &[u8] {
    match name.iter().rposition(|b| *b == b':') {
        Some(i) => &name[i + 1..],
        None => name,
    }
}

/// Reads every `<trk>` of a GPX document as one activity.
pub fn read(bytes: &[u8]) -> Result<Vec<Parsed>, ParseError> {
    let mut reader = Reader::from_reader(bytes);
    let mut buf = Vec::new();
    let mut out: Vec<Parsed> = Vec::new();
    let mut doc_name: Option<String> = None;
    let mut path: Vec<Vec<u8>> = Vec::new();
    let mut cur: Option<Parsed> = None;
    let mut pt: Option<Sample> = None;
    let mut pt_has_time = false;
    let mut untimed_points = 0usize;
    let mut saw_gpx = false;
    // Text of the element being read; entities and CDATA arrive as separate events.
    let mut acc = String::new();

    loop {
        let event = reader.read_event_into(&mut buf).map_err(|e| ParseError::Corrupt(e.to_string()))?;
        match event {
            Event::Eof => break,
            Event::Start(ref e) | Event::Empty(ref e) => {
                let name = local(e.name().as_ref().as_bytes()).to_ascii_lowercase();
                let is_empty = matches!(event, Event::Empty(_));
                match name.as_slice() {
                    b"gpx" => saw_gpx = true,
                    b"trk" => cur = Some(Parsed::default()),
                    // A planned route has no timing: remember it was there, to say so.
                    b"rtept" => untimed_points += 1,
                    b"trkpt" if cur.is_some() => {
                        let mut s = Sample::default();
                        for a in e.attributes().flatten() {
                            let v = text(a.value.as_bytes()).trim().parse::<f64>().ok();
                            match local(a.key.as_ref().as_bytes()) {
                                b"lat" => s.lat = v,
                                b"lon" => s.lon = v,
                                _ => {}
                            }
                        }
                        pt = Some(s);
                        pt_has_time = false;
                        if is_empty {
                            untimed_points += 1;
                            pt = None;
                        }
                    }
                    _ => {}
                }
                if !is_empty {
                    path.push(name);
                    acc.clear();
                }
            }
            Event::Text(ref t) => acc.push_str(&text(t.as_ref().as_bytes())),
            Event::CData(ref t) => {
                let cdata: &str = t.as_ref();
                acc.push_str(cdata)
            }
            Event::GeneralRef(ref r) => {
                // Entities arrive as separate events: `&amp;`, `&#244;`, `&#xF4;`.
                let name: String = {
                    let n: &str = r.as_ref();
                    n.to_string()
                };
                let resolved = match name.as_str() {
                    "amp" => Some('&'),
                    "lt" => Some('<'),
                    "gt" => Some('>'),
                    "quot" => Some('"'),
                    "apos" => Some('\''),
                    n => n
                        .strip_prefix("#x")
                        .or_else(|| n.strip_prefix("#X"))
                        .map(|h| u32::from_str_radix(h, 16))
                        .or_else(|| n.strip_prefix('#').map(str::parse::<u32>))
                        .and_then(Result::ok)
                        .and_then(char::from_u32),
                };
                if let Some(ch) = resolved {
                    acc.push(ch);
                }
            }
            Event::End(_) => {
                let value = acc.trim().to_string();
                acc.clear();
                if !value.is_empty() {
                    let value = value.as_str();
                    let leaf = path.last().map(Vec::as_slice).unwrap_or_default();
                    let parent = path.len().checked_sub(2).map(|i| path[i].as_slice()).unwrap_or_default();
                    if let Some(s) = pt.as_mut() {
                        match leaf {
                            b"ele" => s.ele = value.parse().ok(),
                            b"time" => {
                                if let Some(t) = time::parse_rfc3339(value) {
                                    s.time = t;
                                    pt_has_time = true;
                                }
                            }
                            b"hr" | b"heartrate" => s.hr = value.parse().ok(),
                            b"cad" | b"cadence" | b"runcadence" => s.cad = value.parse().ok(),
                            _ => {}
                        }
                    } else if leaf == b"name" {
                        match (parent, cur.as_mut()) {
                            (b"trk", Some(c)) => c.name = Some(value.to_string()),
                            (b"metadata", _) => doc_name = Some(value.to_string()),
                            _ => {}
                        }
                    } else if leaf == b"type"
                        && parent == b"trk"
                        && let Some(c) = cur.as_mut()
                    {
                        c.sport = Some(value.to_string());
                    }
                }
                let name = path.pop().unwrap_or_default();
                match name.as_slice() {
                    b"trkpt" => {
                        if let (Some(s), Some(c)) = (pt.take(), cur.as_mut()) {
                            if pt_has_time { c.samples.push(s) } else { untimed_points += 1 }
                        }
                    }
                    b"trk" => {
                        if let Some(c) = cur.take()
                            && !c.samples.is_empty()
                        {
                            out.push(c);
                        }
                    }
                    _ => {}
                }
            }
            _ => {}
        }
        buf.clear();
    }

    if !saw_gpx {
        return Err(ParseError::Unrecognised);
    }
    if out.is_empty() {
        return Err(if untimed_points > 0 {
            ParseError::NoTiming
        } else {
            ParseError::Empty
        });
    }
    if out.len() == 1 && out[0].name.is_none() {
        out[0].name = doc_name;
    }
    Ok(out)
}

/// The path of one `<rte>` or `<trk>`: its name and its `(lat, lon, elevation)` points.
pub type NamedPath = (Option<String>, Vec<(f64, f64, Option<f64>)>);

fn entity(name: &str) -> Option<char> {
    match name {
        "amp" => Some('&'),
        "lt" => Some('<'),
        "gt" => Some('>'),
        "quot" => Some('"'),
        "apos" => Some('\''),
        n => n
            .strip_prefix("#x")
            .or_else(|| n.strip_prefix("#X"))
            .map(|h| u32::from_str_radix(h, 16))
            .or_else(|| n.strip_prefix('#').map(str::parse::<u32>))
            .and_then(Result::ok)
            .and_then(char::from_u32),
    }
}

/// Reads every `<rte>` and `<trk>` of a GPX document as a path to follow. Times, heart
/// rate and anything else recorded are ignored: a route says where to go, nothing more.
pub fn read_paths(bytes: &[u8]) -> Result<Vec<NamedPath>, ParseError> {
    let mut reader = Reader::from_reader(bytes);
    let mut buf = Vec::new();
    let mut out: Vec<NamedPath> = Vec::new();
    let mut doc_name: Option<String> = None;
    let mut path: Vec<Vec<u8>> = Vec::new();
    let mut cur: Option<NamedPath> = None;
    let mut pt: Option<(Option<f64>, Option<f64>, Option<f64>)> = None;
    let mut saw_gpx = false;
    let mut acc = String::new();

    fn keep(cur: &mut Option<NamedPath>, p: (Option<f64>, Option<f64>, Option<f64>)) {
        if let (Some(c), (Some(lat), Some(lon), ele)) = (cur.as_mut(), p)
            && (-90.0..=90.0).contains(&lat)
            && (-180.0..=180.0).contains(&lon)
        {
            c.1.push((lat, lon, ele));
        }
    }

    loop {
        let event = reader.read_event_into(&mut buf).map_err(|e| ParseError::Corrupt(e.to_string()))?;
        match event {
            Event::Eof => break,
            Event::Start(ref e) | Event::Empty(ref e) => {
                let name = local(e.name().as_ref().as_bytes()).to_ascii_lowercase();
                let is_empty = matches!(event, Event::Empty(_));
                match name.as_slice() {
                    b"gpx" => saw_gpx = true,
                    b"trk" | b"rte" => cur = Some((None, Vec::new())),
                    b"trkpt" | b"rtept" if cur.is_some() => {
                        let mut p = (None, None, None);
                        for a in e.attributes().flatten() {
                            let v = text(a.value.as_bytes()).trim().parse::<f64>().ok();
                            match local(a.key.as_ref().as_bytes()) {
                                b"lat" => p.0 = v,
                                b"lon" => p.1 = v,
                                _ => {}
                            }
                        }
                        if is_empty {
                            keep(&mut cur, p);
                        } else {
                            pt = Some(p);
                        }
                    }
                    _ => {}
                }
                if !is_empty {
                    path.push(name);
                    acc.clear();
                }
            }
            Event::Text(ref t) => acc.push_str(&text(t.as_ref().as_bytes())),
            Event::CData(ref t) => {
                let cdata: &str = t.as_ref();
                acc.push_str(cdata)
            }
            Event::GeneralRef(ref r) => {
                let name: &str = r.as_ref();
                if let Some(ch) = entity(name) {
                    acc.push(ch);
                }
            }
            Event::End(_) => {
                let value = acc.trim().to_string();
                acc.clear();
                let leaf = path.last().cloned().unwrap_or_default();
                let parent = path.len().checked_sub(2).map(|i| path[i].clone()).unwrap_or_default();
                if !value.is_empty() {
                    match (leaf.as_slice(), parent.as_slice()) {
                        (b"ele", _) => {
                            if let Some(p) = pt.as_mut() {
                                p.2 = value.parse().ok();
                            }
                        }
                        (b"name", b"trk" | b"rte") => {
                            if let Some(c) = cur.as_mut() {
                                c.0 = Some(value);
                            }
                        }
                        (b"name", b"metadata") => doc_name = Some(value),
                        _ => {}
                    }
                }
                path.pop();
                match leaf.as_slice() {
                    b"trkpt" | b"rtept" => {
                        if let Some(p) = pt.take() {
                            keep(&mut cur, p);
                        }
                    }
                    b"trk" | b"rte" => {
                        if let Some(c) = cur.take()
                            && !c.1.is_empty()
                        {
                            out.push(c);
                        }
                    }
                    _ => {}
                }
            }
            _ => {}
        }
        buf.clear();
    }

    if !saw_gpx {
        return Err(ParseError::Unrecognised);
    }
    if out.is_empty() {
        return Err(ParseError::Empty);
    }
    if out.len() == 1 && out[0].0.is_none() {
        out[0].0 = doc_name;
    }
    Ok(out)
}

/// Writes a path as a GPX 1.1 track without times, which other applications read as a route to follow.
pub fn write_path(name: &str, lat: &[f64], lon: &[f64], ele: Option<&[f64]>) -> String {
    let mut s = String::with_capacity(lat.len() * 64 + 256);
    s.push_str("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    s.push_str("<gpx version=\"1.1\" creator=\"Running\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
    s.push_str(&format!(
        " <metadata><name>{0}</name></metadata>\n <trk>\n  <name>{0}</name>\n  <trkseg>\n",
        escape(name)
    ));
    for i in 0..lat.len() {
        s.push_str(&format!("   <trkpt lat=\"{:.7}\" lon=\"{:.7}\">", lat[i], lon[i]));
        if let Some(e) = ele {
            s.push_str(&format!("<ele>{:.1}</ele>", e[i]));
        }
        s.push_str("</trkpt>\n");
    }
    s.push_str("  </trkseg>\n </trk>\n</gpx>\n");
    s
}

fn escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

/// One point to export. Positions are required by GPX, so points without a fix are skipped by the caller.
pub struct ExportPoint {
    pub time: f64,
    pub lat: f64,
    pub lon: f64,
    pub ele: Option<f64>,
    pub hr: Option<f64>,
    /// Steps per minute (both legs).
    pub cad: Option<f64>,
}

/// Writes a GPX 1.1 document with heart rate and cadence as Garmin TrackPointExtension values.
pub fn write(name: &str, points: &[ExportPoint]) -> String {
    let mut s = String::with_capacity(points.len() * 160 + 512);
    s.push_str("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    s.push_str("<gpx version=\"1.1\" creator=\"Running\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n");
    if let Some(first) = points.first() {
        s.push_str(&format!(
            " <metadata><time>{}</time></metadata>\n",
            time::format_rfc3339(first.time)
        ));
    }
    s.push_str(&format!(
        " <trk>\n  <name>{}</name>\n  <type>running</type>\n  <trkseg>\n",
        escape(name)
    ));
    for p in points {
        s.push_str(&format!("   <trkpt lat=\"{:.7}\" lon=\"{:.7}\">", p.lat, p.lon));
        if let Some(e) = p.ele {
            s.push_str(&format!("<ele>{e:.1}</ele>"));
        }
        s.push_str(&format!("<time>{}</time>", time::format_rfc3339(p.time)));
        if p.hr.is_some() || p.cad.is_some() {
            s.push_str("<extensions><gpxtpx:TrackPointExtension>");
            if let Some(h) = p.hr {
                s.push_str(&format!("<gpxtpx:hr>{}</gpxtpx:hr>", h.round() as i64));
            }
            if let Some(c) = p.cad {
                // The extension stores cadence per leg, as devices do.
                s.push_str(&format!("<gpxtpx:cad>{}</gpxtpx:cad>", (c / 2.0).round() as i64));
            }
            s.push_str("</gpxtpx:TrackPointExtension></extensions>");
        }
        s.push_str("</trkpt>\n");
    }
    s.push_str("  </trkseg>\n </trk>\n</gpx>\n");
    s
}
