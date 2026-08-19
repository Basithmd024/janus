use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use axum::{
    extract::{Path, State, WebSocketUpgrade, ws::{WebSocket, Message}},
    routing::{get, post},
    Router,
    response::IntoResponse,
    Json,
};
use tower_http::cors::CorsLayer;
use tokio::net::TcpListener;
use tokio_rustls::TlsAcceptor;
use tokio_rustls::rustls::ServerConfig;
use serde::Deserialize;
use uuid::Uuid;
use tauri::{AppHandle, Emitter};

use crate::protocol::{Packet, PrepareUploadRequest, PrepareUploadResponse, FileMetadata, DeviceInfo};
use crate::security::{Identity, PairedDeviceStore, save_paired_device};
use crate::clipboard::ClipboardState;

// Active transfer structure
#[allow(dead_code)]
pub struct ActiveTransfer {
    pub session_id: String,
    pub files: Vec<FileMetadata>,
    pub received_bytes: HashMap<String, u64>, // file_hash -> bytes
}

pub fn show_macos_notification(title: &str, body: &str) {
    #[cfg(target_os = "macos")]
    {
        let escaped_title = title.replace('\\', "\\\\").replace('"', "\\\"");
        let escaped_body = body.replace('\\', "\\\\").replace('"', "\\\"");
        let script = format!(
            "display notification \"{}\" with title \"{}\"",
            escaped_body, escaped_title
        );
        let _ = std::process::Command::new("osascript")
            .arg("-e")
            .arg(&script)
            .spawn();
    }
}

// Global state
pub struct ServerState {
    pub config_dir: PathBuf,
    pub identity: Identity,
    pub active_transfers: Mutex<HashMap<String, ActiveTransfer>>,
    pub active_ws_clients: Mutex<HashMap<String, tokio::sync::mpsc::UnboundedSender<Message>>>,
    pub client_fingerprints: Mutex<HashMap<String, String>>, // client_id -> fingerprint
    pub active_devices: Mutex<HashMap<String, (String, DeviceInfo)>>, // fingerprint -> (client_id, DeviceInfo)
    pub current_pairing_pin: Mutex<Option<String>>,
    pub last_telemetry: Mutex<Option<serde_json::Value>>,
    pub recent_notifications: Mutex<Vec<serde_json::Value>>,
    pub call_history: Mutex<Vec<serde_json::Value>>,
    pub sms_messages: Mutex<Vec<serde_json::Value>>,
    pub app_handle: AppHandle,
    pub clipboard_state: Arc<ClipboardState>,
}

pub type SharedState = Arc<ServerState>;

pub async fn start_server(
    state: SharedState,
    port: u16,
) -> Result<(), String> {
    // Parse certs and key
    let certs = rustls_pemfile::certs(&mut state.identity.cert_pem.as_bytes())
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| format!("Failed to parse cert: {}", e))?;
    
    let key = rustls_pemfile::private_key(&mut state.identity.key_pem.as_bytes())
        .map_err(|e| format!("Failed to parse private key: {}", e))?
        .ok_or_else(|| "No private key found in pem".to_string())?;

    // Create server config
    let server_config = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(certs, key)
        .map_err(|e| format!("Failed to build server config: {}", e))?;

    let acceptor = TlsAcceptor::from(Arc::new(server_config));

    // Router setup
    let app = Router::new()
        .route("/api/v1/ws", get(ws_handler))
        .route("/api/v1/prepare-upload", post(prepare_upload))
        .route("/api/v1/upload/:session_id/:file_hash", post(upload_file))
        .layer(CorsLayer::permissive())
        .with_state(state.clone());

    let addr = SocketAddr::new(IpAddr::V4(std::net::Ipv4Addr::new(0, 0, 0, 0)), port);
    let listener = TcpListener::bind(addr).await
        .map_err(|e| format!("Failed to bind to {}: {}", addr, e))?;

    println!("HTTPS Server listening on {}", addr);

    tokio::spawn(async move {
        loop {
            let (stream, peer_addr) = match listener.accept().await {
                Ok(val) => val,
                Err(e) => {
                    eprintln!("Failed to accept connection: {}", e);
                    continue;
                }
            };

            let acceptor = acceptor.clone();
            let app = app.clone();

            tokio::spawn(async move {
                let tls_stream = match acceptor.accept(stream).await {
                    Ok(stream) => stream,
                    Err(e) => {
                        eprintln!("TLS Handshake failed for {}: {}", peer_addr, e);
                        return;
                    }
                };

                // Serve connection — must use HTTP/1 with upgrades for WebSocket support
                let io = hyper_util::rt::TokioIo::new(tls_stream);
                let service = hyper_util::service::TowerToHyperService::new(app);
                if let Err(err) = hyper_util::server::conn::auto::Builder::new(
                    hyper_util::rt::TokioExecutor::new()
                )
                .serve_connection_with_upgrades(io, service)
                .await {
                    eprintln!("Error serving connection: {:?}", err);
                }
            });
        }
    });

    Ok(())
}

// WebSocket handler
async fn ws_handler(
    ws: WebSocketUpgrade,
    State(state): State<SharedState>,
) -> impl IntoResponse {
    ws.on_upgrade(|socket| handle_socket(socket, state))
}

async fn handle_socket(mut socket: WebSocket, state: SharedState) {
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
    let client_id = Uuid::new_v4().to_string();

    // Store tx in state
    state.active_ws_clients.lock().unwrap().insert(client_id.clone(), tx);

    println!("New WebSocket connection established: {}", client_id);

    // Loop for receiving messages
    loop {
        tokio::select! {
            Some(msg) = rx.recv() => {
                if socket.send(msg).await.is_err() {
                    break;
                }
            }
            result = socket.recv() => {
                match result {
                    Some(Ok(msg)) => {
                        match msg {
                            Message::Text(text) => {
                                match serde_json::from_str::<Packet>(&text) {
                                    Ok(packet) => {
                                        handle_packet(packet, &client_id, &state).await;
                                    }
                                    Err(e) => {
                                        eprintln!("Failed to parse WebSocket Packet: {}. Error: {}", text, e);
                                    }
                                }
                            }
                            Message::Binary(bin_bytes) => {
                                if !bin_bytes.is_empty() {
                                    match bin_bytes[0] {
                                        0x02 => {
                                            let frame_data = bin_bytes[1..].to_vec();
                                            let _ = state.app_handle.emit("screencast-frame", frame_data);
                                        }
                                        0x04 => {
                                            let audio_data = bin_bytes[1..].to_vec();
                                            let _ = state.app_handle.emit("call-audio-frame", audio_data);
                                        }
                                        _ => {}
                                    }
                                }
                            }
                            Message::Ping(payload) => {
                                if socket.send(Message::Pong(payload)).await.is_err() {
                                    break;
                                }
                            }
                            Message::Pong(_) => {
                                // WebSocket keepalive acknowledged
                            }
                            Message::Close(_) => {
                                break;
                            }
                        }
                    }
                    _ => break,
                }
            }
        }
    }

    // Clean up
    state.active_ws_clients.lock().unwrap().remove(&client_id);
    let fingerprint = state.client_fingerprints.lock().unwrap().remove(&client_id);
    if let Some(fp) = fingerprint {
        println!("WebSocket connection closed for device: {} (connection: {})", fp, client_id);
        
        // Remove from active_devices if this connection is the one registered
        let mut active_devices = state.active_devices.lock().unwrap();
        let should_remove = if let Some((active_client_id, _)) = active_devices.get(&fp) {
            active_client_id == &client_id
        } else {
            false
        };
        if should_remove {
            active_devices.remove(&fp);
        }
        
        let _ = state.app_handle.emit("device-removed", fp);
    } else {
        println!("WebSocket connection closed (unregistered): {}", client_id);
    }
}

async fn handle_packet(packet: Packet, client_id: &str, state: &SharedState) {
    match packet.r#type.as_str() {
        "pairing.request" => {
            #[derive(Deserialize)]
            struct PairingPayload {
                pin: String,
                device_name: String,
                fingerprint: String,
                device_type: String,
            }

            if let Ok(payload) = serde_json::from_value::<PairingPayload>(packet.payload) {
                let pin_guard = state.current_pairing_pin.lock().unwrap();
                let pin_matches = pin_guard.as_ref() == Some(&payload.pin);

                let response_payload = if pin_matches {
                    // Success! Store device
                    let store = PairedDeviceStore {
                        fingerprint: payload.fingerprint.clone(),
                        name: payload.device_name.clone(),
                        added_at: std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .unwrap_or_default()
                            .as_secs(),
                    };
                    
                    if let Err(e) = save_paired_device(state.config_dir.clone(), store) {
                        eprintln!("Failed to save paired device: {}", e);
                        serde_json::json!({ "status": "error", "message": "Failed to save pairing" })
                    } else {
                        // Notify Svelte frontend that pairing was successful!
                        let _ = state.app_handle.emit("device-paired", DeviceInfo {
                            name: payload.device_name,
                            ip: "".to_string(), // websocket connection doesn't require IP display here
                            port: 0,
                            fingerprint: payload.fingerprint,
                            device_type: payload.device_type,
                            paired: true,
                        });
                        serde_json::json!({ "status": "success", "fingerprint": state.identity.fingerprint })
                    }
                } else {
                    serde_json::json!({ "status": "denied", "message": "Invalid PIN" })
                };

                // Send response
                if let Some(tx) = state.active_ws_clients.lock().unwrap().get(client_id) {
                    let response = Packet {
                        r#type: "pairing.response".to_string(),
                        id: Uuid::new_v4().to_string(),
                        timestamp: packet.timestamp,
                        payload: response_payload,
                    };
                    if let Ok(text) = serde_json::to_string(&response) {
                        let _ = tx.send(Message::Text(text));
                    }
                }
            }
        }
        "clipboard.update" => {
            // Extract text content from the clipboard update payload
            if let Some(content) = packet.payload.get("content").and_then(|v| v.as_str()) {
                // Write to the local macOS pasteboard (with echo-loop prevention)
                crate::clipboard::write_remote_to_local(
                    &state.clipboard_state,
                    content,
                    &state.app_handle,
                );

                // Show native macOS notification
                let truncated = if content.len() > 60 {
                    format!("{}...", &content[..60])
                } else {
                    content.to_string()
                };
                show_macos_notification("Janus Clipboard Synced", &truncated);
            }
            // Also forward the raw event for Svelte UI notifications
            let _ = state.app_handle.emit("remote-clipboard-update", packet.payload);
        }
        "notification.new" => {
            let title = packet.payload.get("title")
                .and_then(|v| v.as_str())
                .or_else(|| packet.payload.get("app_name").and_then(|v| v.as_str()))
                .unwrap_or("Janus Notification");
            let text = packet.payload.get("text")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            
            show_macos_notification(title, text);

            // Store in persistent state
            {
                let mut notifs = state.recent_notifications.lock().unwrap();
                let notif_id = packet.payload.get("notification_id").and_then(|v| v.as_str()).unwrap_or("");
                notifs.retain(|n| n.get("notification_id").and_then(|v| v.as_str()).unwrap_or("") != notif_id);
                notifs.push(packet.payload.clone());
                if notifs.len() > 50 {
                    notifs.remove(0);
                }
            }

            // Forward the full notification payload to the Svelte frontend
            println!("🔔 Received notification from Android: {:?}", title);
            let _ = state.app_handle.emit("notification-new", packet.payload);
        }
        "notification.dismiss" => {
            // Forward the dismiss event to the Svelte frontend
            let notif_id = packet.payload.get("notification_id")
                .and_then(|v| v.as_str())
                .unwrap_or("unknown");
            println!("🔔 Notification dismissed on Android: {}", notif_id);
            let _ = state.app_handle.emit("notification-dismiss", packet.payload);
        }
        "call.incoming" => {
            let number = packet.payload.get("number")
                .and_then(|v| v.as_str())
                .unwrap_or("Unknown Number");
            let name = packet.payload.get("name")
                .and_then(|v| v.as_str())
                .unwrap_or("Unknown Caller");
            
            let message = format!("Incoming call from {} ({})", name, number);
            show_macos_notification("📞 Incoming Call", &message);

            println!("📞 Received incoming call notification from Android");
            let _ = state.app_handle.emit("call-incoming", packet.payload);
        }
        "call.state" => {
            let state_str = packet.payload.get("state")
                .and_then(|v| v.as_str())
                .unwrap_or("unknown");
            println!("📞 Call state updated on Android: {}", state_str);
            let _ = state.app_handle.emit("call-state", packet.payload);
        }
        "calls.list" => {
            if let Some(calls) = packet.payload.get("calls").and_then(|v| v.as_array()) {
                *state.call_history.lock().unwrap() = calls.clone();
            }
            let call_count = packet.payload.get("calls")
                .and_then(|v| v.as_array())
                .map(|a| a.len())
                .unwrap_or(0);
            println!("📞 Received call history from Android: {} calls", call_count);
            let _ = state.app_handle.emit("calls-list", packet.payload);
        }
        "sms.list" => {
            if let Some(messages) = packet.payload.get("messages").and_then(|v| v.as_array()) {
                *state.sms_messages.lock().unwrap() = messages.clone();
            }
            let sms_count = packet.payload.get("messages")
                .and_then(|v| v.as_array())
                .map(|a| a.len())
                .unwrap_or(0);
            println!("💬 Received SMS messages from Android: {} messages", sms_count);
            let _ = state.app_handle.emit("sms-list", packet.payload);
        }
        "device.status" => {
            println!("📡 Received device telemetry: {}", packet.payload);
            if let Some(tray) = state.app_handle.tray_by_id("main_tray") {
                let battery = packet.payload.get("battery_level").and_then(|v| v.as_i64()).unwrap_or(0);
                let charging_symbol = if packet.payload.get("is_charging").and_then(|v| v.as_bool()).unwrap_or(false) { "⚡" } else { "🔋" };
                let _ = tray.set_tooltip(Some(format!("Janus: Phone Connected ({}% {})", battery, charging_symbol)));
            }
            {
                let mut t = state.last_telemetry.lock().unwrap();
                if let Some(existing) = t.as_mut() {
                    if let (Some(existing_obj), Some(new_obj)) = (existing.as_object_mut(), packet.payload.as_object()) {
                        for (k, v) in new_obj {
                            existing_obj.insert(k.clone(), v.clone());
                        }
                    } else {
                        *existing = packet.payload.clone();
                    }
                } else {
                    *t = Some(packet.payload.clone());
                }
            }
            let _ = state.app_handle.emit("device-status", packet.payload);
        }
        "device.ready" => {
            println!("🟢 Phone confirmed connected and ready: {}", packet.payload);
            {
                let mut t = state.last_telemetry.lock().unwrap();
                if let Some(existing) = t.as_mut() {
                    if let (Some(existing_obj), Some(new_obj)) = (existing.as_object_mut(), packet.payload.as_object()) {
                        for (k, v) in new_obj {
                            existing_obj.insert(k.clone(), v.clone());
                        }
                    }
                } else {
                    *t = Some(packet.payload.clone());
                }
            }
            let _ = state.app_handle.emit("device-ready", packet.payload.clone());
            let _ = state.app_handle.emit("device-status", packet.payload);
        }
        "device.register" => {
            #[derive(Deserialize)]
            struct RegisterPayload {
                fingerprint: String,
                device_name: String,
                device_type: String,
                ip: Option<String>,
                port: Option<u16>,
            }

            match serde_json::from_value::<RegisterPayload>(packet.payload.clone()) {
                Ok(payload) => {
                    println!("📱 Device registered via WebSocket: {} ({})", payload.device_name, payload.fingerprint);

                    // Auto-pair on registration if not already saved (Trust-on-First-Sight)
                    let paired_devices = crate::security::load_paired_devices(state.config_dir.clone()).unwrap_or_default();
                    let is_paired = paired_devices.iter().any(|d| d.fingerprint == payload.fingerprint);

                    if !is_paired {
                        println!("🤝 Auto-pairing & approving device: {} ({})", payload.device_name, payload.fingerprint);
                        let new_paired = crate::security::PairedDeviceStore {
                            fingerprint: payload.fingerprint.clone(),
                            name: payload.device_name.clone(),
                            added_at: std::time::SystemTime::now()
                                .duration_since(std::time::UNIX_EPOCH)
                                .unwrap_or_default()
                                .as_secs(),
                        };
                        let _ = crate::security::save_paired_device(state.config_dir.clone(), new_paired);
                    }

                    // Send registration success packet back to phone
                    if let Some(tx) = state.active_ws_clients.lock().unwrap().get(client_id) {
                        let ack_packet = crate::protocol::Packet {
                            r#type: "registration.success".to_string(),
                            id: Uuid::new_v4().to_string(),
                            timestamp: std::time::SystemTime::now()
                                .duration_since(std::time::UNIX_EPOCH)
                                .unwrap_or_default()
                                .as_secs(),
                            payload: serde_json::json!({
                                "status": "connected",
                                "server_name": "Janus Mac Host",
                                "fingerprint": state.identity.fingerprint
                            }),
                        };
                        if let Ok(text) = serde_json::to_string(&ack_packet) {
                            let _ = tx.send(Message::Text(text));
                        }
                    }

                    let device_info = DeviceInfo {
                        name: payload.device_name,
                        ip: payload.ip.unwrap_or_default(),
                        port: payload.port.unwrap_or(0),
                        fingerprint: payload.fingerprint.clone(),
                        device_type: payload.device_type,
                        paired: true,
                    };

                    // Map this client_id to the device fingerprint
                    state.client_fingerprints.lock().unwrap().insert(client_id.to_string(), payload.fingerprint.clone());

                    // Store in active devices mapping fingerprint -> (client_id, DeviceInfo)
                    state.active_devices.lock().unwrap().insert(payload.fingerprint, (client_id.to_string(), device_info.clone()));

                    // Emit device-discovered so the frontend marks it online
                    let _ = state.app_handle.emit("device-discovered", device_info);

                    // Auto-start clipboard sync when a device connects
                    if !state.clipboard_state.is_active() {
                        println!("📋 Auto-starting clipboard sync (device connected)");
                        crate::clipboard::start_clipboard_polling(
                            state.clone(),
                            state.app_handle.clone(),
                            state.clipboard_state.clone(),
                        );
                    }
                }
                Err(e) => {
                    eprintln!("Failed to parse RegisterPayload from packet: {}. Error: {}", packet.payload, e);
                }
            }
        }
        _ => {}
    }
}

// REST prepare upload
async fn prepare_upload(
    State(state): State<SharedState>,
    Json(payload): Json<PrepareUploadRequest>,
) -> impl IntoResponse {
    let session_id = Uuid::new_v4().to_string();
    
    let mut transfers = state.active_transfers.lock().unwrap();
    let transfer = ActiveTransfer {
        session_id: session_id.clone(),
        files: payload.files.clone(),
        received_bytes: HashMap::new(),
    };
    transfers.insert(session_id.clone(), transfer);

    let accepted_files = payload.files.iter().map(|f| f.hash.clone()).collect();
    
    println!("Prepared upload session: {}", session_id);
    
    // Notify Svelte frontend that a file transfer is starting
    let _ = state.app_handle.emit("transfer-started", &session_id);

    Json(PrepareUploadResponse {
        session_id,
        accepted_files,
    })
}

// REST upload file chunk/file
async fn upload_file(
    State(state): State<SharedState>,
    Path((session_id, file_hash)): Path<(String, String)>,
    body: axum::body::Body,
) -> impl IntoResponse {
    // Look up session and file metadata first
    let (file_name, file_size) = {
        let transfers = state.active_transfers.lock().unwrap();
        if let Some(transfer) = transfers.get(&session_id) {
            if let Some(file_meta) = transfer.files.iter().find(|f| f.hash == file_hash) {
                (file_meta.name.clone(), file_meta.size)
            } else {
                return (axum::http::StatusCode::NOT_FOUND, "File not found in session").into_response();
            }
        } else {
            return (axum::http::StatusCode::NOT_FOUND, "Session not found").into_response();
        }
    };

    // Write to Downloads directory — streaming, no full-body buffering
    let downloads_dir = match dirs::download_dir() {
        Some(dir) => dir,
        None => {
            return (
                axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                "Could not locate Downloads directory",
            ).into_response();
        }
    };
    
    let file_path = downloads_dir.join(&file_name);
    println!("Streaming file to {:?}", file_path);

    let mut file = match tokio::fs::File::create(&file_path).await {
        Ok(f) => f,
        Err(e) => {
            return (
                axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                format!("Failed to create file: {}", e),
            ).into_response();
        }
    };

    // Stream body chunks directly to disk
    use futures_util::StreamExt;
    use tokio::io::AsyncWriteExt;
    let mut stream = body.into_data_stream();
    let mut bytes_written: u64 = 0;

    while let Some(chunk_result) = stream.next().await {
        match chunk_result {
            Ok(chunk) => {
                if let Err(e) = file.write_all(&chunk).await {
                    let _ = tokio::fs::remove_file(&file_path).await;
                    return (
                        axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                        format!("Failed to write chunk: {}", e),
                    ).into_response();
                }
                bytes_written += chunk.len() as u64;
                
                // Emit progress to Svelte UI
                let _ = state.app_handle.emit("transfer-progress", serde_json::json!({
                    "session_id": session_id,
                    "file_hash": file_hash,
                    "bytes_received": bytes_written,
                    "total_bytes": file_size,
                    "name": file_name,
                }));
            }
            Err(e) => {
                let _ = tokio::fs::remove_file(&file_path).await;
                return (
                    axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                    format!("Error reading upload stream: {}", e),
                ).into_response();
            }
        }
    }

    if let Err(e) = file.flush().await {
        return (
            axum::http::StatusCode::INTERNAL_SERVER_ERROR,
            format!("Failed to flush file: {}", e),
        ).into_response();
    }

    // Update transfer stats
    {
        let mut transfers = state.active_transfers.lock().unwrap();
        if let Some(transfer) = transfers.get_mut(&session_id) {
            transfer.received_bytes.insert(file_hash.clone(), bytes_written);
        }
    }

    println!("Received file: {} ({} bytes)", file_name, bytes_written);
    (axum::http::StatusCode::OK, "Upload complete").into_response()
}

pub fn broadcast_packet(state: &SharedState, packet: &crate::protocol::Packet) {
    if let Ok(text) = serde_json::to_string(packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let _ = tx.send(Message::Text(text.clone()));
        }
    }
}
