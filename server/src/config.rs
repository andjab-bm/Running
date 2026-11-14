//! Runtime configuration, read from environment variables with working defaults.
use std::{env, path::PathBuf};

/// Public vector style used when `MAP_STYLE_URL` is not set.
pub const DEFAULT_MAP_STYLE_URL: &str = "https://tiles.openfreemap.org/styles/positron";

#[derive(Clone, Debug)]
pub struct Config {
    /// TCP port to listen on (`PORT`, default 8080).
    pub port: u16,
    /// Directory holding the database and original files (`DATA_DIR`, default `./data`).
    pub data_dir: PathBuf,
    /// Largest accepted upload in bytes (`MAX_UPLOAD_MB`, default 50).
    pub max_upload_bytes: usize,
    /// Map style URL handed to the browser (`MAP_STYLE_URL`); empty disables the basemap.
    pub map_style_url: String,
}

impl Config {
    pub fn from_env() -> anyhow::Result<Self> {
        Self::from_lookup(|k| env::var(k).ok())
    }

    /// Builds the configuration from any key lookup, so tests need not touch the process environment.
    pub fn from_lookup(get: impl Fn(&str) -> Option<String>) -> anyhow::Result<Self> {
        let port = match get("PORT") {
            Some(v) => v.parse().map_err(|_| anyhow::anyhow!("PORT must be a port number, got {v:?}"))?,
            None => 8080,
        };
        let max_mb: usize = match get("MAX_UPLOAD_MB") {
            Some(v) => v
                .parse()
                .map_err(|_| anyhow::anyhow!("MAX_UPLOAD_MB must be a whole number, got {v:?}"))?,
            None => 50,
        };
        Ok(Self {
            port,
            data_dir: get("DATA_DIR").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("./data")),
            max_upload_bytes: max_mb * 1024 * 1024,
            map_style_url: get("MAP_STYLE_URL").unwrap_or_else(|| DEFAULT_MAP_STYLE_URL.to_string()),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_apply_when_nothing_is_set() {
        let c = Config::from_lookup(|_| None).unwrap();
        assert_eq!(c.port, 8080);
        assert_eq!(c.data_dir, PathBuf::from("./data"));
        assert_eq!(c.max_upload_bytes, 50 * 1024 * 1024);
        assert_eq!(c.map_style_url, DEFAULT_MAP_STYLE_URL);
    }

    #[test]
    fn empty_map_style_disables_the_basemap() {
        let c = Config::from_lookup(|k| (k == "MAP_STYLE_URL").then(String::new)).unwrap();
        assert!(c.map_style_url.is_empty());
    }

    #[test]
    fn invalid_port_is_reported() {
        let err = Config::from_lookup(|k| (k == "PORT").then(|| "abc".to_string())).unwrap_err();
        assert!(err.to_string().contains("PORT"));
    }
}
