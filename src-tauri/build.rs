fn main() {
    // If building in release mode, ensure frontend is built and force TAURI_ENV_DEBUG to "false"
    // so tauri-build never points to devUrl (http://localhost:1420) and embeds ../build static assets.
    // This permanently prevents blank white screens if cargo build --release is executed directly.
    if std::env::var("PROFILE").unwrap_or_default() == "release" {
        if std::env::var("TAURI_ENV_DEBUG").is_err() {
            std::env::set_var("TAURI_ENV_DEBUG", "false");
        }

        let build_index = std::path::Path::new("../build/index.html");
        if !build_index.exists() {
            println!(
                "cargo:warning=Frontend build missing. Automatically running 'npm run build'..."
            );
            let _ = std::process::Command::new("npm")
                .arg("run")
                .arg("build")
                .current_dir("..")
                .status();
        }
    }

    tauri_build::build();
}
