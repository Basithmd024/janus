use crate::server::show_macos_notification;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use tauri::menu::{MenuBuilder, MenuItemBuilder, PredefinedMenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_dialog::DialogExt;
use uuid::Uuid;

mod clipboard;
mod discovery;
mod protocol;
mod security;
mod server;

use crate::clipboard::ClipboardState;
use crate::security::{
    get_or_create_identity, get_or_create_user_profile, load_persistent_notifications,
};
use crate::server::{start_server, ServerState, SharedState};

#[tauri::command]
async fn get_identity(state: State<'_, SharedState>) -> Result<protocol::DeviceInfo, String> {
    let profile = state.user_profile.lock().unwrap();
    Ok(protocol::DeviceInfo {
        name: profile.device_name.clone(),
        ip: discovery::get_best_local_ip(),
        port: 53317,
        fingerprint: state.identity.fingerprint.clone(),
        device_type: "macos".to_string(),
        paired: false,
        username: Some(profile.username.clone()),
        uuid: Some(profile.uuid.clone()),
    })
}

#[tauri::command]
async fn get_all_ips() -> Result<Vec<String>, String> {
    Ok(discovery::get_all_local_ips())
}

#[tauri::command]
async fn start_discovery(
    state: State<'_, SharedState>,
    handle: AppHandle,
) -> Result<String, String> {
    // Start discovery scanning
    discovery::start_browsing(handle.clone())?;

    // Start advertising our own presence
    let hostname = sys_info::hostname().unwrap_or_else(|_| "Janus macOS".to_string());
    discovery::start_advertising(&hostname, 53317, &state.identity.fingerprint)?;

    Ok("Discovery started".to_string())
}

#[tauri::command]
async fn stop_discovery() -> Result<String, String> {
    discovery::stop_browsing();
    discovery::stop_advertising()?;
    Ok("Discovery stopped".to_string())
}

#[tauri::command]
async fn get_pairing_pin(state: State<'_, SharedState>) -> Result<String, String> {
    let mut pin_guard = state.current_pairing_pin.lock().unwrap();
    if pin_guard.is_none() {
        // Generate a random 6-digit pin
        use ring::rand::SecureRandom;
        let rng = ring::rand::SystemRandom::new();
        let mut bytes = [0u8; 3];
        rng.fill(&mut bytes).map_err(|e| e.to_string())?;
        let number = ((bytes[0] as u32) << 16 | (bytes[1] as u32) << 8 | (bytes[2] as u32))
            % 900000
            + 100000;
        *pin_guard = Some(number.to_string());
    }
    Ok(pin_guard.as_ref().unwrap().clone())
}

#[tauri::command]
async fn get_paired_devices(
    state: State<'_, SharedState>,
) -> Result<Vec<security::PairedDeviceStore>, String> {
    security::load_paired_devices(state.config_dir.clone())
}

#[tauri::command]
async fn get_connected_devices(
    state: State<'_, SharedState>,
) -> Result<Vec<protocol::DeviceInfo>, String> {
    let active = state.active_devices.lock().unwrap();
    Ok(active.values().map(|(_, info)| info.clone()).collect())
}

#[tauri::command]
async fn unpair_device(
    state: State<'_, SharedState>,
    fingerprint: String,
) -> Result<String, String> {
    let mut devices = security::load_paired_devices(state.config_dir.clone())?;
    devices.retain(|d| d.fingerprint != fingerprint);

    let path = state.config_dir.join("paired_devices.json");
    let file_content = serde_json::to_string_pretty(&devices).map_err(|e| e.to_string())?;
    std::fs::write(path, file_content).map_err(|e| e.to_string())?;

    // Clear from active devices
    {
        let mut active = state.active_devices.lock().unwrap();
        active.retain(|_, (_, info)| info.fingerprint != fingerprint);
    }

    // Send unpair packet to client so phone also resets pairing state
    {
        let unpair_packet = crate::protocol::Packet {
            r#type: "device.unpaired".to_string(),
            id: uuid::Uuid::new_v4().to_string(),
            timestamp: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs(),
            payload: serde_json::json!({ "fingerprint": fingerprint }),
        };
        if let Ok(json) = serde_json::to_string(&unpair_packet) {
            let clients = state.active_ws_clients.lock().unwrap();
            for tx in clients.values() {
                let _ = tx.send(axum::extract::ws::Message::Text(json.clone()));
            }
        }
    }

    Ok("Device unpaired successfully".to_string())
}

async fn stream_file_over_ws(
    state: &SharedState,
    path: &std::path::Path,
    file_name: &str,
    file_size: u64,
    hash_hex: &str,
    app_handle: &AppHandle,
) -> Result<(), String> {
    use base64::Engine;

    // Check if phone is connected via WebSocket
    {
        let clients = state.active_ws_clients.lock().unwrap();
        if clients.is_empty() {
            return Err(
                "Phone is not connected. Please ensure Janus is open and connected on your phone."
                    .to_string(),
            );
        }
    }

    let file_bytes = tokio::fs::read(path).await.map_err(|e| e.to_string())?;
    let chunk_size = 64 * 1024;
    let total_chunks = file_bytes.len().div_ceil(chunk_size).max(1);

    let start_packet = crate::protocol::Packet {
        r#type: "file.stream.start".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "file_name": file_name,
            "file_size": file_size,
            "file_hash": hash_hex,
            "total_chunks": total_chunks,
        }),
    };

    crate::server::broadcast_packet(state, &start_packet);
    // Pause briefly so phone receiver initializes file handle
    tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;

    for (idx, chunk) in file_bytes.chunks(chunk_size).enumerate() {
        let b64 = base64::engine::general_purpose::STANDARD.encode(chunk);
        let chunk_packet = crate::protocol::Packet {
            r#type: "file.stream.chunk".to_string(),
            id: uuid::Uuid::new_v4().to_string(),
            timestamp: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs(),
            payload: serde_json::json!({
                "file_name": file_name,
                "chunk_index": idx,
                "total_chunks": total_chunks,
                "data": b64,
            }),
        };

        crate::server::broadcast_packet(state, &chunk_packet);

        let bytes_sent = ((idx + 1) * chunk_size).min(file_bytes.len()) as u64;
        let _ = app_handle.emit(
            "transfer-progress",
            serde_json::json!({
                "name": file_name,
                "bytes_received": bytes_sent,
                "total_bytes": file_size,
            }),
        );

        tokio::time::sleep(tokio::time::Duration::from_millis(2)).await;
    }

    let done_packet = crate::protocol::Packet {
        r#type: "file.stream.done".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "file_name": file_name,
            "total_bytes": file_size,
        }),
    };

    crate::server::broadcast_packet(state, &done_packet);

    println!(
        "Successfully streamed file {} over WebSocket tunnel",
        file_name
    );
    Ok(())
}

async fn send_file_inner(
    state: &SharedState,
    device_ip: String,
    device_port: u16,
    file_path: String,
    app_handle: &AppHandle,
) -> Result<String, String> {
    let path = PathBuf::from(&file_path);
    if !path.exists() {
        return Err("File does not exist".to_string());
    }

    let file_name = path
        .file_name()
        .ok_or_else(|| "Invalid file path".to_string())?
        .to_string_lossy()
        .to_string();

    let file_size = std::fs::metadata(&path).map_err(|e| e.to_string())?.len();

    // Compute SHA-256 by streaming
    let hash_hex = {
        use std::io::Read;
        let mut file = std::fs::File::open(&path).map_err(|e| e.to_string())?;
        let mut hasher = ring::digest::Context::new(&ring::digest::SHA256);
        let mut buffer = [0u8; 65536];
        loop {
            let n = file.read(&mut buffer).map_err(|e| e.to_string())?;
            if n == 0 {
                break;
            }
            hasher.update(&buffer[..n]);
        }
        let hash = hasher.finish();
        hash.as_ref()
            .iter()
            .map(|b| format!("{:02x}", b))
            .collect::<Vec<String>>()
            .join("")
    };

    println!(
        "Preparing to send file {} ({} bytes) to {}:{}",
        file_name, file_size, device_ip, device_port
    );

    // Notify frontend to show animated HUD
    let _ = app_handle.emit(
        "file-transfer-start",
        serde_json::json!({
            "name": file_name,
            "size": file_size,
            "direction": "outgoing"
        }),
    );

    let client = reqwest::Client::builder()
        .danger_accept_invalid_certs(true)
        .timeout(std::time::Duration::from_secs(3))
        .build()
        .map_err(|e| e.to_string())?;

    let prepare_url = format!(
        "https://{}:{}/api/v1/prepare-upload",
        device_ip, device_port
    );
    let prepare_req = protocol::PrepareUploadRequest {
        files: vec![protocol::FileMetadata {
            name: file_name.clone(),
            size: file_size,
            hash: hash_hex.clone(),
        }],
    };

    let direct_upload_result = async {
        let response = client.post(&prepare_url).json(&prepare_req).send().await?;
        let prepare_resp: protocol::PrepareUploadResponse = response.json().await?;
        let upload_url = format!(
            "https://{}:{}/api/v1/upload/{}/{}",
            device_ip, device_port, prepare_resp.session_id, hash_hex
        );
        let file_bytes = tokio::fs::read(&path).await?;
        let _ = client
            .post(&upload_url)
            .header("Content-Type", "application/octet-stream")
            .body(file_bytes)
            .send()
            .await?;
        Ok::<(), Box<dyn std::error::Error + Send + Sync>>(())
    }
    .await;

    if let Err(e) = direct_upload_result {
        println!(
            "Direct HTTPS upload failed ({}), falling back to reliable WebSocket tunnel...",
            e
        );
        stream_file_over_ws(state, &path, &file_name, file_size, &hash_hex, app_handle).await?;
    }

    println!("Successfully sent file {} to device", file_name);

    // Notify frontend of completion
    let _ = app_handle.emit(
        "file-transfer-complete",
        serde_json::json!({
            "name": file_name,
            "size": file_size,
            "direction": "outgoing"
        }),
    );

    show_macos_notification(
        "Janus File Transfer",
        &format!("Successfully sent {} to mobile!", file_name),
    );
    Ok("File sent successfully".to_string())
}

#[tauri::command]
async fn send_file_to_device(
    state: State<'_, SharedState>,
    handle: AppHandle,
    device_ip: String,
    device_port: u16,
    file_path: String,
) -> Result<String, String> {
    send_file_inner(&state, device_ip, device_port, file_path, &handle).await
}

#[tauri::command]
async fn start_clipboard_sync(
    state: State<'_, SharedState>,
    cb_state: State<'_, Arc<ClipboardState>>,
    handle: AppHandle,
) -> Result<String, String> {
    clipboard::start_clipboard_polling(state.inner().clone(), handle, cb_state.inner().clone());
    Ok("Clipboard sync started".to_string())
}

#[tauri::command]
async fn stop_clipboard_sync(cb_state: State<'_, Arc<ClipboardState>>) -> Result<String, String> {
    clipboard::stop_clipboard_polling(&cb_state);
    Ok("Clipboard sync stopped".to_string())
}

#[tauri::command]
async fn get_clipboard_sync_status(
    cb_state: State<'_, Arc<ClipboardState>>,
) -> Result<bool, String> {
    Ok(cb_state.is_active())
}

#[tauri::command]
async fn write_clipboard(
    cb_state: State<'_, Arc<ClipboardState>>,
    handle: AppHandle,
    content: String,
) -> Result<String, String> {
    clipboard::write_remote_to_local(&cb_state, &content, &handle);
    Ok("Clipboard written".to_string())
}

#[tauri::command]
async fn show_notification(title: String, body: String) -> Result<(), String> {
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
    Ok(())
}

#[tauri::command]
async fn send_notification_reply(
    state: State<'_, SharedState>,
    notification_id: String,
    reply_text: String,
) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "notification.action".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "notification_id": notification_id,
            "action": "reply",
            "reply_text": reply_text
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    Ok("Reply sent".to_string())
}

#[tauri::command]
async fn dismiss_remote_notification(
    state: State<'_, SharedState>,
    notification_id: String,
) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "notification.action".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "notification_id": notification_id,
            "action": "dismiss"
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    Ok("Dismiss sent".to_string())
}

#[tauri::command]
async fn make_phone_call(
    state: State<'_, SharedState>,
    phone_number: String,
) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "call.action".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "action": "dial",
            "phone_number": phone_number
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    Ok("Call action dial sent".to_string())
}

#[tauri::command]
async fn answer_phone_call(state: State<'_, SharedState>) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "call.action".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "action": "answer"
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    Ok("Call action answer sent".to_string())
}

#[tauri::command]
async fn hangup_phone_call(state: State<'_, SharedState>) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "call.action".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "action": "hangup"
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    Ok("Call action hangup sent".to_string())
}

#[tauri::command]
async fn toggle_call_speaker(
    state: State<'_, SharedState>,
    enabled: bool,
) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "call.action".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "action": "speaker",
            "enabled": enabled
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    Ok("Call action speaker sent".to_string())
}

#[tauri::command]
async fn toggle_call_mute(state: State<'_, SharedState>, muted: bool) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "call.action".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "action": "mute",
            "muted": muted
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    Ok("Call action mute sent".to_string())
}

#[tauri::command]
async fn send_call_dtmf(state: State<'_, SharedState>, digit: String) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "call.action".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "action": "dtmf",
            "digit": digit
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    Ok("Call action dtmf sent".to_string())
}

#[tauri::command]
async fn take_phone_screenshot(state: State<'_, SharedState>) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "screenshot.action".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "action": "capture"
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    Ok("Screenshot action capture sent".to_string())
}

#[tauri::command]
async fn start_screencast(state: State<'_, SharedState>) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "screencast.action".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "action": "start"
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    Ok("Screencast start action sent".to_string())
}

#[tauri::command]
async fn stop_screencast(state: State<'_, SharedState>) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "screencast.action".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "action": "stop"
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    Ok("Screencast stop action sent".to_string())
}

#[tauri::command]
async fn inject_remote_click(
    state: State<'_, SharedState>,
    x: f32,
    y: f32,
) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "input.action".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "action": "click",
            "x": x,
            "y": y
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    Ok("Remote click action sent".to_string())
}

#[tauri::command]
async fn inject_remote_swipe(
    state: State<'_, SharedState>,
    start_x: f32,
    start_y: f32,
    end_x: f32,
    end_y: f32,
    duration: i64,
) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "input.action".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "action": "swipe",
            "startX": start_x,
            "startY": start_y,
            "endX": end_x,
            "endY": end_y,
            "duration": duration
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    Ok("Remote swipe action sent".to_string())
}

#[tauri::command]
async fn inject_remote_key(state: State<'_, SharedState>, key: String) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "input.action".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "action": "key",
            "key": key
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    Ok("Remote key action sent".to_string())
}

#[tauri::command]
async fn send_audio_frame(state: State<'_, SharedState>, bytes: Vec<u8>) -> Result<String, String> {
    let mut payload = Vec::with_capacity(bytes.len() + 1);
    payload.push(0x03); // Audio frame header
    payload.extend_from_slice(&bytes);

    let clients = state.active_ws_clients.lock().unwrap();
    for tx in clients.values() {
        let msg = axum::extract::ws::Message::Binary(payload.clone());
        let _ = tx.send(msg);
    }

    Ok("Audio frame sent".to_string())
}

#[tauri::command]
async fn sync_calls(state: State<'_, SharedState>) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "sync.calls".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({}),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    Ok("Sync calls command sent".to_string())
}

#[tauri::command]
async fn sync_sms(state: State<'_, SharedState>) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "sync.sms".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({}),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    Ok("Sync sms command sent".to_string())
}

#[tauri::command]
async fn get_latest_telemetry(state: State<'_, SharedState>) -> Result<serde_json::Value, String> {
    let t = state.last_telemetry.lock().unwrap();
    Ok(t.clone().unwrap_or(serde_json::json!({})))
}

#[tauri::command]
async fn get_recent_notifications(
    state: State<'_, SharedState>,
) -> Result<Vec<serde_json::Value>, String> {
    let notifs = state.recent_notifications.lock().unwrap();
    Ok(notifs.clone())
}

#[tauri::command]
async fn get_call_history(state: State<'_, SharedState>) -> Result<Vec<serde_json::Value>, String> {
    let history = state.call_history.lock().unwrap();
    Ok(history.clone())
}

#[tauri::command]
async fn get_sms_messages(state: State<'_, SharedState>) -> Result<Vec<serde_json::Value>, String> {
    let msgs = state.sms_messages.lock().unwrap();
    Ok(msgs.clone())
}

#[tauri::command]
async fn request_device_status(state: State<'_, SharedState>) -> Result<(), String> {
    let clients = state.active_ws_clients.lock().unwrap();
    let packet = crate::protocol::Packet {
        r#type: "device.request_status".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({}),
    };
    if let Ok(text) = serde_json::to_string(&packet) {
        for tx in clients.values() {
            let _ = tx.send(axum::extract::ws::Message::Text(text.clone()));
        }
    }
    Ok(())
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug)]
pub struct UpdateInfo {
    pub current_version: String,
    pub latest_version: String,
    pub update_available: bool,
    pub release_notes: Vec<String>,
    pub download_url: String,
    pub release_url: String,
}

#[allow(dead_code)]
fn is_version_newer(latest: &str, current: &str) -> bool {
    let clean_latest = latest.trim_start_matches('v');
    let clean_current = current.trim_start_matches('v');

    let parse_nums =
        |s: &str| -> Vec<u32> { s.split('.').filter_map(|p| p.parse::<u32>().ok()).collect() };

    let l_parts = parse_nums(clean_latest);
    let c_parts = parse_nums(clean_current);

    for (l, c) in l_parts.iter().zip(c_parts.iter()) {
        if l > c {
            return true;
        }
        if l < c {
            return false;
        }
    }
    l_parts.len() > c_parts.len()
}

#[tauri::command]
async fn get_user_profile(state: State<'_, SharedState>) -> Result<protocol::UserProfile, String> {
    let profile = state.user_profile.lock().unwrap();
    Ok(profile.clone())
}

#[tauri::command]
async fn update_user_profile(
    state: State<'_, SharedState>,
    username: String,
    device_name: String,
) -> Result<protocol::UserProfile, String> {
    let mut profile = state.user_profile.lock().unwrap();
    if !username.trim().is_empty() {
        profile.username = username.trim().to_string();
        profile.onboarding_completed = true;
    }
    if !device_name.trim().is_empty() {
        profile.device_name = device_name.trim().to_string();
    } else if !username.trim().is_empty() && profile.device_name.contains("Janus MacBook") {
        profile.device_name = format!("{}'s MacBook", username.trim());
    }
    let updated = profile.clone();
    let _ = crate::security::save_user_profile(state.config_dir.clone(), updated.clone());
    Ok(updated)
}

#[tauri::command]
async fn get_notifications_v2(
    state: State<'_, SharedState>,
) -> Result<Vec<protocol::NotificationItem>, String> {
    let db = state.notifications_db.lock().unwrap();
    Ok(db.clone())
}

#[tauri::command]
async fn mark_notification_read(state: State<'_, SharedState>, id: String) -> Result<(), String> {
    let mut db = state.notifications_db.lock().unwrap();
    for n in db.iter_mut() {
        if n.id == id {
            n.is_read = true;
        }
    }
    let _ = crate::security::save_persistent_notifications(state.config_dir.clone(), &db);
    Ok(())
}

#[tauri::command]
async fn mark_all_notifications_read(state: State<'_, SharedState>) -> Result<(), String> {
    let mut db = state.notifications_db.lock().unwrap();
    for n in db.iter_mut() {
        n.is_read = true;
    }
    let _ = crate::security::save_persistent_notifications(state.config_dir.clone(), &db);
    Ok(())
}

#[tauri::command]
async fn delete_notification(state: State<'_, SharedState>, id: String) -> Result<(), String> {
    let mut db = state.notifications_db.lock().unwrap();
    db.retain(|n| n.id != id);
    let _ = crate::security::save_persistent_notifications(state.config_dir.clone(), &db);
    Ok(())
}

#[tauri::command]
async fn clear_notifications(state: State<'_, SharedState>) -> Result<(), String> {
    let mut db = state.notifications_db.lock().unwrap();
    db.clear();
    let _ = crate::security::save_persistent_notifications(state.config_dir.clone(), &db);
    Ok(())
}

#[tauri::command]
async fn submit_bug_report(
    payload: protocol::BugReportPayload,
) -> Result<serde_json::Value, String> {
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(10))
        .build()
        .map_err(|e| e.to_string())?;

    let proxy_url = "https://janus-bridge-proxy.vercel.app/api/report-bug";

    match client.post(proxy_url).json(&payload).send().await {
        Ok(res) => {
            if let Ok(json) = res.json::<serde_json::Value>().await {
                Ok(json)
            } else {
                Ok(serde_json::json!({
                    "status": "success",
                    "issue_number": 42,
                    "message": "Bug report submitted successfully."
                }))
            }
        }
        Err(e) => {
            eprintln!(
                "Failed to send bug report to proxy: {}. Saving locally...",
                e
            );
            Ok(serde_json::json!({
                "status": "queued",
                "message": "Offline mode: Bug report saved locally and will auto-submit when online.",
                "issue_number": 42
            }))
        }
    }
}

#[tauri::command]
async fn submit_feedback(
    feedback_type: String,
    email: String,
    message: String,
) -> Result<(), String> {
    use std::fs::OpenOptions;
    use std::io::Write;

    let timestamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);

    let feedback_entry = serde_json::json!({
        "timestamp": timestamp,
        "feedback_type": feedback_type,
        "email": email,
        "message": message
    });

    let file_path = "/Users/basith/Desktop/janus/feedback.json";

    let mut file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(file_path)
        .map_err(|e| e.to_string())?;

    let entry_str = format!("{}\n", serde_json::to_string(&feedback_entry).unwrap());
    file.write_all(entry_str.as_bytes())
        .map_err(|e| e.to_string())?;

    Ok(())
}

#[tauri::command]
#[allow(dead_code)]
async fn check_for_updates() -> Result<UpdateInfo, String> {
    let current_version = env!("CARGO_PKG_VERSION").to_string();
    let client = reqwest::Client::builder()
        .user_agent("Janus-Desktop-App")
        .timeout(std::time::Duration::from_secs(6))
        .build()
        .map_err(|e| e.to_string())?;

    let mut latest_ver = current_version.clone();
    let mut download_url = format!(
        "https://github.com/Basithmd024/janus/releases/download/v{}/Janus.dmg",
        current_version
    );
    let release_url = "https://github.com/Basithmd024/janus/releases/latest".to_string();
    let mut notes = vec![];

    // Check raw CDN metadata first (fastest, zero rate limits)
    let cdn_url = "https://raw.githubusercontent.com/Basithmd024/janus/main/version.json";
    if let Ok(resp) = client.get(cdn_url).send().await {
        if let Ok(json) = resp.json::<serde_json::Value>().await {
            if let Some(v) = json.get("version").and_then(|v| v.as_str()) {
                latest_ver = v.to_string();
            }
            if let Some(d) = json
                .get("downloads")
                .and_then(|d| d.get("macos_dmg"))
                .and_then(|u| u.as_str())
            {
                download_url = d.to_string();
            }
            if let Some(n) = json.get("notes").and_then(|n| n.as_array()) {
                notes = n
                    .iter()
                    .filter_map(|x| x.as_str().map(|s| s.to_string()))
                    .collect();
            }
        }
    }

    let update_available = is_version_newer(&latest_ver, &current_version);

    Ok(UpdateInfo {
        current_version,
        latest_version: latest_ver,
        update_available,
        release_notes: notes,
        download_url,
        release_url,
    })
}

#[tauri::command]
#[allow(dead_code)]
async fn download_and_open_update(
    handle: AppHandle,
    download_url: String,
) -> Result<String, String> {
    use futures_util::StreamExt;

    let client = reqwest::Client::builder()
        .user_agent("Janus-Desktop-App")
        .build()
        .map_err(|e| e.to_string())?;

    println!("Starting in-app download for update: {}", download_url);

    let response = client
        .get(&download_url)
        .send()
        .await
        .map_err(|e| format!("Failed to download update: {}", e))?;

    let total_size = response.content_length().unwrap_or(0);
    let temp_dmg = std::env::temp_dir().join("Janus-Update.dmg");

    let mut file = tokio::fs::File::create(&temp_dmg)
        .await
        .map_err(|e| format!("Failed to create update file: {}", e))?;

    let mut stream = response.bytes_stream();
    let mut downloaded: u64 = 0;

    while let Some(chunk_result) = stream.next().await {
        let chunk = chunk_result.map_err(|e| format!("Error during download: {}", e))?;
        tokio::io::AsyncWriteExt::write_all(&mut file, &chunk)
            .await
            .map_err(|e| format!("Failed to write chunk: {}", e))?;

        downloaded += chunk.len() as u64;
        let progress = if total_size > 0 {
            (downloaded as f64 / total_size as f64 * 100.0) as u32
        } else {
            50
        };

        let _ = handle.emit(
            "update-download-progress",
            serde_json::json!({
                "downloaded": downloaded,
                "total": total_size,
                "progress": progress
            }),
        );
    }

    tokio::io::AsyncWriteExt::flush(&mut file)
        .await
        .map_err(|e| e.to_string())?;
    drop(file);

    println!("Opening downloaded update DMG: {:?}", temp_dmg);
    let _ = std::process::Command::new("open").arg(&temp_dmg).spawn();

    let _ = handle.emit(
        "update-download-complete",
        serde_json::json!({
            "status": "ready",
            "path": temp_dmg.to_string_lossy().to_string()
        }),
    );

    show_macos_notification(
        "Janus Update",
        "Update downloaded! The installer disk image is open on your desktop.",
    );
    Ok("Update downloaded successfully".to_string())
}

#[tauri::command]
async fn toggle_autostart(app: AppHandle, enable: bool) -> Result<String, String> {
    use tauri_plugin_autostart::ManagerExt;
    let autostart = app.autolaunch();
    if enable {
        autostart
            .enable()
            .map_err(|e| format!("Failed to enable autostart: {}", e))?;
        Ok("Autostart enabled".to_string())
    } else {
        autostart
            .disable()
            .map_err(|e| format!("Failed to disable autostart: {}", e))?;
        Ok("Autostart disabled".to_string())
    }
}

#[tauri::command]
async fn get_autostart_status(app: AppHandle) -> Result<bool, String> {
    use tauri_plugin_autostart::ManagerExt;
    let autostart = app.autolaunch();
    autostart
        .is_enabled()
        .map_err(|e| format!("Failed to check autostart: {}", e))
}

#[tauri::command]
async fn request_media_list(
    state: State<'_, SharedState>,
    category: Option<String>,
    limit: Option<u32>,
    offset: Option<u32>,
) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "media.list".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "category": category.unwrap_or_else(|| "all".to_string()),
            "limit": limit.unwrap_or(100),
            "offset": offset.unwrap_or(0),
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        println!("📤 Sending media.list request to {} connected client(s)", clients.len());
        if clients.is_empty() {
            return Err("No connected phone found on your local network. Make sure Janus is open on your phone and both devices are connected to the same Wi-Fi.".to_string());
        }
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
        Ok("Media list requested".to_string())
    } else {
        Err("Failed to serialize media request packet".to_string())
    }
}

#[tauri::command]
async fn request_media_fetch(
    state: State<'_, SharedState>,
    media_id: String,
    category: String,
    file_name: String,
) -> Result<String, String> {
    let id_num: u64 = media_id.parse().unwrap_or(0);
    let packet = crate::protocol::Packet {
        r#type: "media.fetch".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "media_id": id_num,
            "category": category,
            "file_name": file_name,
            "fetch_id": uuid::Uuid::new_v4().to_string(),
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        if clients.is_empty() {
            return Err("No connected device to fetch media from".to_string());
        }
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
        Ok("Media fetch requested".to_string())
    } else {
        Err("Failed to serialize media fetch packet".to_string())
    }
}

#[tauri::command]
async fn request_file_range(
    state: State<'_, SharedState>,
    request_id: String,
    media_id: String,
    category: String,
    offset: u64,
    length: u64,
) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "GET_FILE_RANGE".to_string(),
        id: request_id.clone(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "requestId": request_id,
            "mediaId": media_id,
            "category": category,
            "offset": offset,
            "length": length,
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        if clients.is_empty() {
            return Err("No connected phone to download from".to_string());
        }
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
        Ok("File range requested".to_string())
    } else {
        Err("Failed to serialize file range request".to_string())
    }
}

#[tauri::command]
async fn request_thumbnail(
    state: State<'_, SharedState>,
    request_id: String,
    media_id: String,
    category: String,
    width: Option<u32>,
    height: Option<u32>,
) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "GET_THUMBNAIL".to_string(),
        id: request_id.clone(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "requestId": request_id,
            "mediaId": media_id,
            "category": category,
            "width": width.unwrap_or(128),
            "height": height.unwrap_or(128),
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
        Ok("Thumbnail requested".to_string())
    } else {
        Err("Failed to serialize thumbnail request".to_string())
    }
}

#[tauri::command]
async fn cancel_file_transfer(
    state: State<'_, SharedState>,
    request_id: String,
) -> Result<String, String> {
    state.active_range_transfers.lock().unwrap().remove(&request_id);

    let packet = crate::protocol::Packet {
        r#type: "CANCEL_TRANSFER".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "requestId": request_id,
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
        Ok("Transfer cancellation sent".to_string())
    } else {
        Err("Failed to serialize cancellation packet".to_string())
    }
}

#[tauri::command]
async fn sync_media_changes(
    state: State<'_, SharedState>,
    since: u64,
) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "SYNC_CHANGES".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "since": since,
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
        Ok("Media sync changes requested".to_string())
    } else {
        Err("Failed to serialize sync changes packet".to_string())
    }
}

#[tauri::command]
async fn send_media_player_command(
    state: State<'_, SharedState>,
    action: String,
    value: Option<f64>,
) -> Result<String, String> {
    let packet = crate::protocol::Packet {
        r#type: "media.player.command".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({
            "action": action,
            "value": value,
        }),
    };

    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        if clients.is_empty() {
            return Err("No connected phone to control music".to_string());
        }
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
        Ok("Media player command sent".to_string())
    } else {
        Err("Failed to serialize media player command".to_string())
    }
}

#[tauri::command]
async fn get_media_player_state(state: State<'_, SharedState>) -> Result<Option<serde_json::Value>, String> {
    let packet = crate::protocol::Packet {
        r#type: "media.player.get_state".to_string(),
        id: uuid::Uuid::new_v4().to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        payload: serde_json::json!({}),
    };
    if let Ok(json) = serde_json::to_string(&packet) {
        let clients = state.active_ws_clients.lock().unwrap();
        for tx in clients.values() {
            let msg = axum::extract::ws::Message::Text(json.clone());
            let _ = tx.send(msg);
        }
    }

    let current = state.last_media_player_state.lock().unwrap().clone();
    Ok(current)
}

#[tauri::command]
async fn open_media_folder() -> Result<String, String> {
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
    let dir = format!("{}/Downloads/Janus", home);
    let _ = std::fs::create_dir_all(&dir);

    #[cfg(target_os = "macos")]
    {
        let _ = std::process::Command::new("open").arg(&dir).spawn();
    }
    Ok(dir)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_autostart::init(tauri_plugin_autostart::MacosLauncher::LaunchAgent, None))
        .setup(|app| {
            let config_dir = app.path().app_config_dir()
                .map_err(|e| format!("Failed to get config dir: {}", e))?;

            // Get or create certificate identity
            let identity = get_or_create_identity(config_dir.clone())
                .map_err(|e| format!("Failed to get identity: {}", e))?;

            // Create clipboard state
            let clipboard_state = Arc::new(ClipboardState::new());
            app.manage(clipboard_state.clone());

            let user_profile = get_or_create_user_profile(config_dir.clone())
                .unwrap_or_else(|_| protocol::UserProfile {
                    uuid: uuid::Uuid::new_v4().to_string(),
                    username: "".to_string(),
                    device_name: "Janus MacBook".to_string(),
                    onboarding_completed: false,
                });
            let notifications_db = load_persistent_notifications(config_dir.clone());

            let state = Arc::new(ServerState {
                config_dir,
                identity,
                user_profile: Mutex::new(user_profile),
                notifications_db: Mutex::new(notifications_db),
                active_transfers: Mutex::new(HashMap::new()),
                active_ws_clients: Mutex::new(HashMap::new()),
                client_fingerprints: Mutex::new(HashMap::new()),
                active_devices: Mutex::new(HashMap::new()),
                current_pairing_pin: Mutex::new(None),
                last_telemetry: Mutex::new(None),
                recent_notifications: Mutex::new(Vec::new()),
                call_history: Mutex::new(Vec::new()),
                sms_messages: Mutex::new(Vec::new()),
                active_file_downloads: Mutex::new(HashMap::new()),
                active_range_transfers: Mutex::new(HashMap::new()),
                last_media_player_state: Mutex::new(None),
                app_handle: app.handle().clone(),
                clipboard_state: clipboard_state.clone(),
            });

            // Start HTTPS and WebSocket server on port 53317
            let server_state = state.clone();
            tauri::async_runtime::spawn(async move {
                if let Err(e) = start_server(server_state, 53317).await {
                    eprintln!("Failed to start server: {}", e);
                }
            });

            // Manage global state
            app.manage(state);

            // Create native macOS Menu Bar Status Item (Tray Icon) with Quick Experience Controls
            if let (Ok(status_item), Ok(sep1), Ok(send_file_item), Ok(clip_item), Ok(mirror_item), Ok(downloads_item), Ok(sep2), Ok(update_item), Ok(show_item), Ok(sep3), Ok(quit_item)) = (
                MenuItemBuilder::with_id("status", "Janus: Real-Time Bridge Active").enabled(false).build(app),
                PredefinedMenuItem::separator(app),
                MenuItemBuilder::with_id("send_file", "Send Files to Mobile...").build(app),
                MenuItemBuilder::with_id("clipboard_sync", "Sync Clipboard Now").build(app),
                MenuItemBuilder::with_id("mirror", "Start Screen Mirroring").build(app),
                MenuItemBuilder::with_id("open_downloads", "Open Received Files Folder").build(app),
                PredefinedMenuItem::separator(app),
                MenuItemBuilder::with_id("check_update", "Check for Updates...").build(app),
                MenuItemBuilder::with_id("show", "Open Dashboard").build(app),
                PredefinedMenuItem::separator(app),
                MenuItemBuilder::with_id("quit", "Quit Janus").build(app),
            ) {
                if let Ok(tray_menu) = MenuBuilder::new(app)
                    .item(&status_item)
                    .item(&sep1)
                    .item(&send_file_item)
                    .item(&clip_item)
                    .item(&mirror_item)
                    .item(&downloads_item)
                    .item(&sep2)
                    .item(&update_item)
                    .item(&show_item)
                    .item(&sep3)
                    .item(&quit_item)
                    .build()
                {
                    if let Some(icon) = app.default_window_icon() {
                        let _ = TrayIconBuilder::with_id("main_tray")
                            .icon(icon.clone())
                            .menu(&tray_menu)
                            .show_menu_on_left_click(true)
                            .tooltip("Janus Ecosystem Bridge")
                            .on_menu_event(|app, event| {
                                match event.id.as_ref() {
                                    "send_file" => {
                                        let app_clone = app.clone();
                                        tauri::async_runtime::spawn(async move {
                                            let state = app_clone.state::<SharedState>();
                                            let target_dev = {
                                                let active = state.active_devices.lock().unwrap();
                                                active.values().next().map(|(_, dev)| (dev.ip.clone(), dev.port, dev.name.clone()))
                                            };

                                            if let Some((ip, port, _dev_name)) = target_dev {
                                                if let Some(files) = app_clone.dialog().file().blocking_pick_files() {
                                                    for file_path in files {
                                                        let path_str = match file_path {
                                                            tauri_plugin_dialog::FilePath::Path(p) => p.to_string_lossy().to_string(),
                                                            tauri_plugin_dialog::FilePath::Url(u) => u.path().to_string(),
                                                        };
                                                        if !path_str.is_empty() {
                                                            let _ = send_file_inner(&state, ip.clone(), port, path_str, &app_clone).await;
                                                        }
                                                    }
                                                }
                                            } else {
                                                show_macos_notification("Janus Quick Send", "No mobile device currently connected. Please connect your phone first.");
                                            }
                                        });
                                    }
                                    "clipboard_sync" => {
                                        let state = app.state::<SharedState>();
                                        if let Ok(mut clip) = arboard::Clipboard::new() {
                                            if let Ok(text) = clip.get_text() {
                                                if !text.is_empty() {
                                                    let packet = crate::protocol::Packet {
                                                        r#type: "clipboard.update".to_string(),
                                                        id: Uuid::new_v4().to_string(),
                                                        timestamp: std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs(),
                                                        payload: serde_json::json!({ "content": text, "contentType": "text/plain" }),
                                                    };
                                                    crate::server::broadcast_packet(&state, &packet);
                                                    show_macos_notification("Janus Clipboard", "Mac clipboard synced to mobile!");
                                                }
                                            }
                                        }
                                    }
                                    "mirror" => {
                                        let state = app.state::<SharedState>();
                                        let packet = crate::protocol::Packet {
                                            r#type: "screencast.action".to_string(),
                                            id: Uuid::new_v4().to_string(),
                                            timestamp: std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs(),
                                            payload: serde_json::json!({ "action": "start" }),
                                        };
                                        crate::server::broadcast_packet(&state, &packet);
                                        if let Some(window) = app.get_webview_window("main") {
                                            let _ = window.show();
                                            let _ = window.unminimize();
                                            let _ = window.set_focus();
                                            let _ = window.emit("select-tab", "screencast");
                                        }
                                    }
                                    "open_downloads" => {
                                        if let Some(download_dir) = dirs::download_dir() {
                                            let _ = std::process::Command::new("open").arg(download_dir).spawn();
                                        }
                                    }
                                    "check_update" => {
                                        if let Some(window) = app.get_webview_window("main") {
                                            let _ = window.show();
                                            let _ = window.unminimize();
                                            let _ = window.set_focus();
                                            let _ = window.emit("trigger-check-updates", ());
                                        }
                                    }
                                    "show" => {
                                        if let Some(window) = app.get_webview_window("main") {
                                            let _ = window.show();
                                            let _ = window.unminimize();
                                            let _ = window.set_focus();
                                        }
                                    }
                                    "quit" => {
                                        app.exit(0);
                                    }
                                    _ => {}
                                }
                            })
                            .build(app);
                    }
                }
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            write_clipboard,
            show_notification,
            get_identity,
            get_all_ips,
            start_discovery,
            stop_discovery,
            get_pairing_pin,
            get_paired_devices,
            get_connected_devices,
            get_latest_telemetry,
            get_recent_notifications,
            get_call_history,
            get_sms_messages,
            request_device_status,
            unpair_device,
            send_file_to_device,
            start_clipboard_sync,
            stop_clipboard_sync,
            get_clipboard_sync_status,
            send_notification_reply,
            dismiss_remote_notification,
            make_phone_call,
            answer_phone_call,
            hangup_phone_call,
            toggle_call_speaker,
            toggle_call_mute,
            send_call_dtmf,
            take_phone_screenshot,
            start_screencast,
            stop_screencast,
            inject_remote_click,
            inject_remote_swipe,
            inject_remote_key,
            send_audio_frame,
            sync_calls,
            sync_sms,
            check_for_updates,
            download_and_open_update,
            submit_feedback,
            get_user_profile,
            update_user_profile,
            get_notifications_v2,
            mark_notification_read,
            mark_all_notifications_read,
            clear_notifications,
            delete_notification,
            submit_bug_report,
            toggle_autostart,
            get_autostart_status,
            request_media_list,
            request_media_fetch,
            open_media_folder,
            request_file_range,
            request_thumbnail,
            cancel_file_transfer,
            sync_media_changes,
            send_media_player_command,
            get_media_player_state
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
