//! Makes sure the folder embedded by `web.rs` exists, so the server compiles
//! even when the web app has not been built yet.
use std::{env, fs, path::PathBuf};

fn main() {
    let dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap()).join("../web/build");
    let _ = fs::create_dir_all(&dir);
    println!("cargo:rerun-if-changed=../web/build");
}
