# Janus — Phase 1 Task Tracker

## 🏗️ Project Scaffolding
- [x] Create Tauri v2 project with Svelte + TS
- [x] Install npm dependencies
- [/] Add Rust networking dependencies (tokio, axum, rustls, mdns-sd, etc.)
- [ ] Configure Tauri capabilities for networking

## 🔍 mDNS Discovery
- [ ] Implement mDNS service advertising (broadcast our device)
- [ ] Implement mDNS service browsing (discover peers)
- [ ] Expose discovered devices to Svelte frontend via Tauri commands
- [ ] Handle device online/offline events

## 🔐 Security Foundation
- [ ] Generate self-signed TLS certificate on first launch
- [ ] Store certificate in app data directory
- [ ] Implement SPAKE2 pairing flow
- [ ] Store paired device fingerprints

## 🌐 Network Server
- [ ] HTTPS server (axum) with mTLS support
- [ ] WebSocket endpoint for real-time messages
- [ ] Protocol message types (serde serialization)
- [ ] Heartbeat / keepalive system

## 📁 File Transfer (Mac → Android)
- [ ] REST API: prepare-upload endpoint
- [ ] REST API: chunked upload endpoint
- [ ] SHA-256 integrity verification
- [ ] Progress reporting to frontend

## 🎨 Svelte Frontend
- [ ] Premium dark-mode UI with glassmorphism
- [ ] Device discovery panel (show nearby devices)
- [ ] Pairing flow UI (PIN display + verification)
- [ ] File transfer UI (drag & drop, progress bars)
- [ ] Connection status indicator
