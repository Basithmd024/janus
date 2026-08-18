use std::sync::{Arc, Mutex, atomic::{AtomicBool, Ordering}};
use std::time::Duration;
use arboard::Clipboard;
use tauri::{AppHandle, Emitter};
use uuid::Uuid;
use serde::{Serialize, Deserialize};

use crate::server::SharedState;

/// Tracks clipboard state to detect changes and prevent echo loops
pub struct ClipboardState {
    /// The last content hash we saw on the local clipboard
    last_local_hash: Mutex<u64>,
    /// The last content hash we received from a remote device (to suppress echo)
    last_remote_hash: Mutex<u64>,
    /// Whether clipboard sync is currently active
    is_active: AtomicBool,
    /// Handle to abort the polling task
    poll_handle: Mutex<Option<tokio::task::JoinHandle<()>>>,
}

impl ClipboardState {
    pub fn new() -> Self {
        Self {
            last_local_hash: Mutex::new(0),
            last_remote_hash: Mutex::new(0),
            is_active: AtomicBool::new(false),
            poll_handle: Mutex::new(None),
        }
    }

    pub fn is_active(&self) -> bool {
        self.is_active.load(Ordering::SeqCst)
    }
}

/// Simple hash function for clipboard content comparison
fn hash_content(s: &str) -> u64 {
    use std::hash::{Hash, Hasher};
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    s.hash(&mut hasher);
    hasher.finish()
}

/// Payload emitted to the Svelte frontend when clipboard changes
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ClipboardPayload {
    pub content: String,
    pub content_type: String,
    pub source: String, // "local" or "remote"
}

/// Start polling the local macOS clipboard for changes.
/// When a change is detected (and it's not an echo from a remote paste),
/// broadcast it over WebSocket to all connected peers.
pub fn start_clipboard_polling(
    state: SharedState,
    app_handle: AppHandle,
    clipboard_state: Arc<ClipboardState>,
) {
    if clipboard_state.is_active.load(Ordering::SeqCst) {
        return; // Already running
    }

    clipboard_state.is_active.store(true, Ordering::SeqCst);

    let cs = clipboard_state.clone();
    let handle = tokio::spawn(async move {
        // We poll every 800ms — a balance between responsiveness and CPU usage.
        // macOS doesn't provide a push-based clipboard change notification,
        // so polling is the standard approach (same as KDE Connect, LocalSend, etc.)
        let mut interval = tokio::time::interval(Duration::from_millis(800));

        loop {
            interval.tick().await;

            if !cs.is_active.load(Ordering::SeqCst) {
                break;
            }

            // Read the clipboard in a blocking context since arboard isn't async
            let cs_inner = cs.clone();
            let state_inner = state.clone();
            let app_inner = app_handle.clone();

            let _ = tokio::task::spawn_blocking(move || {
                let mut clipboard = match Clipboard::new() {
                    Ok(cb) => cb,
                    Err(e) => {
                        eprintln!("Clipboard access error: {}", e);
                        return;
                    }
                };

                let text = match clipboard.get_text() {
                    Ok(t) => t,
                    Err(_) => return, // No text content or error — skip
                };

                if text.is_empty() {
                    return;
                }

                let content_hash = hash_content(&text);

                // Check if this is the same as the last thing we saw
                let last_local = *cs_inner.last_local_hash.lock().unwrap();
                if content_hash == last_local {
                    return; // No change
                }

                // Check if this is an echo from our own remote paste
                let last_remote = *cs_inner.last_remote_hash.lock().unwrap();
                if content_hash == last_remote {
                    // Update local hash to suppress further checks, but don't broadcast
                    *cs_inner.last_local_hash.lock().unwrap() = content_hash;
                    return;
                }

                // Genuine local clipboard change — broadcast to connected peers
                *cs_inner.last_local_hash.lock().unwrap() = content_hash;

                println!("📋 Local clipboard changed, broadcasting to peers");

                // Broadcast over WebSocket to all connected clients
                let packet = crate::protocol::Packet {
                    r#type: "clipboard.update".to_string(),
                    id: Uuid::new_v4().to_string(),
                    timestamp: std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .unwrap_or_default()
                        .as_secs(),
                    payload: serde_json::json!({
                        "content": text,
                        "contentType": "text/plain"
                    }),
                };

                if let Ok(json) = serde_json::to_string(&packet) {
                    let clients = state_inner.active_ws_clients.lock().unwrap();
                    for (id, tx) in clients.iter() {
                        let msg = axum::extract::ws::Message::Text(json.clone());
                        if tx.send(msg).is_err() {
                            eprintln!("Failed to send clipboard to ws client {}", id);
                        }
                    }
                }

                // Also notify the Svelte frontend
                let _ = app_inner.emit("clipboard-synced", ClipboardPayload {
                    content: if text.len() > 100 {
                        format!("{}…", &text[..100])
                    } else {
                        text
                    },
                    content_type: "text/plain".to_string(),
                    source: "local".to_string(),
                });
            })
            .await;
        }
    });

    *clipboard_state.poll_handle.lock().unwrap() = Some(handle);
}

/// Stop clipboard polling
pub fn stop_clipboard_polling(clipboard_state: &ClipboardState) {
    clipboard_state.is_active.store(false, Ordering::SeqCst);
    if let Some(handle) = clipboard_state.poll_handle.lock().unwrap().take() {
        handle.abort();
    }
}

/// Write incoming remote clipboard content to the local macOS pasteboard.
/// Marks it as "remote" to prevent echo loop.
pub fn write_remote_to_local(
    clipboard_state: &ClipboardState,
    content: &str,
    app_handle: &AppHandle,
) {
    let content_hash = hash_content(content);

    // Mark this content as "came from remote" so we don't re-broadcast it
    *clipboard_state.last_remote_hash.lock().unwrap() = content_hash;
    *clipboard_state.last_local_hash.lock().unwrap() = content_hash;

    // Write to macOS pasteboard
    match Clipboard::new() {
        Ok(mut clipboard) => {
            if let Err(e) = clipboard.set_text(content) {
                eprintln!("Failed to set clipboard: {}", e);
                return;
            }
            println!("📋 Remote clipboard content written to local pasteboard");

            let _ = app_handle.emit("clipboard-synced", ClipboardPayload {
                content: if content.len() > 100 {
                    format!("{}…", &content[..100])
                } else {
                    content.to_string()
                },
                content_type: "text/plain".to_string(),
                source: "remote".to_string(),
            });
        }
        Err(e) => {
            eprintln!("Failed to access clipboard: {}", e);
        }
    }
}
