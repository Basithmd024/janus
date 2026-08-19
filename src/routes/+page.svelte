<script lang="ts">
  import { onMount, onDestroy } from "svelte";
  import { invoke } from "@tauri-apps/api/core";
  import { listen } from "@tauri-apps/api/event";
  import { open } from "@tauri-apps/plugin-dialog";
  import QRCode from "qrcode";
  import Login from "../lib/components/Login.svelte";
  import { auth, db, startCloudSync, stopCloudSync } from "../lib/firebase";
  import { onAuthStateChanged, signOut } from "firebase/auth";
  import { collection, onSnapshot } from "firebase/firestore";

  interface Device {
    name: string;
    ip: string;
    port: number;
    fingerprint: string;
    device_type: string;
    paired: boolean;
  }

  interface TransferProgress {
    session_id: string;
    file_hash: string;
    bytes_received: number;
    total_bytes: number;
    name: string;
  }

  interface ClipboardEvent {
    content: string;
    content_type: string;
    source: string;
  }

  // Theme Toggle State (Default to Light Theme)
  let currentTheme = $state<string>(typeof localStorage !== 'undefined' ? (localStorage.getItem('janus-theme') || 'light') : 'light');

  function toggleTheme() {
    currentTheme = currentTheme === 'dark' ? 'light' : 'dark';
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem('janus-theme', currentTheme);
    }
    document.documentElement.setAttribute('data-theme', currentTheme);
  }

  // Intro Motion & Launch Animation State
  let showIntro = $state<boolean>(true);
  let introPhase = $state<number>(0); // 0: Logo draw, 1: Text reveal, 2: HUD scan, 3: Shutter exit
  let particleCanvas = $state<HTMLCanvasElement | null>(null);

  function dismissIntro() {
    introPhase = 3;
    setTimeout(() => {
      showIntro = false;
    }, 450);
  }

  // Local Device Identity
  let localIdentity = $state<Device | null>(null);
  let pairingPin = $state<string>("");
  let showPairingModal = $state<boolean>(false);
  let qrCanvas = $state<HTMLCanvasElement | null>(null);
  let homeQrCanvas = $state<HTMLCanvasElement | null>(null);

  function getQrPayload(): string {
    if (!localIdentity) return "";
    return JSON.stringify({
      ip: localIdentity.ip,
      port: 53317,
      fn: localIdentity.fingerprint,
      pin: pairingPin || "123456"
    });
  }

  function renderQr(canvas: HTMLCanvasElement | null, size = 200) {
    if (!canvas) return;
    const data = getQrPayload();
    if (!data) {
      // If data is not ready yet, draw a placeholder background
      const ctx = canvas.getContext('2d');
      if (ctx) {
        ctx.fillStyle = '#ffffff';
        ctx.fillRect(0, 0, size, size);
      }
      return;
    }
    QRCode.toCanvas(canvas, data, {
      width: size,
      margin: 1,
      color: {
        dark: '#000000',
        light: '#ffffff'
      },
      errorCorrectionLevel: 'medium'
    }, (err) => {
      if (err) console.error("QR drawing error:", err);
    });
  }

  function qrAction(node: HTMLCanvasElement, size = 180) {
    const tryRender = () => {
      renderQr(node, size);
      if (!localIdentity || !pairingPin) {
        setTimeout(tryRender, 250);
      }
    };
    tryRender();
    return {
      update(newSize: number) {
        renderQr(node, newSize);
      }
    };
  }

  $effect(() => {
    if (localIdentity && pairingPin) {
      if (qrCanvas) renderQr(qrCanvas, 200);
      if (homeQrCanvas) renderQr(homeQrCanvas, 180);
    }
  });

  // Discovery State
  let discoveredDevices = $state<Device[]>([]);
  let pairedDevices = $state<any[]>([]);
  let isScanning = $state<boolean>(false);
  let user = $state<any>(null);
  let bypassCloudAuth = $state<boolean>(true);
  let isAuthInitializing = $state<boolean>(true);
  let unsubscribeAuth = $state<any>(null);
  let unsubscribeDevices = $state<any>(null);

  // Clipboard Sync
  let clipboardSyncActive = $state<boolean>(false);
  let lastClipboardEvent = $state<ClipboardEvent | null>(null);

  // Active Transfers Progress
  let activeTransfers = $state<Record<string, TransferProgress>>({});

  // Notifications state
  let activeNotifications = $state<any[]>([]);
  let replyInputs = $state<Record<string, string>>({});

  // Screen cast states
  let isMirroring = $state<boolean>(false);
  let activeTab = $state<string>("overview");

  // Phone Call states
  interface IncomingCall {
    phoneNumber: string;
    callerName: string;
    timestamp: number;
  }
  let activeCall = $state<IncomingCall | null>(null);
  let callState = $state<string>("idle"); // "ringing" | "offhook" | "idle"
  let dialerNumber = $state<string>("");
  let callDuration = $state<number>(0);
  let callTimerInterval: ReturnType<typeof setInterval> | null = null;
  let currentLocalTime = $state<string>("");
  let timeInterval: ReturnType<typeof setInterval> | null = null;

  // System Bridge Telemetry (Real-time live sync)
  let cachedBattery = typeof localStorage !== 'undefined' && localStorage.getItem('janus_last_battery') ? parseInt(localStorage.getItem('janus_last_battery')!) : null;
  let cachedSignal = typeof localStorage !== 'undefined' && localStorage.getItem('janus_last_signal') ? parseInt(localStorage.getItem('janus_last_signal')!) : null;
  let deviceBattery = $state<number | null>(cachedBattery);
  let deviceIsCharging = $state<boolean>(false);
  let deviceSignal = $state<number | null>(cachedSignal);

  // Audio output devices
  let audioOutputs = $state<MediaDeviceInfo[]>([]);
  let selectedAudioOutput = $state<string>("");

  // History & Messages
  let callHistory = $state<any[]>([]);
  let smsMessages = $state<any[]>([]);
  let activeSmsThread = $state<string | null>(null);

  // Derived SMS threads
  let smsThreads = $derived(
    (() => {
      const threadsMap = new Map<string, any[]>();
      for (const msg of smsMessages) {
        const key = msg.address;
        if (!threadsMap.has(key)) threadsMap.set(key, []);
        threadsMap.get(key)!.push(msg);
      }
      const list = [];
      for (const [address, msgs] of threadsMap) {
        list.push({
          address,
          name: msgs[0].name || address,
          lastMessage: msgs[0].body,
          lastDate: msgs[0].date,
          messages: [...msgs].reverse(), // oldest to newest
        });
      }
      return list.sort((a, b) => b.lastDate - a.lastDate);
    })()
  );

  // Drag and drop state
  let isDragOver = $state<boolean>(false);
  let dragTargetDevice = $state<any | null>(null);
  
  // Toasts
  let toasts = $state<{ id: string; message: string; type: "success" | "error" | "info" }[]>([]);

  // Event listener unlisteners
  let unlisteners: (() => void)[] = [];

  // ──────────────────────────────────────────────
  // DE-DUPLICATED paired devices list.
  // Groups by device name; picks the online instance first, then most-recently-paired.
  // ──────────────────────────────────────────────
  let pairedDevicesList = $derived(
    (() => {
      // Build raw list with online status
      const rawList = pairedDevices.map((paired) => {
        const discovered = discoveredDevices.find((d) => d.fingerprint === paired.fingerprint);
        return {
          name: paired.name,
          fingerprint: paired.fingerprint,
          added_at: paired.added_at || 0,
          online: !!discovered,
          ip: discovered?.ip || "",
          port: discovered?.port || 0,
          device_type: discovered?.device_type || "android",
        };
      });

      // Group by name, pick the best representative
      const grouped = new Map<string, typeof rawList>();
      for (const dev of rawList) {
        const key = dev.name;
        if (!grouped.has(key)) grouped.set(key, []);
        grouped.get(key)!.push(dev);
      }

      const deduped: typeof rawList = [];
      for (const [_name, entries] of grouped) {
        // Prefer online, then most recent
        const onlineEntry = entries.find((e) => e.online);
        if (onlineEntry) {
          deduped.push({ ...onlineEntry, _allFingerprints: entries.map(e => e.fingerprint) } as any);
        } else {
          const sorted = [...entries].sort((a, b) => b.added_at - a.added_at);
          deduped.push({ ...sorted[0], _allFingerprints: entries.map(e => e.fingerprint) } as any);
        }
      }
      return deduped;
    })()
  );

  // ──────────────────────────────────────────────
  // ROBUST ACTIVE CONNECTED DEVICE DERIVATION
  // Only treats a device as connected if it is explicitly paired and online
  // ──────────────────────────────────────────────
  let activeConnectedDevice = $derived(
    (() => {
      // 1. Check if any device in pairedDevicesList is currently online
      const onlinePaired = pairedDevicesList.find((d) => d.online);
      if (onlinePaired) return onlinePaired;

      // 2. Check if any discovered device is online or active
      const onlineDiscovered = discoveredDevices.find((d) => (d as any).online || d.paired);
      if (onlineDiscovered) return onlineDiscovered;

      // 3. If any device exists in discoveredDevices, treat it as active
      if (discoveredDevices.length > 0) return discoveredDevices[0];

      return null;
    })()
  );

  let isDeviceConnected = $derived(!!activeConnectedDevice);

  // Derived state: discovered devices that are not paired yet
  let unpairedDiscovered = $derived(
    discoveredDevices.filter(
      (d) => !pairedDevices.some((p) => p.fingerprint === d.fingerprint)
    )
  );

  // ──────────────────────────────────────────────
  // Functions
  // ──────────────────────────────────────────────

  async function syncLiveState() {
    try {
      await loadConnectedDevices();
      await loadPairedDevices();

      const telemetry = await invoke<any>("get_latest_telemetry");
      if (telemetry && telemetry.battery_level !== undefined && telemetry.battery_level !== null) {
        deviceBattery = telemetry.battery_level;
        deviceIsCharging = telemetry.is_charging || false;
        deviceSignal = telemetry.signal_level ?? 4;
      }

      const notifs = await invoke<any[]>("get_recent_notifications");
      if (notifs && notifs.length > 0) {
        activeNotifications = notifs;
      }

      const calls = await invoke<any[]>("get_call_history");
      if (calls && calls.length > 0) {
        callHistory = calls;
      }

      const sms = await invoke<any[]>("get_sms_messages");
      if (sms && sms.length > 0) {
        smsMessages = sms;
      }

      invoke("request_device_status").catch(() => {});
    } catch (e) {
      console.error("Live state sync error:", e);
    }
  }

  async function selectTab(tab: string) {
    syncLiveState();
    activeTab = tab;
    if (tab === "history") {
      try {
        await invoke("sync_calls");
      } catch (e) {
        showToast("Failed to request call logs: " + e, "error");
      }
    } else if (tab === "messages") {
      try {
        await invoke("sync_sms");
      } catch (e) {
        showToast("Failed to request SMS list: " + e, "error");
      }
    }
  }

  async function loadAudioOutputs() {
    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
      audioOutputs = devices.filter((d) => d.kind === "audiooutput");
      if (audioOutputs.length > 0 && !selectedAudioOutput) {
        selectedAudioOutput = audioOutputs[0].deviceId;
      }
    } catch (err) {
      console.error("Failed to list audio outputs:", err);
    }
  }

  async function changeAudioOutput(deviceId: string) {
    selectedAudioOutput = deviceId;
    if (audioCtx && (audioCtx as any).setSinkId) {
      try {
        await (audioCtx as any).setSinkId(deviceId);
        showToast("Audio output routed: " + (audioOutputs.find(d => d.deviceId === deviceId)?.label || deviceId), "success");
      } catch (err) {
        showToast("Failed to set audio output: " + err, "error");
      }
    }
  }

  function showToast(message: string, type: "success" | "error" | "info" = "info") {
    const id = Math.random().toString(36).substring(2, 9);
    toasts = [...toasts, { id, message, type }];
    setTimeout(() => {
      toasts = toasts.filter((t) => t.id !== id);
    }, 4000);
  }

  function formatCallDuration(seconds: number): string {
    const m = Math.floor(seconds / 60).toString().padStart(2, "0");
    const s = (seconds % 60).toString().padStart(2, "0");
    return `${m}:${s}`;
  }

  async function loadIdentity() {
    try {
      localIdentity = await invoke<Device>("get_identity");
    } catch (e) {
      showToast("Failed to fetch local identity: " + e, "error");
    }
  }

  async function loadPairedDevices() {
    try {
      pairedDevices = await invoke<any[]>("get_paired_devices");
    } catch (e) {
      console.error("Failed to load paired devices:", e);
    }
  }

  async function loadConnectedDevices() {
    try {
      const active = await invoke<Device[]>("get_connected_devices");
      for (const dev of active) {
        const isPaired = pairedDevices.some((p) => p.fingerprint === dev.fingerprint);
        const existing = discoveredDevices.find((d) => d.fingerprint === dev.fingerprint);
        if (existing) {
          discoveredDevices = discoveredDevices.map((d) =>
            d.fingerprint === dev.fingerprint
              ? { ...d, ...dev, paired: isPaired || d.paired }
              : d
          );
        } else {
          discoveredDevices = [...discoveredDevices, { ...dev, paired: isPaired }];
        }
      }
    } catch (e) {
      console.error("Failed to load connected devices:", e);
    }
  }

  async function toggleScanning() {
    try {
      if (isScanning) {
        await invoke("stop_discovery");
        isScanning = false;
        showToast("Stopped scanning for devices", "info");
      } else {
        await invoke("start_discovery");
        isScanning = true;
        showToast("Scanning for nearby Janus devices...", "success");
      }
    } catch (e) {
      showToast("Discovery error: " + e, "error");
    }
  }

  async function toggleClipboardSync() {
    try {
      if (clipboardSyncActive) {
        await invoke("stop_clipboard_sync");
        clipboardSyncActive = false;
        showToast("Clipboard sync stopped", "info");
      } else {
        await invoke("start_clipboard_sync");
        clipboardSyncActive = true;
        showToast("Clipboard sync active", "success");
      }
    } catch (e) {
      showToast("Clipboard sync error: " + e, "error");
    }
  }

  async function generatePairingPin() {
    try {
      if (!localIdentity) {
        localIdentity = await invoke<Device>("get_identity");
      }
      pairingPin = await invoke<string>("get_pairing_pin");
      showPairingModal = true;
      setTimeout(() => {
        if (qrCanvas) renderQr(qrCanvas, 200);
      }, 50);
    } catch (e) {
      showToast("Failed to generate PIN: " + e, "error");
    }
  }

  async function triggerFileSelect(device: any) {
    try {
      const selected = await open({
        multiple: false,
        title: `Select file to send to ${device.name}`,
      });

      if (!selected) return;

      const filepath = typeof selected === "string" ? selected : selected;

      showToast(`Sending file to ${device.name}...`, "info");
      await invoke("send_file_to_device", {
        deviceIp: device.ip,
        devicePort: device.port,
        filePath: filepath
      });
      showToast("File sent successfully!", "success");
    } catch (e) {
      showToast("Failed to send file: " + e, "error");
    }
  }

  // Drag and drop handlers
  function handleDragOver(e: DragEvent) {
    e.preventDefault();
    isDragOver = true;
  }

  function handleDragLeave(e: DragEvent) {
    isDragOver = false;
  }

  function handleDrop(e: DragEvent, device: any) {
    e.preventDefault();
    isDragOver = false;
    const files = e.dataTransfer?.files;
    if (files && files.length > 0) {
      const file = files[0];
      const path = (file as any).path;
      if (path) {
        sendFilePath(device, path);
      } else {
        showToast("Drag and drop requires native paths — use the file picker instead", "info");
      }
    }
  }

  async function sendFilePath(device: any, filepath: string) {
    try {
      showToast(`Sending file to ${device.name}...`, "info");
      await invoke("send_file_to_device", {
        deviceIp: device.ip,
        devicePort: device.port,
        filePath: filepath
      });
      showToast("File sent successfully!", "success");
    } catch (e) {
      showToast("Failed to send file: " + e, "error");
    }
  }

  async function replyToNotification(notificationId: string, replyText: string) {
    if (!replyText || !replyText.trim()) return;
    try {
      await invoke("send_notification_reply", { notificationId, replyText });
      showToast("Reply sent!", "success");
      replyInputs[notificationId] = "";
    } catch (e) {
      showToast("Failed to send reply: " + e, "error");
    }
  }

  async function dismissNotification(notificationId: string) {
    try {
      await invoke("dismiss_remote_notification", { notificationId });
      activeNotifications = activeNotifications.filter((n) => n.notification_id !== notificationId);
      showToast("Notification dismissed", "info");
    } catch (e) {
      showToast("Failed to dismiss notification: " + e, "error");
    }
  }

  async function startMirroring() {
    try {
      await invoke("start_screencast");
      isMirroring = true;
      showToast("Requested screen mirror from device...", "info");
    } catch (e) {
      showToast("Failed to start mirror: " + e, "error");
    }
  }

  async function stopMirroring() {
    try {
      await invoke("stop_screencast");
      isMirroring = false;
      showToast("Screen mirror stopped", "info");
    } catch (e) {
      showToast("Failed to stop mirror: " + e, "error");
    }
  }

  async function makePhoneCall() {
    if (!dialerNumber.trim()) return;
    try {
      await invoke("make_phone_call", { phoneNumber: dialerNumber });
      showToast(`Dialing ${dialerNumber} on Android...`, "success");
    } catch (e) {
      showToast("Failed to make call: " + e, "error");
    }
  }

  async function answerCall() {
    try {
      await invoke("answer_phone_call");
      callState = "offhook";
      callDuration = 0;
      callTimerInterval = setInterval(() => { callDuration += 1; }, 1000);
      showToast("Call answered", "success");
      startMicCapture();
    } catch (e) {
      showToast("Failed to answer call: " + e, "error");
    }
  }

  async function declineCall() {
    try {
      await invoke("hangup_phone_call");
      callState = "idle";
      activeCall = null;
      if (callTimerInterval) { clearInterval(callTimerInterval); callTimerInterval = null; }
      callDuration = 0;
      showToast("Call ended", "info");
      stopMicCapture();
    } catch (e) {
      showToast("Failed to end call: " + e, "error");
    }
  }

  // ──────────────────────────────────────────────
  // Unpair device — removes ALL fingerprints for that device name
  // ──────────────────────────────────────────────
  async function unpairDevice(device: any) {
    try {
      const allFingerprints: string[] = (device as any)._allFingerprints || [device.fingerprint];
      for (const fp of allFingerprints) {
        await invoke("unpair_device", { fingerprint: fp });
      }
      pairedDevices = pairedDevices.filter(p => !allFingerprints.includes(p.fingerprint));
      discoveredDevices = discoveredDevices.filter(d => !allFingerprints.includes(d.fingerprint));
      deviceBattery = null;
      deviceSignal = null;
      showToast(`${device.name} forgotten and disconnected`, "info");
      await loadPairedDevices();
    } catch (e) {
      showToast("Failed to unpair device: " + e, "error");
    }
  }

  // Audio state
  let audioCtx: AudioContext | null = null;
  let nextStartTime = 0;
  let micStream: MediaStream | null = null;
  let micSource: MediaStreamAudioSourceNode | null = null;
  let scriptNode: ScriptProcessorNode | null = null;

  // Remote operation state
  let isPointerDown = false;
  let startPointerX = 0;
  let startPointerY = 0;
  let startPointerTime = 0;

  function playPCMFrame(pcmBytes: Uint8Array) {
    if (!audioCtx) {
      audioCtx = new (window.AudioContext || (window as any).webkitAudioContext)();
      if (selectedAudioOutput && (audioCtx as any).setSinkId) {
        (audioCtx as any).setSinkId(selectedAudioOutput).catch(console.error);
      }
      nextStartTime = audioCtx.currentTime;
    }
    
    const int16Array = new Int16Array(pcmBytes.buffer, pcmBytes.byteOffset, pcmBytes.byteLength / 2);
    const floatArray = new Float32Array(int16Array.length);
    for (let i = 0; i < int16Array.length; i++) {
      floatArray[i] = int16Array[i] / 32768.0;
    }
    
    const buffer = audioCtx.createBuffer(1, floatArray.length, 16000);
    buffer.copyToChannel(floatArray, 0);
    
    const source = audioCtx.createBufferSource();
    source.buffer = buffer;
    source.connect(audioCtx.destination);
    
    const now = audioCtx.currentTime;
    if (nextStartTime < now) {
      nextStartTime = now;
    }
    
    source.start(nextStartTime);
    nextStartTime += buffer.duration;
  }

  async function startMicCapture() {
    try {
      if (!audioCtx) {
        audioCtx = new (window.AudioContext || (window as any).webkitAudioContext)({ sampleRate: 16000 });
      }
      if (selectedAudioOutput && (audioCtx as any).setSinkId) {
        try {
          await (audioCtx as any).setSinkId(selectedAudioOutput);
        } catch (e) {
          console.error("Failed to set sink ID during mic capture init", e);
        }
      }
      
      micStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        }
      });
      
      micSource = audioCtx.createMediaStreamSource(micStream);
      scriptNode = audioCtx.createScriptProcessor(2048, 1, 1);
      scriptNode.onaudioprocess = (e) => {
        const inputData = e.inputBuffer.getChannelData(0);
        const pcmBytes = new Uint8Array(inputData.length * 2);
        const dataView = new DataView(pcmBytes.buffer);
        for (let i = 0; i < inputData.length; i++) {
          const sample = Math.max(-1, Math.min(1, inputData[i]));
          const pcmVal = sample < 0 ? sample * 0x8000 : sample * 0x7FFF;
          dataView.setInt16(i * 2, pcmVal, true);
        }
        
        invoke("send_audio_frame", { bytes: Array.from(pcmBytes) }).catch((err) => {
          console.error("Failed to send mic frame:", err);
        });
      };
      
      micSource.connect(scriptNode);
      scriptNode.connect(audioCtx.destination);
      console.log("Mic capture started at sample rate:", audioCtx.sampleRate);
    } catch (err) {
      showToast("Failed to access microphone: " + err, "error");
    }
  }

  function stopMicCapture() {
    if (scriptNode) {
      scriptNode.disconnect();
      scriptNode = null;
    }
    if (micSource) {
      micSource.disconnect();
      micSource = null;
    }
    if (micStream) {
      micStream.getTracks().forEach((track) => track.stop());
      micStream = null;
    }
    console.log("Mic capture stopped");
  }

  function handlePointerDown(e: PointerEvent) {
    const canvas = e.currentTarget as HTMLCanvasElement;
    const rect = canvas.getBoundingClientRect();
    isPointerDown = true;
    startPointerX = e.clientX - rect.left;
    startPointerY = e.clientY - rect.top;
    startPointerTime = Date.now();
    canvas.setPointerCapture(e.pointerId);
  }

  function handlePointerUp(e: PointerEvent) {
    if (!isPointerDown) return;
    isPointerDown = false;
    
    const canvas = e.currentTarget as HTMLCanvasElement;
    canvas.releasePointerCapture(e.pointerId);
    
    const rect = canvas.getBoundingClientRect();
    const endX = e.clientX - rect.left;
    const endY = e.clientY - rect.top;
    const duration = Date.now() - startPointerTime;
    
    const dx = endX - startPointerX;
    const dy = endY - startPointerY;
    const dist = Math.sqrt(dx * dx + dy * dy);
    
    const width = rect.width;
    const height = rect.height;
    
    if (dist < 8 && duration < 300) {
      const clickX = startPointerX / width;
      const clickY = startPointerY / height;
      invoke("inject_remote_click", { x: clickX, y: clickY }).catch((err) => {
        console.error("Failed to inject remote click:", err);
      });
    } else {
      const startX = startPointerX / width;
      const startY = startPointerY / height;
      const endXRatio = endX / width;
      const endYRatio = endY / height;
      
      invoke("inject_remote_swipe", {
        startX,
        startY,
        endX: endXRatio,
        endY: endYRatio,
        duration: Math.max(100, duration)
      }).catch((err) => {
        console.error("Failed to inject remote swipe:", err);
      });
    }
  }

  async function sendRemoteKey(key: string) {
    try {
      await invoke("inject_remote_key", { key });
    } catch (e) {
      showToast("Failed to send system key: " + e, "error");
    }
  }

  onMount(async () => {
    // Apply saved theme
    if (typeof document !== 'undefined') {
      document.documentElement.setAttribute('data-theme', currentTheme);
    }

    // Start Intro Motion sequence
    setTimeout(() => { introPhase = 1; }, 400);
    setTimeout(() => { introPhase = 2; }, 1100);
    setTimeout(() => { dismissIntro(); }, 2800);

    // Particle Canvas Animation
    if (particleCanvas) {
      const ctx = particleCanvas.getContext('2d');
      if (ctx) {
        let width = (particleCanvas.width = window.innerWidth);
        let height = (particleCanvas.height = window.innerHeight);
        const particles: { x: number; y: number; vx: number; vy: number; size: number; alpha: number; color: string }[] = [];
        const colors = ['#c084fc', '#818cf8', '#38bdf8', '#34d399'];

        for (let i = 0; i < 45; i++) {
          particles.push({
            x: Math.random() * width,
            y: Math.random() * height,
            vx: (Math.random() - 0.5) * 0.8,
            vy: (Math.random() - 0.5) * 0.8,
            size: Math.random() * 2.5 + 1,
            alpha: Math.random() * 0.7 + 0.3,
            color: colors[Math.floor(Math.random() * colors.length)]
          });
        }

        let animFrame: number;
        function renderParticles() {
          if (!showIntro) return;
          ctx.clearRect(0, 0, width, height);
          for (let i = 0; i < particles.length; i++) {
            const p = particles[i];
            p.x += p.vx;
            p.y += p.vy;
            if (p.x < 0) p.x = width;
            if (p.x > width) p.x = 0;
            if (p.y < 0) p.y = height;
            if (p.y > height) p.y = 0;

            ctx.beginPath();
            ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
            ctx.fillStyle = p.color;
            ctx.globalAlpha = p.alpha * 0.6;
            ctx.fill();

            // Connect nearby particles with laser filaments
            for (let j = i + 1; j < particles.length; j++) {
              const p2 = particles[j];
              const dist = Math.hypot(p.x - p2.x, p.y - p2.y);
              if (dist < 110) {
                ctx.beginPath();
                ctx.moveTo(p.x, p.y);
                ctx.lineTo(p2.x, p2.y);
                ctx.strokeStyle = p.color;
                ctx.globalAlpha = (1 - dist / 110) * 0.25;
                ctx.lineWidth = 0.75;
                ctx.stroke();
              }
            }
          }
          animFrame = requestAnimationFrame(renderParticles);
        }
        renderParticles();
      }
    }

    // Start local time updater for status bar mockup
    const updateTime = () => {
      const now = new Date();
      currentLocalTime = now.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });
    };
    updateTime();
    timeInterval = setInterval(updateTime, 10000);

    // Listen to Firebase Auth state
    unsubscribeAuth = onAuthStateChanged(auth, (u) => {
      user = u;
      isAuthInitializing = false;
      if (u) {
        // Start syncing presence/clipboard
        startCloudSync(u.uid);
        
        // Listen to Firestore devices to replace local device listings
        const devicesCol = collection(db, "users", u.uid, "devices");
        unsubscribeDevices = onSnapshot(devicesCol, (snapshot) => {
          let list: any[] = [];
          snapshot.forEach((doc) => {
            const data = doc.data();
            list.push({
              name: data.name,
              ip: data.localIp,
              port: data.port,
              fingerprint: data.deviceId,
              device_type: data.type,
              paired: true,
              online: data.status === "online",
              batteryPct: data.batteryPct || 100,
              isCharging: data.isCharging || false
            });
          });
          pairedDevices = list;
          discoveredDevices = list;
        });

        // Start clipboard sync in Rust backend so we receive clipboard events
        invoke("start_clipboard_sync").catch(console.error);
        clipboardSyncActive = true;
      } else {
        stopCloudSync();
        if (unsubscribeDevices) {
          unsubscribeDevices();
          unsubscribeDevices = null;
        }
        // Do NOT wipe local devices in offline/P2P mode!
        loadPairedDevices();
        loadConnectedDevices();
      }
    });

    await loadIdentity();
    await loadPairedDevices();
    await loadConnectedDevices();
    await syncLiveState();
    const connCheckInterval = setInterval(() => {
      syncLiveState();
    }, 1500);
    onDestroy(() => clearInterval(connCheckInterval));
    
    // Auto-generate pairing PIN on startup
    try {
      pairingPin = await invoke<string>("get_pairing_pin");
    } catch (e) {
      console.error("Failed to generate initial PIN:", e);
    }
    
    // Auto-start discovery
    await toggleScanning();

    // Periodic connected devices heartbeat check
    const connectedHeartbeat = setInterval(async () => {
      await loadConnectedDevices();
    }, 4000);
    unlisteners.push(() => clearInterval(connectedHeartbeat));

    // Request notification permission
    if (typeof Notification !== "undefined" && Notification.permission !== "granted") {
      Notification.requestPermission();
    }

    // Load audio devices and register change listener
    await loadAudioOutputs();
    navigator.mediaDevices.addEventListener("devicechange", loadAudioOutputs);

    // Check if clipboard sync was already running
    try {
      clipboardSyncActive = await invoke<boolean>("get_clipboard_sync_status");
    } catch (_) {}

    // Listen to device resolution
    const unlistenDiscovered = await listen<Device>("device-discovered", async (event) => {
      const dev = event.payload;

      // Refresh paired devices list FIRST to avoid race condition
      // where device.register arrives before the paired list has been
      // updated (e.g., right after a pairing + register on the same socket)
      await loadPairedDevices();

      const isPaired = dev.paired || pairedDevices.some((p) => p.fingerprint === dev.fingerprint);
      const existing = discoveredDevices.find((d) => d.fingerprint === dev.fingerprint);
      if (existing) {
        // Update existing device (e.g. new IP/port from WebSocket registration)
        discoveredDevices = discoveredDevices.map((d) =>
          d.fingerprint === dev.fingerprint
            ? { ...d, ...dev, paired: isPaired || d.paired }
            : d
        );
      } else {
        discoveredDevices = [...discoveredDevices, { ...dev, paired: isPaired }];
        showToast(`Found device: ${dev.name}`, "info");
      }

      // Auto-start clipboard sync when a paired device comes online
      if (isPaired && !clipboardSyncActive) {
        try {
          await invoke("start_clipboard_sync");
          clipboardSyncActive = true;
        } catch (e) {
          console.error("Auto-start clipboard sync failed:", e);
        }
      }
    });
    unlisteners.push(unlistenDiscovered);

    const unlistenRemoved = await listen<string>("device-removed", (event) => {
      const payload = event.payload;
      // Match by full fingerprint OR partial fingerprint (mDNS service name contains first 8 chars)
      discoveredDevices = discoveredDevices.filter((d) =>
        d.fingerprint !== payload && !payload.includes(d.fingerprint.substring(0, 8))
      );
      if (discoveredDevices.length === 0) {
        deviceBattery = null;
        deviceSignal = null;
        deviceIsCharging = false;
      }
    });
    unlisteners.push(unlistenRemoved);

    const unlistenPaired = await listen<Device>("device-paired", (event) => {
      const dev = event.payload;
      discoveredDevices = discoveredDevices.map((d) => 
        d.fingerprint === dev.fingerprint ? { ...d, paired: true } : d
      );
      loadPairedDevices();
      showToast(`Successfully paired with ${dev.name}!`, "success");
      showPairingModal = false;
    });
    unlisteners.push(unlistenPaired);

    // File transfer events
    const unlistenProgress = await listen<TransferProgress>("transfer-progress", (event) => {
      const progress = event.payload;
      activeTransfers = {
        ...activeTransfers,
        [progress.file_hash]: progress
      };
    });
    unlisteners.push(unlistenProgress);

    // Clipboard sync events
    const unlistenClipboard = await listen<ClipboardEvent>("clipboard-synced", (event) => {
      lastClipboardEvent = event.payload;
      const direction = event.payload.source === "local" ? "→ Sent to Android" : "← Received from Android";
      showToast(`📋 Clipboard ${direction}`, "success");
    });
    unlisteners.push(unlistenClipboard);

    // Legacy remote clipboard (for backward compat)
    const unlistenRemoteClip = await listen<any>("remote-clipboard-update", (_event) => {
      // Handled by clipboard-synced event above
    });
    unlisteners.push(unlistenRemoteClip);

    // Listen to notification events
    const unlistenNotificationNew = await listen<any>("notification-new", (event) => {
      const newNotif = event.payload;
      activeNotifications = [
        ...activeNotifications.filter((n) => n.notification_id !== newNotif.notification_id),
        newNotif
      ];

      // Trigger native notification
      if (typeof Notification !== "undefined" && Notification.permission === "granted") {
        new Notification(newNotif.title || newNotif.app_name || "Janus Notification", {
          body: newNotif.text || "",
          icon: newNotif.app_icon ? `data:image/png;base64,${newNotif.app_icon}` : undefined,
        });
      }
    });
    unlisteners.push(unlistenNotificationNew);

    const unlistenNotificationDismiss = await listen<any>("notification-dismiss", (event) => {
      const payload = event.payload;
      activeNotifications = activeNotifications.filter((n) => n.notification_id !== payload.notification_id);
    });
    unlisteners.push(unlistenNotificationDismiss);

    const unlistenScreencast = await listen<number[]>("screencast-frame", (event) => {
      isMirroring = true;
      const frameBytes = new Uint8Array(event.payload);
      const blob = new Blob([frameBytes], { type: "image/jpeg" });
      const url = URL.createObjectURL(blob);
      
      const canvas = document.getElementById("screencast-canvas") as HTMLCanvasElement | null;
      if (canvas) {
        const ctx = canvas.getContext("2d");
        const img = new Image();
        img.onload = () => {
          canvas.width = img.width;
          canvas.height = img.height;
          ctx?.drawImage(img, 0, 0);
          URL.revokeObjectURL(url);
        };
        img.src = url;
      }
    });
    unlisteners.push(unlistenScreencast);

    const unlistenCallIncoming = await listen<any>("call-incoming", (event) => {
      const payload = event.payload;
      activeCall = {
        phoneNumber: payload.phone_number,
        callerName: payload.caller_name,
        timestamp: payload.timestamp
      };
      callState = "ringing";
      showToast(`Incoming call: ${payload.caller_name}`, "info");
    });
    unlisteners.push(unlistenCallIncoming);

    const unlistenCallState = await listen<any>("call-state", (event) => {
      const payload = event.payload;
      callState = payload.state;
      if (callState === "offhook") {
        callDuration = 0;
        if (!callTimerInterval) {
          callTimerInterval = setInterval(() => { callDuration += 1; }, 1000);
        }
        startMicCapture();
      } else if (callState === "idle") {
        activeCall = null;
        if (callTimerInterval) { clearInterval(callTimerInterval); callTimerInterval = null; }
        callDuration = 0;
        stopMicCapture();
      }
    });
    unlisteners.push(unlistenCallState);

    const unlistenCallAudio = await listen<number[]>("call-audio-frame", (event) => {
      const audioBytes = new Uint8Array(event.payload);
      playPCMFrame(audioBytes);
    });
    unlisteners.push(unlistenCallAudio);

    const unlistenDeviceStatus = await listen<any>("device-status", async (event) => {
      const payload = event.payload;
      console.log("📡 Device telemetry received:", JSON.stringify(payload));
      if (payload.battery_level !== undefined && payload.battery_level !== null) {
        deviceBattery = payload.battery_level;
        if (typeof localStorage !== 'undefined') localStorage.setItem('janus_last_battery', String(payload.battery_level));
      }
      if (payload.is_charging !== undefined && payload.is_charging !== null) {
        deviceIsCharging = payload.is_charging;
      }
      if (payload.signal_level !== undefined && payload.signal_level !== null) {
        deviceSignal = payload.signal_level;
        if (typeof localStorage !== 'undefined') localStorage.setItem('janus_last_signal', String(payload.signal_level));
      }

      // If discoveredDevices is empty, sync from active connected devices immediately
      if (discoveredDevices.length === 0) {
        await loadConnectedDevices();
        await loadPairedDevices();
      }
    });
    unlisteners.push(unlistenDeviceStatus);

    const unlistenDeviceRegistered = await listen<any>("device-registered", async (event) => {
      console.log("🟢 Device registered via WebSocket:", event.payload);
      const dev = event.payload;
      if (dev && dev.fingerprint) {
        const existingIdx = discoveredDevices.findIndex(d => d.fingerprint === dev.fingerprint);
        const entry: Device = {
          name: dev.name || dev.device_name || "Android Device",
          ip: dev.ip || "",
          port: dev.port || 53318,
          fingerprint: dev.fingerprint,
          device_type: dev.device_type || "android",
          paired: true
        };
        if (existingIdx >= 0) {
          discoveredDevices[existingIdx] = entry;
          discoveredDevices = [...discoveredDevices];
        } else {
          discoveredDevices = [...discoveredDevices, entry];
        }
      }
      await loadPairedDevices();
      await loadConnectedDevices();
    });
    unlisteners.push(unlistenDeviceRegistered);

    const unlistenDeviceReady = await listen<any>("device-ready", async (event) => {
      console.log("🟢 Device confirmed ready:", event.payload);
      const payload = event.payload;
      if (payload && payload.fingerprint) {
        const existingIdx = discoveredDevices.findIndex(d => d.fingerprint === payload.fingerprint);
        const entry: Device = {
          name: payload.name || payload.device_name || "Android Device",
          ip: payload.ip || "",
          port: payload.port || 53318,
          fingerprint: payload.fingerprint,
          device_type: payload.device_type || "android",
          paired: true
        };
        if (existingIdx >= 0) {
          discoveredDevices[existingIdx] = entry;
          discoveredDevices = [...discoveredDevices];
        } else {
          discoveredDevices = [...discoveredDevices, entry];
        }
      }
      await loadPairedDevices();
      await loadConnectedDevices();
      if (payload.battery_level !== undefined) deviceBattery = payload.battery_level;
      if (payload.is_charging !== undefined) deviceIsCharging = payload.is_charging;
      if (payload.signal_level !== undefined) deviceSignal = payload.signal_level;
    });
    unlisteners.push(unlistenDeviceReady);

    const unlistenCallsList = await listen<any>("calls-list", (event) => {
      const payload = event.payload;
      callHistory = payload.calls || [];
    });
    unlisteners.push(unlistenCallsList);

    const unlistenSmsList = await listen<any>("sms-list", (event) => {
      const payload = event.payload;
      smsMessages = payload.sms || [];
    });
    unlisteners.push(unlistenSmsList);
  });

  onDestroy(() => {
    unlisteners.forEach((u) => u());
    navigator.mediaDevices.removeEventListener("devicechange", loadAudioOutputs);
    invoke("stop_discovery").catch(() => {});
    invoke("stop_clipboard_sync").catch(() => {});
    if (callTimerInterval) { clearInterval(callTimerInterval); callTimerInterval = null; }
    if (timeInterval) { clearInterval(timeInterval); timeInterval = null; }
    if (unsubscribeAuth) unsubscribeAuth();
    if (unsubscribeDevices) unsubscribeDevices();
    stopCloudSync();
  });
</script>

<svelte:head>
  <link rel="preconnect" href="https://fonts.googleapis.com" />
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin="anonymous" />
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&display=swap" rel="stylesheet" />
</svelte:head>

{#if isAuthInitializing}
  <div class="auth-loading-screen">
    <div class="auth-loading-card">
      <div class="auth-spinner"></div>
      <p class="auth-loading-title">Restoring Janus Session...</p>
    </div>
  </div>
{:else if !user && !bypassCloudAuth}
  <Login onSkip={() => { bypassCloudAuth = true; }} />
{:else}
  <div class="janus-layout">
  <!-- Toast notification list -->
  <div class="toast-container">
    {#each toasts as toast (toast.id)}
      <div class="toast toast-{toast.type}">
        <span class="toast-icon">
          {#if toast.type === "success"}
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="7" stroke="#34d399" stroke-width="1.5"/><path d="M5 8l2 2 4-4" stroke="#34d399" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
          {:else if toast.type === "error"}
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="7" stroke="#f87171" stroke-width="1.5"/><path d="M6 6l4 4M10 6l-4 4" stroke="#f87171" stroke-width="1.5" stroke-linecap="round"/></svg>
          {:else}
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="7" stroke="#a78bfa" stroke-width="1.5"/><path d="M8 5v3M8 10v1" stroke="#a78bfa" stroke-width="1.5" stroke-linecap="round"/></svg>
          {/if}
        </span>
        <span class="toast-message">{toast.message}</span>
      </div>
    {/each}
  </div>

  <!-- LEFT SIDEBAR -->
  <aside class="sidebar">
    <!-- Branding -->
    <div class="sidebar-header">
      <div class="logo-mark">
        <svg width="28" height="28" viewBox="0 0 28 28" fill="none">
          <rect x="2" y="2" width="24" height="24" rx="7" stroke="url(#g1)" stroke-width="2"/>
          <path d="M10 8v12M14 10v8M18 8v12" stroke="url(#g1)" stroke-width="2" stroke-linecap="round"/>
          <defs><linearGradient id="g1" x1="2" y1="2" x2="26" y2="26"><stop stop-color="#2563eb"/><stop offset="1" stop-color="#38bdf8"/></linearGradient></defs>
        </svg>
      </div>
      <div class="logo-text">
        <h1>Janus</h1>
        <span class="version-badge">Ecosystem Bridge</span>
      </div>
    </div>

    <!-- Active Connected Device Widget -->
    {#if activeConnectedDevice}
      {@const activeDev = activeConnectedDevice}
      <div class="device-widget connected">
        <div class="dw-row">
          <div class="dw-phone-icon">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none"><rect x="5" y="2" width="14" height="20" rx="3" stroke="currentColor" stroke-width="1.5"/><circle cx="12" cy="18" r="1" fill="currentColor"/></svg>
          </div>
          <div class="dw-info">
            <span class="dw-name">{activeDev.name}</span>
            <span class="dw-status"><span class="dot online"></span> Connected</span>
          </div>
        </div>
        <div class="dw-meta-row">
          {#if deviceSignal !== null}
            <span class="dw-meta-item" title="Signal Level: {deviceSignal} bars">
              <span class="signal-bars">
                <span class="bar {deviceSignal >= 1 ? 'filled' : ''}"></span>
                <span class="bar {deviceSignal >= 2 ? 'filled' : ''}"></span>
                <span class="bar {deviceSignal >= 3 ? 'filled' : ''}"></span>
                <span class="bar {deviceSignal >= 4 ? 'filled' : ''}"></span>
              </span>
              Signal
            </span>
          {:else}
            <span class="dw-meta-item" style="color: var(--text-muted);">
              Signal: --
            </span>
          {/if}
          {#if deviceBattery !== null}
            <span class="dw-meta-item" title="Battery: {deviceBattery}%">
              {deviceBattery}%{#if deviceIsCharging} 🔌{/if}
            </span>
          {:else}
            <span class="dw-meta-item" style="color: var(--text-muted);">
              Battery: --
            </span>
          {/if}
        </div>
        <div class="dw-actions">
          <button class="dw-action-btn {clipboardSyncActive ? 'active' : ''}" onclick={toggleClipboardSync} title={clipboardSyncActive ? "Disable Clipboard Sync" : "Enable Clipboard Sync"}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none"><rect x="8" y="2" width="13" height="17" rx="2" stroke="currentColor" stroke-width="1.5"/><path d="M16 7H5a2 2 0 00-2 2v11a2 2 0 002 2h11" stroke="currentColor" stroke-width="1.5"/></svg>
            Clipboard
          </button>
          <button class="dw-action-btn" onclick={toggleScanning} title="Scan toggle">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none"><path d="M21 12a9 9 0 11-6.22-8.56" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/><path d="M21 3v5h-5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
            {isScanning ? "Stop" : "Scan"}
          </button>
        </div>
      </div>
    {:else}
      <div class="device-widget disconnected">
        <div class="dw-row">
          <div class="dw-phone-icon dim">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none"><rect x="5" y="2" width="14" height="20" rx="3" stroke="currentColor" stroke-width="1.5"/><circle cx="12" cy="18" r="1" fill="currentColor"/></svg>
          </div>
          <div class="dw-info">
            <span class="dw-name dim">No Device</span>
            <span class="dw-status"><span class="dot offline"></span> Disconnected</span>
          </div>
        </div>
        <div style="display: flex; gap: 6px; margin-top: 0.5rem; width: 100%;">
          <button class="btn btn-sm btn-primary" onclick={generatePairingPin} style="flex: 1;">Pair Device</button>
          <button class="btn btn-sm btn-outline" onclick={toggleScanning} style="flex: 1; padding: 0;">
            {isScanning ? "Stop" : "Scan"}
          </button>
        </div>
      </div>
    {/if}

    <!-- Navigation -->
    <!-- Theme Toggle -->
    <div class="theme-toggle-wrap">
      <button class="theme-toggle-btn" onclick={toggleTheme} title="Switch to {currentTheme === 'dark' ? 'Light' : 'Dark'} Theme">
        {#if currentTheme === 'dark'}
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="5" stroke="currentColor" stroke-width="1.5"/><path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
          <span>Light Mode</span>
        {:else}
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
          <span>Dark Mode</span>
        {/if}
      </button>
    </div>

    <nav class="sidebar-nav">
      <button class="nav-item {activeTab === 'overview' ? 'active' : ''}" onclick={() => selectTab('overview')}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><polyline points="9 22 9 12 15 12 15 22" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
        <span>Overview</span>
      </button>
      <button class="nav-item {activeTab === 'mirroring' ? 'active' : ''}" onclick={() => selectTab('mirroring')}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><rect x="2" y="3" width="20" height="14" rx="2" stroke="currentColor" stroke-width="1.5"/><path d="M8 21h8M12 17v4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
        <span>Screen Mirror</span>
      </button>
      <button class="nav-item {activeTab === 'notifications' ? 'active' : ''}" onclick={() => selectTab('notifications')}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M13.73 21a2 2 0 01-3.46 0" stroke="currentColor" stroke-width="1.5"/></svg>
        <span>Notifications</span>
        {#if activeNotifications.length > 0}
          <span class="nav-badge">{activeNotifications.length}</span>
        {/if}
      </button>
      <button class="nav-item {activeTab === 'files' ? 'active' : ''}" onclick={() => selectTab('files')}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z" stroke="currentColor" stroke-width="1.5"/></svg>
        <span>Files</span>
      </button>
      <button class="nav-item {activeTab === 'dialer' ? 'active' : ''}" onclick={() => selectTab('dialer')}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M22 16.92v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6A19.79 19.79 0 012.12 4.18 2 2 0 014.11 2h3a2 2 0 012 1.72c.127.96.361 1.903.7 2.81a2 2 0 01-.45 2.11L8.09 9.91a16 16 0 006 6l1.27-1.27a2 2 0 012.11-.45c.907.339 1.85.573 2.81.7A2 2 0 0122 16.92z" stroke="currentColor" stroke-width="1.5"/></svg>
        <span>Dialer</span>
      </button>
      <button class="nav-item {activeTab === 'history' ? 'active' : ''}" onclick={() => selectTab('history')}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
        <span>History</span>
      </button>
      <button class="nav-item {activeTab === 'messages' ? 'active' : ''}" onclick={() => selectTab('messages')}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
        <span>Messages</span>
      </button>
      <button class="nav-item {activeTab === 'devices' ? 'active' : ''}" onclick={() => selectTab('devices')}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="1.5"/><path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z" stroke="currentColor" stroke-width="1.5"/></svg>
        <span>Settings</span>
      </button>

    </nav>
  </aside>

  <!-- MAIN WORKSPACE -->
  <main class="workspace">
    <!-- ═══════ OVERVIEW / HOMESCREEN TAB ═══════ -->
    {#if activeTab === 'overview'}
      <div class="tab-panel overview-tab">
        <div class="panel-header">
          <div>
            <h2>Device Command Center</h2>
            <p class="panel-desc">Seamlessly orchestrate your phone, screen mirroring, notifications, files, and clipboard.</p>
          </div>
          {#if isDeviceConnected}
            <div class="status-pill online">
              <span class="dot online"></span>
              <span>{activeConnectedDevice?.name || 'Device'} Connected • Local Wi-Fi Bridge</span>
            </div>
          {:else}
            <div class="status-pill offline">
              <span class="dot offline"></span>
              <span>Scanning Network</span>
            </div>
          {/if}
        </div>

        {#if isDeviceConnected && activeConnectedDevice}
          {@const activeDev = activeConnectedDevice}
          
          <!-- Hero Device Banner -->
          <div class="overview-hero-card">
            <div class="hero-device-left">
              <div class="hero-avatar">
                <svg width="36" height="36" viewBox="0 0 24 24" fill="none"><rect x="5" y="2" width="14" height="20" rx="3" stroke="currentColor" stroke-width="1.5"/><circle cx="12" cy="18" r="1" fill="currentColor"/></svg>
              </div>
              <div class="hero-device-details">
                <div class="hero-device-title-row">
                  <h3 class="hero-device-name">{activeDev.name}</h3>
                  <span class="badge-type">{activeDev.device_type.toUpperCase()}</span>
                </div>
                <div class="hero-device-meta">
                  <span class="meta-tag">IP: {activeDev.ip}:{activeDev.port}</span>
                  <span class="meta-tag">FP: {activeDev.fingerprint.substring(0, 10)}...</span>
                </div>
              </div>
            </div>

            <div class="hero-device-telemetry">
              <div class="telemetry-gauge">
                <span class="gauge-label">Battery Level</span>
                <div class="gauge-val-row">
                  <span class="gauge-val">{deviceBattery !== null ? `${deviceBattery}%` : '--'}</span>
                  {#if deviceIsCharging}<span class="charging-icon">🔌 Charging</span>{/if}
                </div>
                <div class="progress-track">
                  <div class="progress-bar-fill" style="width: {deviceBattery || 0}%;"></div>
                </div>
              </div>

              <div class="telemetry-gauge">
                <span class="gauge-label">Cellular / Wi-Fi Signal</span>
                <div class="gauge-val-row">
                  <span class="gauge-val">{deviceSignal !== null ? `${deviceSignal}/4 Bars` : 'Connected'}</span>
                  <span class="signal-tag">LTE / 5G</span>
                </div>
                <div class="signal-meter-bars">
                  <span class="sbar {deviceSignal !== null && deviceSignal >= 1 ? 'active' : ''}"></span>
                  <span class="sbar {deviceSignal !== null && deviceSignal >= 2 ? 'active' : ''}"></span>
                  <span class="sbar {deviceSignal !== null && deviceSignal >= 3 ? 'active' : ''}"></span>
                  <span class="sbar {deviceSignal !== null && deviceSignal >= 4 ? 'active' : ''}"></span>
                </div>
              </div>
            </div>
          </div>

          <!-- Quick Action Widgets Grid -->
          <div class="overview-grid">
            <!-- Screen Mirror Card -->
            <div class="overview-card">
              <div class="card-header-row">
                <div class="card-icon-title">
                  <div class="card-badge-icon purple">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none"><rect x="2" y="3" width="20" height="14" rx="2" stroke="currentColor" stroke-width="1.5"/><path d="M8 21h8M12 17v4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
                  </div>
                  <h4>Screen Mirroring</h4>
                </div>
                <span class="card-status-tag">{isMirroring ? "Streaming" : "Ready"}</span>
              </div>
              <p class="card-desc">Control your phone with mouse and keyboard with low-latency P2P streaming.</p>
              <div class="card-actions-bottom">
                <button class="btn btn-primary btn-sm" onclick={() => { selectTab('mirroring'); if (!isMirroring) startMirroring(); }}>
                  {isMirroring ? "Open Mirror View" : "Launch Screen Mirror"}
                </button>
              </div>
            </div>

            <!-- Universal Clipboard Card -->
            <div class="overview-card">
              <div class="card-header-row">
                <div class="card-icon-title">
                  <div class="card-badge-icon green">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none"><rect x="8" y="2" width="13" height="17" rx="2" stroke="currentColor" stroke-width="1.5"/><path d="M16 7H5a2 2 0 00-2 2v11a2 2 0 002 2h11" stroke="currentColor" stroke-width="1.5"/></svg>
                  </div>
                  <h4>Universal Clipboard</h4>
                </div>
                <span class="card-status-tag active">Live Sync</span>
              </div>
              <div class="clipboard-preview-box">
                {#if lastClipboardEvent}
                  <span class="clip-snippet">{lastClipboardEvent.content.substring(0, 70)}{lastClipboardEvent.content.length > 70 ? '...' : ''}</span>
                  <span class="clip-source">{lastClipboardEvent.source === 'local' ? 'Copied on Mac' : 'Received from Phone'}</span>
                {:else}
                  <span class="clip-snippet empty">Clipboard stream ready. Copy text on either device to sync instantly.</span>
                {/if}
              </div>
              <div class="card-actions-bottom">
                <button class="btn btn-outline btn-sm" onclick={toggleClipboardSync}>
                  {clipboardSyncActive ? "Sync Active" : "Toggle Sync"}
                </button>
              </div>
            </div>

            <!-- Instant File Dropzone Card -->
            <div class="overview-card">
              <div class="card-header-row">
                <div class="card-icon-title">
                  <div class="card-badge-icon blue">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z" stroke="currentColor" stroke-width="1.5"/></svg>
                  </div>
                  <h4>Fast File Drop</h4>
                </div>
                <span class="card-status-tag">Drag & Drop</span>
              </div>
              <div
                class="overview-dropzone {isDragOver ? 'drag-over' : ''}"
                ondragover={handleDragOver}
                ondragleave={handleDragLeave}
                ondrop={(e) => handleDrop(e, activeDev)}
                role="region"
                aria-label="Dropzone"
              >
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/><polyline points="17 8 12 3 7 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/><line x1="12" y1="3" x2="12" y2="15" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
                <span>Drop files here to send to {activeDev.name}</span>
              </div>
              <div class="card-actions-bottom">
                <button class="btn btn-outline btn-sm" onclick={() => triggerFileSelect(activeDev)}>Choose File...</button>
              </div>
            </div>

            <!-- Notifications Feed Card -->
            <div class="overview-card">
              <div class="card-header-row">
                <div class="card-icon-title">
                  <div class="card-badge-icon orange">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none"><path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/><path d="M13.73 21a2 2 0 01-3.46 0" stroke="currentColor" stroke-width="1.5"/></svg>
                  </div>
                  <h4>Notifications</h4>
                </div>
                <span class="card-status-tag">{activeNotifications.length} active</span>
              </div>
              <div class="overview-notif-list">
                {#if activeNotifications.length === 0}
                  <p class="empty-notif-hint">No unread notifications right now.</p>
                {:else}
                  {#each activeNotifications.slice(0, 2) as notif}
                    <div class="mini-notif-item">
                      <span class="mini-notif-app">{notif.app_name}</span>
                      <span class="mini-notif-title">{notif.title}</span>
                    </div>
                  {/each}
                {/if}
              </div>
              <div class="card-actions-bottom">
                <button class="btn btn-outline btn-sm" onclick={() => selectTab('notifications')}>View Notifications</button>
              </div>
            </div>
          </div>

        {:else}
          <!-- Disconnected Homescreen State: Interactive Pairing Hub -->
          <div class="overview-pairing-hub">
            <div class="pairing-hero-header">
              <div class="radar-pulse-ring">
                <div class="pulse-circle c1"></div>
                <div class="pulse-circle c2"></div>
                <div class="pulse-circle c3"></div>
                <div class="pulse-core">
                  <svg width="32" height="32" viewBox="0 0 24 24" fill="none"><rect x="5" y="2" width="14" height="20" rx="3" stroke="currentColor" stroke-width="1.5"/><circle cx="12" cy="18" r="1" fill="currentColor"/></svg>
                </div>
              </div>
              <h3>Pair Your Phone</h3>
              <p class="pairing-subtitle">Open the Janus mobile app on your Android device and scan the QR code or enter the pairing PIN.</p>
            </div>

            <div class="pairing-cards-container">
              <!-- QR Code Card -->
              <div class="pairing-box-card">
                <span class="pairing-box-title">SCAN QR CODE</span>
                <div class="qr-canvas-wrapper">
                  <canvas use:qrAction={200} bind:this={homeQrCanvas} style="width: 180px; height: 180px; display: block; border-radius: 8px;"></canvas>
                </div>
                <span class="qr-hint">Scan with the Janus mobile app camera</span>
              </div>

              <!-- PIN Entry Card -->
              <div class="pairing-box-card">
                <span class="pairing-box-title">MANUAL 6-DIGIT PIN</span>
                <div class="pin-large-badge">{pairingPin}</div>
                <button class="btn btn-sm btn-outline" onclick={() => { navigator.clipboard.writeText(pairingPin); showToast("PIN copied to clipboard!", "success"); }}>
                  📋 Copy PIN
                </button>
                <span class="qr-hint" style="margin-top: 8px;">Enter this PIN on your phone to connect directly</span>
              </div>
            </div>

            <!-- Steps Bar -->
            <div class="pairing-steps-row">
              <div class="step-item">
                <span class="step-num">1</span>
                <span>Connect both devices to same Wi-Fi</span>
              </div>
              <div class="step-arrow">→</div>
              <div class="step-item">
                <span class="step-num">2</span>
                <span>Open Janus app on your phone</span>
              </div>
              <div class="step-arrow">→</div>
              <div class="step-item">
                <span class="step-num">3</span>
                <span>Scan QR or enter PIN to link</span>
              </div>
            </div>
          </div>
        {/if}
      </div>

    <!-- ═══════ MIRRORING TAB ═══════ -->
    {:else if activeTab === 'mirroring'}
      <div class="tab-panel mirroring-tab">
        <div class="panel-header">
          <h2>Screen Mirror</h2>
          <p class="panel-desc">Operate your mobile device in real-time. Clicks and drags on the screen are sent directly to your phone.</p>
        </div>
        
        <div class="mirror-workspace">
          {#if isMirroring}
            <div class="phone-chassis">
              <!-- Volume Keys -->
              <div class="chassis-vol-up"></div>
              <div class="chassis-vol-down"></div>
              <!-- Power Key -->
              <div class="chassis-power"></div>
              
              <div class="chassis-inner">
                <!-- Punch-hole camera -->
                <div class="punch-hole"></div>
                <!-- Status Bar Mockup -->
                <div class="phone-status-bar">
                  <span class="status-time">{currentLocalTime}</span>
                  <div class="status-icons">
                    {#if deviceSignal !== null}
                      <span class="signal-bars">
                        <span class="bar {deviceSignal >= 1 ? 'filled' : ''}"></span>
                        <span class="bar {deviceSignal >= 2 ? 'filled' : ''}"></span>
                        <span class="bar {deviceSignal >= 3 ? 'filled' : ''}"></span>
                        <span class="bar {deviceSignal >= 4 ? 'filled' : ''}"></span>
                      </span>
                      <span class="status-network" style="margin-right: 4px;">LTE</span>
                    {:else}
                      <span class="status-network" style="margin-right: 4px; color: var(--text-muted); font-size: 10px;">Offline</span>
                    {/if}
                    <svg class="status-icon" width="12" height="12" viewBox="0 0 24 24" fill="none"><rect x="2" y="7" width="16" height="10" rx="2" stroke="currentColor" stroke-width="1.8"/><path d="M22 11v2M6 10h8v4H6z" fill="currentColor"/></svg>
                    <span class="status-battery">{deviceBattery !== null ? `${deviceBattery}%${deviceIsCharging ? '🔌' : ''}` : 'Not Connected'}</span>
                  </div>
                </div>
                <!-- Screen -->
                <div class="phone-viewport">
                  <canvas
                    id="screencast-canvas"
                    class="mirror-canvas"
                    onpointerdown={handlePointerDown}
                    onpointerup={handlePointerUp}
                    style="touch-action: none;"
                  ></canvas>
                </div>
                <!-- Gesture bar -->
                <div class="gesture-bar">
                  <div class="gesture-pill"></div>
                </div>
              </div>
              
              <!-- Navigation Soft Keys (outside bezel) -->
              <div class="nav-keys">
                <button class="nav-key" onclick={() => sendRemoteKey("back")} title="Back">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none"><path d="M19 12H5M12 19l-7-7 7-7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
                </button>
                <button class="nav-key home-key" onclick={() => sendRemoteKey("home")} title="Home">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2"/></svg>
                </button>
              </div>
            </div>
            
            <button class="btn btn-danger-subtle stop-btn" onclick={stopMirroring}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none"><rect x="6" y="6" width="12" height="12" rx="1" stroke="currentColor" stroke-width="2"/></svg>
              Stop Mirroring
            </button>
          {:else}
            {#if isDeviceConnected}
              <div class="empty-state">
                <div class="empty-icon">
                  <svg width="56" height="56" viewBox="0 0 24 24" fill="none"><rect x="2" y="3" width="20" height="14" rx="2" stroke="currentColor" stroke-width="1.2"/><path d="M8 21h8M12 17v4" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/></svg>
                </div>
                <h3>Ready to Mirror</h3>
                <p>Cast your Android screen to view and control it directly from macOS.</p>
                <button class="btn btn-primary" onclick={startMirroring}>
                  Start Mirroring
                </button>
              </div>
            {:else}
              <div class="empty-state pairing-flow" style="max-width: 500px; padding: 2rem; display: flex; flex-direction: column; align-items: center; text-align: center;">
                <div class="empty-icon" style="color: var(--text-primary); margin-bottom: 0.5rem;">
                  <svg width="48" height="48" viewBox="0 0 24 24" fill="none"><rect x="5" y="2" width="14" height="20" rx="3" stroke="currentColor" stroke-width="1.5"/><circle cx="12" cy="18" r="1" fill="currentColor"/></svg>
                </div>
                <h3>Pair Your Device</h3>
                <p>Scan this QR code from the Janus mobile app or enter the PIN manually to connect instantly:</p>
                
                <div style="display: flex; flex-direction: column; align-items: center; gap: 16px; margin: 20px 0; width: 100%;">
                  <!-- QR Code Canvas -->
                  <div style="background: white; padding: 12px; border-radius: 12px; display: inline-block; box-shadow: 0 4px 12px rgba(0,0,0,0.15);">
                    <canvas use:qrAction={180} bind:this={homeQrCanvas} style="width: 180px; height: 180px; display: block;"></canvas>
                  </div>
                  
                  <div style="margin: 4px 0; color: rgba(255,255,255,0.4); font-size: 11px; font-weight: bold; letter-spacing: 1px;">— OR ENTER PIN MANUALLY —</div>
                  <div class="pin-display" style="letter-spacing: 4px; font-size: 32px; font-weight: bold; background: rgba(255,255,255,0.05); padding: 8px 24px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.1); color: var(--text-primary); margin: 0;">{pairingPin}</div>
                </div>
                
                <p class="hint-text" style="font-size: 0.8rem; color: var(--text-muted);">Make sure both your Mac and phone are on the same local Wi-Fi network.</p>
              </div>
            {/if}
          {/if}
        </div>
      </div>
      
    <!-- ═══════ NOTIFICATIONS TAB ═══════ -->
    {:else if activeTab === 'notifications'}
      <div class="tab-panel notifications-tab">
        <div class="panel-header">
          <h2>Notifications</h2>
          <p class="panel-desc">Mirrored phone notifications. Reply to messages inline or dismiss them instantly.</p>
        </div>
        
        <div class="notifications-workspace">
          {#if activeNotifications.length === 0}
            <div class="empty-state">
              <div class="empty-icon">
                <svg width="56" height="56" viewBox="0 0 24 24" fill="none"><path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/><path d="M13.73 21a2 2 0 01-3.46 0" stroke="currentColor" stroke-width="1.2"/></svg>
              </div>
              <h3>No Notifications</h3>
              <p>Incoming notifications from your phone will appear here in real-time.</p>
            </div>
          {:else}
            <div class="notif-list">
              {#each activeNotifications as notif (notif.notification_id)}
                <div class="notif-card">
                  <div class="notif-header">
                    {#if notif.app_icon}
                      <img class="notif-app-icon" src="data:image/png;base64,{notif.app_icon}" alt={notif.app_name} />
                    {:else}
                      <div class="notif-app-icon-placeholder">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none"><rect x="5" y="2" width="14" height="20" rx="3" stroke="currentColor" stroke-width="1.5"/></svg>
                      </div>
                    {/if}
                    <span class="notif-app-name">{notif.app_name}</span>
                    <span class="notif-time">{new Date(notif.timestamp || Date.now()).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})}</span>
                  </div>
                  <div class="notif-body">
                    <h4>{notif.title}</h4>
                    <p>{notif.text}</p>
                  </div>
                  <div class="notif-actions">
                    {#if notif.is_replyable}
                      <div class="reply-row">
                        <input
                          type="text"
                          placeholder="Type a reply..."
                          bind:value={replyInputs[notif.notification_id]}
                          onkeydown={(e) => { if (e.key === 'Enter') replyToNotification(notif.notification_id, replyInputs[notif.notification_id] || ''); }}
                        />
                        <button class="btn btn-sm btn-primary" onclick={() => replyToNotification(notif.notification_id, replyInputs[notif.notification_id] || '')}>
                          Reply
                        </button>
                      </div>
                    {/if}
                    <button class="btn btn-sm btn-ghost-danger" onclick={() => dismissNotification(notif.notification_id)}>
                      Dismiss
                    </button>
                  </div>
                </div>
              {/each}
            </div>
          {/if}
        </div>
      </div>
      
    <!-- ═══════ FILES TAB ═══════ -->
    {:else if activeTab === 'files'}
      <div class="tab-panel files-tab">
        <div class="panel-header">
          <h2>File Sharing</h2>
          <p class="panel-desc">Drag &amp; drop files onto device cards or use the file picker to transfer files instantly over Wi-Fi.</p>
        </div>
        
        <div class="files-workspace">
          <div class="dropzone-grid">
            {#each availableFileDropDevices as device}
              <div
                class="dropzone {isDragOver && dragTargetDevice?.fingerprint === device.fingerprint ? 'drag-active' : ''}"
                ondragover={(e) => { handleDragOver(e); dragTargetDevice = device; }}
                ondragleave={handleDragLeave}
                ondrop={(e) => handleDrop(e, device)}
                role="region"
                aria-label="File drop area for {device.name}"
              >
                <div class="dropzone-icon">
                  <svg width="40" height="40" viewBox="0 0 24 24" fill="none"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><polyline points="17 8 12 3 7 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><line x1="12" y1="3" x2="12" y2="15" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
                </div>
                <h3>{device.name}</h3>
                <p>Drag and drop files here to send</p>
                <button class="btn btn-primary btn-sm" onclick={() => triggerFileSelect(device)} style="margin-top:1rem">
                  Choose File...
                </button>
              </div>
            {/each}
            
            {#if availableFileDropDevices.length === 0}
              <div class="empty-state" style="width:100%">
                <div class="empty-icon">
                  <svg width="56" height="56" viewBox="0 0 24 24" fill="none"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z" stroke="currentColor" stroke-width="1.2"/></svg>
                </div>
                <h3>Device Offline</h3>
                <p>Connect your phone on the same Wi-Fi network to start sending files.</p>
              </div>
            {/if}
          </div>
          
          {#if Object.keys(activeTransfers).length > 0}
            <div class="transfers-section">
              <h3>Active Transfers</h3>
              <div class="transfer-list">
                {#each Object.values(activeTransfers) as trans}
                  <div class="transfer-item">
                    <div class="transfer-info">
                      <span class="transfer-name">{trans.name}</span>
                      <span class="transfer-pct">{Math.round((trans.bytes_received / trans.total_bytes) * 100)}%</span>
                    </div>
                    <div class="progress-track">
                      <div class="progress-fill" style="width: {(trans.bytes_received / trans.total_bytes) * 100}%"></div>
                    </div>
                    <span class="transfer-bytes">
                      {Math.round(trans.bytes_received / 1024 / 1024)} MB / {Math.round(trans.total_bytes / 1024 / 1024)} MB
                    </span>
                  </div>
                {/each}
              </div>
            </div>
          {/if}
        </div>
      </div>
      
    <!-- ═══════ DIALER TAB ═══════ -->
    {:else if activeTab === 'dialer'}
      <div class="tab-panel dialer-tab">
        <div class="panel-header">
          <h2>Phone Dialer</h2>
          <p class="panel-desc">Place outgoing calls from your computer. Call audio bridges directly to your Mac microphone and speakers.</p>
        </div>
        
        <div class="dialer-workspace">
          <div class="dialer-widget">
            <input
              type="text"
              placeholder="Enter phone number..."
              bind:value={dialerNumber}
              class="dialer-input"
            />
            <div class="dial-grid">
              {#each [
                {key: '1', sub: ''},
                {key: '2', sub: 'ABC'},
                {key: '3', sub: 'DEF'},
                {key: '4', sub: 'GHI'},
                {key: '5', sub: 'JKL'},
                {key: '6', sub: 'MNO'},
                {key: '7', sub: 'PQRS'},
                {key: '8', sub: 'TUV'},
                {key: '9', sub: 'WXYZ'},
                {key: '*', sub: ''},
                {key: '0', sub: '+'},
                {key: '#', sub: ''}
              ] as item}
                <button class="dial-btn" onclick={() => dialerNumber += item.key}>
                  <span class="dial-num">{item.key}</span>
                  {#if item.sub}
                    <span class="dial-sub">{item.sub}</span>
                  {/if}
                </button>
              {/each}
            </div>
            <div class="dialer-actions">
              <button class="call-btn" onclick={makePhoneCall} disabled={!dialerNumber.trim() || !isDeviceConnected}>
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none"><path d="M22 16.92v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6A19.79 19.79 0 012.12 4.18 2 2 0 014.11 2h3a2 2 0 012 1.72c.127.96.361 1.903.7 2.81a2 2 0 01-.45 2.11L8.09 9.91a16 16 0 006 6l1.27-1.27a2 2 0 012.11-.45c.907.339 1.85.573 2.81.7A2 2 0 0122 16.92z" stroke="currentColor" stroke-width="1.5"/></svg>
              </button>
              <button class="btn btn-sm btn-outline" onclick={() => dialerNumber = ""}>Clear</button>
            </div>
          </div>

          <div class="audio-route-selector">
            <label for="audio-output-select">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none"><path d="M12 6L8 10H5v4h3l4 4V6z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/><path d="M15.54 8.46a5 5 0 010 7.07M19.07 4.93a10 10 0 010 14.14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
              Audio Output:
            </label>
            <select id="audio-output-select" value={selectedAudioOutput} onchange={(e) => changeAudioOutput(e.currentTarget.value)}>
              {#each audioOutputs as device}
                <option value={device.deviceId}>{device.label || `Speaker (${device.deviceId.substring(0,5)}...)`}</option>
              {/each}
              {#if audioOutputs.length === 0}
                <option value="">Default System Speaker</option>
              {/if}
            </select>
          </div>
        </div>
      </div>
      
    <!-- ═══════ HISTORY TAB ═══════ -->
    {:else if activeTab === 'history'}
      <div class="tab-panel history-tab">
        <div class="panel-header">
          <h2>Call History</h2>
          <p class="panel-desc">Recent incoming, outgoing, and missed calls synced from your device.</p>
        </div>
        
        <div class="history-workspace">
          {#if !isDeviceConnected}
            <div class="empty-state">
              <div class="empty-icon">
                <svg width="56" height="56" viewBox="0 0 24 24" fill="none"><path d="M22 16.92v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6A19.79 19.79 0 012.12 4.18" stroke="currentColor" stroke-width="1.2"/></svg>
              </div>
              <h3>Device Offline</h3>
              <p>Connect your phone to view and sync call history.</p>
            </div>
          {:else if callHistory.length === 0}
            <div class="empty-state">
              <div class="empty-icon">
                <svg width="56" height="56" viewBox="0 0 24 24" fill="none"><path d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" stroke="currentColor" stroke-width="1.2"/></svg>
              </div>
              <h3>No Call Logs</h3>
              <p>No call history has been synchronized yet.</p>
              <button class="btn btn-primary btn-sm" onclick={() => invoke("sync_calls")}>
                Sync Call History
              </button>
            </div>
          {:else}
            <div class="history-list-container">
              <button class="btn btn-outline btn-sm sync-btn-float" onclick={() => invoke("sync_calls")}>
                Refresh Logs
              </button>
              <div class="history-list">
                {#each callHistory as log}
                  <div class="history-item">
                    <div class="history-type-icon {log.type}">
                      {#if log.type === 'incoming'}
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M22 16.92v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6A19.79 19.79 0 012.12 4.18" stroke="#10b981" stroke-width="1.8"/></svg>
                      {:else if log.type === 'outgoing'}
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M22 16.92v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6A19.79 19.79 0 012.12 4.18" stroke="#818cf8" stroke-width="1.8"/></svg>
                      {:else}
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M22 16.92v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6A19.79 19.79 0 012.12 4.18" stroke="#ef4444" stroke-width="1.8"/><path d="M12 2v4M12 10v2" stroke="#ef4444" stroke-width="1.8"/></svg>
                      {/if}
                    </div>
                    <div class="history-info">
                      <span class="history-name">{log.name || log.number}</span>
                      <span class="history-number">{log.number}</span>
                    </div>
                    <div class="history-meta">
                      <span class="history-time">{new Date(log.date).toLocaleString([], {month: 'short', day: 'numeric', hour: '2-digit', minute:'2-digit'})}</span>
                      <span class="history-duration">{formatCallDuration(log.duration)}</span>
                    </div>
                    <button class="btn btn-sm btn-ghost call-back-btn" onclick={() => { dialerNumber = log.number; selectTab('dialer'); makePhoneCall(); }} title="Call back">
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none"><path d="M22 16.92v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6A19.79 19.79 0 012.12 4.18 2 2 0 014.11 2h3a2 2 0 012 1.72c.127.96.361 1.903.7 2.81a2 2 0 01-.45 2.11L8.09 9.91a16 16 0 006 6l1.27-1.27a2 2 0 012.11-.45c.907.339 1.85.573 2.81.7A2 2 0 0122 16.92z" stroke="currentColor" stroke-width="1.5"/></svg>
                    </button>
                  </div>
                {/each}
              </div>
            </div>
          {/if}
        </div>
      </div>
      
    <!-- ═══════ MESSAGES TAB ═══════ -->
    {:else if activeTab === 'messages'}
      <div class="tab-panel messages-tab">
        <div class="panel-header">
          <h2>Messages</h2>
          <p class="panel-desc">View text message conversations synced dynamically from your Android device.</p>
        </div>
        
        <div class="messages-workspace">
          {#if !isDeviceConnected}
            <div class="empty-state">
              <div class="empty-icon">
                <svg width="56" height="56" viewBox="0 0 24 24" fill="none"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" stroke="currentColor" stroke-width="1.2"/></svg>
              </div>
              <h3>Device Offline</h3>
              <p>Connect your phone to view and sync text messages.</p>
            </div>
          {:else if smsMessages.length === 0}
            <div class="empty-state">
              <div class="empty-icon">
                <svg width="56" height="56" viewBox="0 0 24 24" fill="none"><path d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8" stroke="currentColor" stroke-width="1.2"/></svg>
              </div>
              <h3>No Messages</h3>
              <p>No text messages have been synchronized yet.</p>
              <button class="btn btn-primary btn-sm" onclick={() => invoke("sync_sms")}>
                Sync Messages
              </button>
            </div>
          {:else}
            <div class="sms-workspace-layout">
              <!-- Thread List -->
              <div class="sms-threads-sidebar">
                <button class="btn btn-outline btn-xs sync-btn" onclick={() => invoke("sync_sms")}>
                  Refresh SMS
                </button>
                <div class="threads-list">
                  {#each smsThreads as thread}
                    <button class="thread-item {activeSmsThread === thread.address ? 'active' : ''}" onclick={() => activeSmsThread = thread.address}>
                      <div class="thread-item-header">
                        <span class="thread-name">{thread.name}</span>
                        <span class="thread-date">{new Date(thread.lastDate).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})}</span>
                      </div>
                      <span class="thread-preview">{thread.lastMessage}</span>
                    </button>
                  {/each}
                </div>
              </div>

              <!-- Chat Thread View -->
              <div class="sms-thread-view">
                {#if activeSmsThread}
                  {@const currentThread = smsThreads.find(t => t.address === activeSmsThread)}
                  {#if currentThread}
                    <div class="thread-view-header">
                      <div class="thread-view-contact">
                        <span class="view-contact-name">{currentThread.name}</span>
                        <span class="view-contact-address">{currentThread.address}</span>
                      </div>
                      <button class="btn btn-sm btn-ghost" onclick={() => { dialerNumber = currentThread.address; selectTab('dialer'); }} title="Call Contact">
                        Call
                      </button>
                    </div>
                    <div class="thread-messages-list">
                      {#each currentThread.messages as msg}
                        <div class="sms-bubble-wrapper {msg.type}">
                          <div class="sms-bubble">
                            <p class="sms-body">{msg.body}</p>
                            <span class="sms-time">{new Date(msg.date).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})}</span>
                          </div>
                        </div>
                      {/each}
                    </div>
                    <div class="thread-reply-box">
                      <input type="text" placeholder="SMS sending is read-only in this version..." disabled class="sms-reply-input" />
                    </div>
                  {/if}
                {:else}
                  <div class="thread-view-empty">
                    <svg width="40" height="40" viewBox="0 0 24 24" fill="none"><path d="M8 12h.01M12 12h.01M16 12h.01" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/><path d="M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" stroke="currentColor" stroke-width="1.5"/></svg>
                    <p>Select a conversation thread to read messages</p>
                  </div>
                {/if}
              </div>
            </div>
          {/if}
        </div>
      </div>
      
    <!-- ═══════ DEVICES & SETTINGS TAB ═══════ -->
    {:else if activeTab === 'devices'}
      <div class="tab-panel settings-tab">
        <div class="panel-header">
          <h2>Settings</h2>
          <p class="panel-desc">Manage paired devices, pair new ones, and view your local server node details.</p>
        </div>
        
        <div class="settings-workspace">
          <!-- Local Server Card -->
          <div class="settings-section">
            <h3 class="section-title">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none"><rect x="2" y="3" width="20" height="14" rx="2" stroke="currentColor" stroke-width="1.5"/><path d="M8 21h8M12 17v4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
              Local Server Node
            </h3>
            {#if localIdentity}
              <div class="detail-grid">
                <div class="detail-row"><span class="detail-label">Server Name</span><span class="detail-value">{localIdentity.name}</span></div>
                <div class="detail-row"><span class="detail-label">TLS Fingerprint</span><span class="detail-value mono">{localIdentity.fingerprint}</span></div>
                <div class="detail-row"><span class="detail-label">Local Address</span><span class="detail-value">{localIdentity.ip}:53317</span></div>
              </div>
              <div style="margin-top: 12px; display: flex; justify-content: flex-end;">
                <button class="btn btn-sm btn-primary" onclick={generatePairingPin}>Pair New Device</button>
              </div>
            {/if}
          </div>

          <!-- Paired Devices -->
          <div class="settings-section">
            <h3 class="section-title">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none"><path d="M9 11l3 3L22 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11" stroke="currentColor" stroke-width="1.5"/></svg>
              Paired Devices
            </h3>
            <div class="device-list">
              {#each pairedDevicesList as device}
                <div class="device-row {device.online ? 'online' : 'offline'}">
                  <div class="device-row-icon">
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none"><rect x="5" y="2" width="14" height="20" rx="3" stroke="currentColor" stroke-width="1.5"/><circle cx="12" cy="18" r="1" fill="currentColor"/></svg>
                  </div>
                  <div class="device-row-info">
                    <span class="device-row-name">{device.name}</span>
                    <span class="device-row-fp">FP: {device.fingerprint.substring(0, 16)}...</span>
                    <span class="device-row-status {device.online ? 'on' : 'off'}">
                      <span class="dot {device.online ? 'online' : 'offline'}"></span>
                      {device.online ? `Online (${device.ip}:${device.port})` : "Offline"}
                    </span>
                  </div>
                  <div class="device-row-actions">
                    {#if device.online}
                      <button class="btn btn-sm btn-outline" onclick={() => triggerFileSelect(device)}>Send File</button>
                    {/if}
                    <button class="btn btn-sm btn-ghost-danger" onclick={() => unpairDevice(device)}>Forget</button>
                  </div>
                </div>
              {/each}
              
              {#if pairedDevicesList.length === 0}
                <p class="hint-text">No paired devices found.</p>
              {/if}
            </div>
          </div>

          <!-- Available to Pair -->
          <div class="settings-section">
            <h3 class="section-title">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none"><circle cx="11" cy="11" r="8" stroke="currentColor" stroke-width="1.5"/><path d="M21 21l-4.35-4.35" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
              Available to Pair
            </h3>
            <div class="device-list">
              {#each unpairedDiscovered as device}
                <div class="device-row">
                  <div class="device-row-icon">
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none"><rect x="5" y="2" width="14" height="20" rx="3" stroke="currentColor" stroke-width="1.5"/><circle cx="12" cy="18" r="1" fill="currentColor"/></svg>
                  </div>
                  <div class="device-row-info">
                    <span class="device-row-name">{device.name}</span>
                    <span class="device-row-fp">FP: {device.fingerprint.substring(0, 16)}...</span>
                    <span class="device-row-status">{device.ip}:{device.port}</span>
                  </div>
                  <div class="device-row-actions">
                    <button class="btn btn-sm btn-primary" onclick={generatePairingPin}>Pair</button>
                  </div>
                </div>
              {/each}
              
              {#if unpairedDiscovered.length === 0}
                <p class="hint-text">Scanning local network for new Janus nodes...</p>
              {/if}
            </div>
          </div>
        </div>
      </div>
    {/if}
  </main>

  <!-- Pairing Setup Modal -->
  {#if showPairingModal}
    <div class="modal-overlay" onclick={() => showPairingModal = false}>
      <div class="modal-card" onclick={(e) => e.stopPropagation()}>
        <div class="modal-top">
          <h2>Pairing Setup</h2>
          <button class="modal-close" onclick={() => showPairingModal = false}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M18 6L6 18M6 6l12 12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
          </button>
        </div>
        <div class="modal-body" style="display: flex; flex-direction: column; align-items: center; gap: 16px; text-align: center;">
          <p>Scan this QR code from the Janus mobile app to connect instantly:</p>
          <div style="background: white; padding: 12px; border-radius: 12px; display: inline-block; box-shadow: 0 4px 12px rgba(0,0,0,0.15);">
            <canvas use:qrAction={200} bind:this={qrCanvas} style="width: 200px; height: 200px; display: block;"></canvas>
          </div>
          <div style="margin: 4px 0; color: rgba(255,255,255,0.4); font-size: 11px; font-weight: bold; letter-spacing: 1px;">— OR ENTER PIN MANUALLY —</div>
          <div class="pin-display" style="letter-spacing: 4px; font-size: 32px; font-weight: bold; background: rgba(255,255,255,0.05); padding: 8px 24px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.1); color: var(--text-primary);">{pairingPin}</div>
          <p class="hint-text">Make sure both devices are on the same local Wi-Fi network.</p>
        </div>
      </div>
    </div>
  {/if}

  <!-- Incoming Call Overlay -->
  {#if callState === "ringing" && activeCall}
    <div class="modal-overlay">
      <div class="modal-card call-card">
        <div class="call-avatar">
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none"><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2" stroke="currentColor" stroke-width="1.5"/><circle cx="12" cy="7" r="4" stroke="currentColor" stroke-width="1.5"/></svg>
        </div>
        <h2>Incoming Call</h2>
        <div class="call-details">
          <span class="caller-name">{activeCall.callerName}</span>
          <span class="caller-number">{activeCall.phoneNumber}</span>
        </div>
        <div class="call-controls">
          <button class="call-control-btn answer" onclick={answerCall}>
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none"><path d="M22 16.92v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6A19.79 19.79 0 012.12 4.18 2 2 0 014.11 2h3a2 2 0 012 1.72c.127.96.361 1.903.7 2.81a2 2 0 01-.45 2.11L8.09 9.91a16 16 0 006 6l1.27-1.27a2 2 0 012.11-.45c.907.339 1.85.573 2.81.7A2 2 0 0122 16.92z" stroke="currentColor" stroke-width="1.5"/></svg>
          </button>
          <button class="call-control-btn decline" onclick={declineCall}>
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none"><path d="M22 16.92v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6A19.79 19.79 0 012.12 4.18 2 2 0 014.11 2h3a2 2 0 012 1.72c.127.96.361 1.903.7 2.81a2 2 0 01-.45 2.11L8.09 9.91a16 16 0 006 6l1.27-1.27a2 2 0 012.11-.45c.907.339 1.85.573 2.81.7A2 2 0 0122 16.92z" stroke="currentColor" stroke-width="1.5"/></svg>
          </button>
        </div>
      </div>
    </div>
  {/if}

  <!-- Active Call Banner -->
  {#if callState === "offhook"}
    <div class="call-banner">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none"><path d="M22 16.92v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6A19.79 19.79 0 012.12 4.18 2 2 0 014.11 2h3a2 2 0 012 1.72c.127.96.361 1.903.7 2.81a2 2 0 01-.45 2.11L8.09 9.91a16 16 0 006 6l1.27-1.27a2 2 0 012.11-.45c.907.339 1.85.573 2.81.7A2 2 0 0122 16.92z" stroke="currentColor" stroke-width="1.5"/></svg>
      <span>Active Call — {formatCallDuration(callDuration)}</span>
      <button class="btn btn-sm btn-ghost-danger" onclick={declineCall}>End Call</button>
    </div>
  {/if}

  </div>
{/if}

<style>
  /* ════════════════════════════════════════════════
     DESIGN TOKENS — LIGHT THEME (Default, Crisp Slate & Blue)
     ════════════════════════════════════════════════ */
  :root, :root[data-theme="light"] {
    --bg-base: #f8fafc;
    --bg-surface: #ffffff;
    --bg-elevated: #ffffff;
    --border-subtle: #e2e8f0;
    --border-accent: #cbd5e1;
    --text-primary: #0f172a;
    --text-secondary: #475569;
    --text-muted: #64748b;
    --accent: #2563eb;
    --accent-bright: #1d4ed8;
    --accent-dim: rgba(37, 99, 235, 0.08);
    --success: #059669;
    --success-dim: rgba(5, 150, 105, 0.08);
    --error: #dc2626;
    --error-dim: rgba(220, 38, 38, 0.06);
    --radius-sm: 8px;
    --radius-md: 12px;
    --radius-lg: 16px;
    --radius-xl: 20px;
    --font: "Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    --shadow-card: 0 1px 3px rgba(0,0,0,0.05), 0 1px 2px rgba(0,0,0,0.03);
    --shadow-elevated: 0 10px 25px -5px rgba(0,0,0,0.08), 0 8px 10px -6px rgba(0,0,0,0.04);
    --transition: 0.2s ease;
    --hover-bg: #f1f5f9;
    --hover-bg-strong: #e2e8f0;
    --scrollbar-track: #f1f5f9;
    --scrollbar-thumb: #cbd5e1;
    --card-bg: #ffffff;
    --input-bg: #f8fafc;
    --input-border: #cbd5e1;
    --sidebar-bg: #ffffff;
    --hero-card-bg: linear-gradient(135deg, #f0f9ff 0%, #e0f2fe 100%);
    --badge-bg: #f1f5f9;
    --meta-bg: #f1f5f9;
  }

  /* ════════════════════════════════════════════════
     DESIGN TOKENS — TRUE DEEP BLACK (Dark Theme)
     ════════════════════════════════════════════════ */
  :root[data-theme="dark"] {
    --bg-base: #09090b;
    --bg-surface: #18181b;
    --bg-elevated: #27272a;
    --border-subtle: #27272a;
    --border-accent: #3f3f46;
    --text-primary: #f8fafc;
    --text-secondary: #94a3b8;
    --text-muted: #64748b;
    --accent: #3b82f6;
    --accent-bright: #60a5fa;
    --accent-dim: rgba(59, 130, 246, 0.15);
    --success: #10b981;
    --success-dim: rgba(16, 185, 129, 0.15);
    --error: #ef4444;
    --error-dim: rgba(239, 68, 68, 0.12);
    --shadow-card: 0 4px 20px rgba(0,0,0,0.5);
    --shadow-elevated: 0 12px 40px rgba(0,0,0,0.8);
    --hover-bg: rgba(255,255,255,0.05);
    --hover-bg-strong: rgba(255,255,255,0.08);
    --scrollbar-track: #18181b;
    --scrollbar-thumb: #3f3f46;
    --card-bg: #18181b;
    --input-bg: #27272a;
    --input-border: #3f3f46;
    --sidebar-bg: #121215;
    --hero-card-bg: linear-gradient(135deg, #1e293b 0%, #0f172a 100%);
    --badge-bg: rgba(255, 255, 255, 0.06);
    --meta-bg: rgba(255,255,255,0.05);
  }

  :global(body) {
    margin: 0;
    padding: 0;
    background: var(--bg-base);
    color: var(--text-primary);
    font-family: var(--font);
    min-height: 100vh;
    overflow: hidden;
    -webkit-font-smoothing: antialiased;
    -moz-osx-font-smoothing: grayscale;
  }

  :global(::-webkit-scrollbar) {
    width: 6px;
  }
  :global(::-webkit-scrollbar-track) {
    background: transparent;
  }
  :global(::-webkit-scrollbar-thumb) {
    background: rgba(255,255,255,0.08);
    border-radius: 3px;
  }
  :global(::-webkit-scrollbar-thumb:hover) {
    background: rgba(255,255,255,0.15);
  }

  /* ════════════════════════════════════════════════
     LAYOUT
     ════════════════════════════════════════════════ */
  .janus-layout {
    display: flex;
    height: 100vh;
    width: 100vw;
    overflow: hidden;
  }

  /* ════════════════════════════════════════════════
     TOASTS
     ════════════════════════════════════════════════ */
  .toast-container {
    position: fixed;
    top: 1rem;
    right: 1rem;
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    z-index: 9999;
    pointer-events: none;
  }

  .toast {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    padding: 0.6rem 1rem;
    border-radius: var(--radius-md);
    background: var(--sidebar-bg);
    border: 1px solid var(--border-subtle);
    backdrop-filter: blur(12px);
    box-shadow: var(--shadow-elevated);
    animation: toastIn 0.3s ease forwards;
    font-size: 0.82rem;
    pointer-events: auto;
    max-width: 340px;
  }
  .toast-success { border-color: rgba(52, 211, 153, 0.3); }
  .toast-error { border-color: rgba(248, 113, 113, 0.3); }
  .toast-info { border-color: rgba(167, 139, 250, 0.3); }
  .toast-icon { display: flex; align-items: center; flex-shrink: 0; }
  .toast-message { color: var(--text-primary); line-height: 1.3; }

  @keyframes toastIn {
    from { transform: translateX(100%); opacity: 0; }
    to { transform: translateX(0); opacity: 1; }
  }

  /* ════════════════════════════════════════════════
     SIDEBAR
     ════════════════════════════════════════════════ */
  .sidebar {
    width: 260px;
    background: var(--bg-surface);
    border-right: 1px solid var(--border-subtle);
    backdrop-filter: blur(24px);
    display: flex;
    flex-direction: column;
    padding: 1.25rem 1rem;
    box-sizing: border-box;
    gap: 1.25rem;
    flex-shrink: 0;
    height: 100%;
    overflow-y: auto;
  }

  .sidebar-header {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    padding: 0.25rem 0.25rem 0 0.25rem;
  }
  .logo-mark { flex-shrink: 0; display: flex; }
  .logo-text h1 {
    font-size: 1.35rem;
    font-weight: 800;
    margin: 0;
    letter-spacing: -0.3px;
    background: linear-gradient(135deg, #fff 30%, var(--accent) 100%);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
  }
  .version-badge {
    display: inline-block;
    font-size: 0.6rem;
    padding: 0.1rem 0.4rem;
    border-radius: 20px;
    background: var(--accent-dim);
    border: 1px solid rgba(167, 139, 250, 0.2);
    color: var(--text-primary);
    font-weight: 600;
    letter-spacing: 0.3px;
  }

  /* Device Widget */
  .device-widget {
    border-radius: var(--radius-lg);
    padding: 1rem;
    border: 1px solid var(--border-subtle);
    background: var(--bg-elevated);
    display: flex;
    flex-direction: column;
    gap: 0.6rem;
    transition: border-color var(--transition);
  }
  .device-widget.connected { border-color: rgba(52, 211, 153, 0.15); }
  .device-widget.disconnected { border-style: dashed; }
  .dw-row { display: flex; align-items: center; gap: 0.6rem; }
  .dw-phone-icon { color: var(--text-secondary); display: flex; }
  .dw-phone-icon.dim { opacity: 0.35; }
  .dw-info { display: flex; flex-direction: column; gap: 0.1rem; }
  .dw-name { font-weight: 700; font-size: 0.95rem; }
  .dw-name.dim { color: var(--text-muted); }
  .dw-status { display: flex; align-items: center; gap: 0.35rem; font-size: 0.72rem; color: var(--text-secondary); }
  .dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
  .dot.online { background: var(--success); box-shadow: 0 0 6px var(--success); animation: pulse 2s infinite; }
  .dot.offline { background: var(--text-muted); }
  .dw-meta-row { display: flex; gap: 0.75rem; font-size: 0.7rem; color: var(--text-muted); align-items: center; }
  .dw-meta-item { display: flex; align-items: center; gap: 0.25rem; }
  .dw-actions { display: flex; gap: 0.4rem; }
  .dw-action-btn {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 0.3rem;
    background: var(--scrollbar-track);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-sm);
    padding: 0.35rem 0.4rem;
    font-size: 0.68rem;
    font-weight: 600;
    color: var(--text-secondary);
    cursor: pointer;
    transition: all var(--transition);
  }
  .dw-action-btn:hover { background: rgba(255,255,255,0.06); color: var(--text-primary); }
  .dw-action-btn.active { background: var(--success-dim); border-color: rgba(52,211,153,0.2); color: var(--success); }

  @keyframes pulse {
    0%, 100% { opacity: 0.6; transform: scale(0.9); }
    50% { opacity: 1; transform: scale(1.15); }
  }

  /* Navigation */
  .sidebar-nav { display: flex; flex-direction: column; gap: 2px; margin-top: auto; }
  .nav-item {
    display: flex;
    align-items: center;
    gap: 0.65rem;
    padding: 0.65rem 0.75rem;
    border-radius: var(--radius-md);
    color: var(--text-secondary);
    background: transparent;
    border: none;
    cursor: pointer;
    font-size: 0.85rem;
    font-weight: 600;
    transition: all var(--transition);
    position: relative;
    text-align: left;
  }
  .nav-item:hover { color: var(--text-primary); background: var(--hover-bg); }
  .nav-item.active {
    color: var(--text-primary);
    background: var(--accent-dim);
  }
  .nav-item.active::before {
    content: "";
    position: absolute;
    left: 0;
    top: 50%;
    transform: translateY(-50%);
    width: 3px;
    height: 16px;
    background: var(--accent);
    border-radius: 0 2px 2px 0;
  }
  .nav-badge {
    margin-left: auto;
    font-size: 0.65rem;
    background: var(--accent);
    color: var(--bg-base);
    padding: 0.05rem 0.35rem;
    border-radius: 10px;
    font-weight: 700;
  }

  /* ════════════════════════════════════════════════
     WORKSPACE
     ════════════════════════════════════════════════ */
  .workspace {
    flex: 1;
    padding: 2rem;
    box-sizing: border-box;
    overflow-y: auto;
    height: 100%;
  }

  .tab-panel {
    display: flex;
    flex-direction: column;
    gap: 1.5rem;
    max-width: 820px;
    margin: 0 auto;
    animation: fadeUp 0.35s ease-out;
  }

  @keyframes fadeUp {
    from { opacity: 0; transform: translateY(8px); }
    to { opacity: 1; transform: translateY(0); }
  }

  .panel-header h2 {
    font-size: 1.5rem;
    font-weight: 800;
    margin: 0 0 0.3rem 0;
    letter-spacing: -0.3px;
  }
  .panel-desc {
    margin: 0;
    font-size: 0.85rem;
    color: var(--text-secondary);
    line-height: 1.4;
  }

  /* ════════════════════════════════════════════════
     BUTTONS
     ════════════════════════════════════════════════ */
  .btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.4rem;
    padding: 0.6rem 1rem;
    border-radius: var(--radius-sm);
    font-weight: 600;
    font-size: 0.82rem;
    cursor: pointer;
    transition: all var(--transition);
    border: none;
    outline: none;
    font-family: var(--font);
  }
  .btn-sm { padding: 0.35rem 0.65rem; font-size: 0.75rem; }
  .btn-primary {
    background: #2563eb;
    color: #ffffff;
    box-shadow: 0 2px 8px rgba(37, 99, 235, 0.25);
  }
  .btn-primary:hover:not(:disabled) { background: #1d4ed8; transform: translateY(-1px); box-shadow: 0 4px 12px rgba(37, 99, 235, 0.35); }
  .btn-primary:disabled { opacity: 0.35; cursor: not-allowed; box-shadow: none; }
  .btn-outline {
    background: transparent;
    border: 1px solid var(--border-accent);
    color: var(--text-secondary);
  }
  .btn-outline:hover { background: rgba(255,255,255,0.04); color: var(--text-primary); }
  .btn-ghost-danger {
    background: transparent;
    border: none;
    color: var(--error);
    font-weight: 600;
  }
  .btn-ghost-danger:hover { background: var(--error-dim); }
  .btn-danger-subtle {
    background: var(--error-dim);
    border: 1px solid rgba(248,113,113,0.15);
    color: var(--error);
  }
  .btn-danger-subtle:hover { background: rgba(248,113,113,0.18); border-color: var(--error); }
  .full-width { width: 100%; }

  /* ════════════════════════════════════════════════
     EMPTY STATE
     ════════════════════════════════════════════════ */
  .empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 4rem 2rem;
    color: var(--text-secondary);
    border: 1px dashed var(--border-subtle);
    border-radius: var(--radius-xl);
    text-align: center;
    gap: 0.75rem;
  }
  .empty-icon { color: var(--text-primary); opacity: 0.5; margin-bottom: 0.25rem; }
  .empty-state h3 { margin: 0; font-size: 1.1rem; color: var(--text-primary); font-weight: 700; }
  .empty-state p { margin: 0; font-size: 0.85rem; max-width: 320px; line-height: 1.5; }

  /* ════════════════════════════════════════════════
     PHONE CHASSIS (Mirroring)
     ════════════════════════════════════════════════ */
  .mirror-workspace {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 1.25rem;
  }

  .phone-chassis {
    position: relative;
    width: 300px;
    background: linear-gradient(145deg, #1a1730 0%, #12101f 100%);
    border-radius: 36px;
    box-shadow: 
      0 30px 60px -10px rgba(0,0,0,0.65),
      0 0 0 1px rgba(255,255,255,0.04),
      inset 0 1px 0 rgba(255,255,255,0.06);
    padding: 10px;
    box-sizing: border-box;
  }

  /* Side buttons */
  .chassis-vol-up, .chassis-vol-down, .chassis-power {
    position: absolute;
    background: #1f1c32;
    border-radius: 2px;
  }
  .chassis-vol-up { width: 3px; height: 28px; top: 100px; left: -2px; }
  .chassis-vol-down { width: 3px; height: 28px; top: 140px; left: -2px; }
  .chassis-power { width: 3px; height: 36px; top: 110px; right: -2px; }

  .chassis-inner {
    background: #000;
    border-radius: 28px;
    overflow: hidden;
    position: relative;
    display: flex;
    flex-direction: column;
  }

  .punch-hole {
    position: absolute;
    top: 10px;
    left: 50%;
    transform: translateX(-50%);
    width: 10px;
    height: 10px;
    background: #0a0a12;
    border-radius: 50%;
    z-index: 10;
    box-shadow: inset 0 1px 2px rgba(255,255,255,0.08);
  }

  .phone-status-bar {
    height: 24px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 10px 18px 0 18px;
    background: #000;
    font-size: 0.72rem;
    font-weight: 500;
    color: rgba(255, 255, 255, 0.75);
    user-select: none;
    z-index: 5;
  }
  .status-icons {
    display: flex;
    align-items: center;
    gap: 5px;
  }
  .status-icon {
    opacity: 0.75;
  }
  .status-network, .status-battery {
    font-size: 0.65rem;
    font-weight: 600;
  }

  .phone-viewport {
    width: 100%;
    aspect-ratio: 9/19.5;
    background: #000;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
  }

  .mirror-canvas {
    width: 100%;
    height: 100%;
    display: block;
    cursor: pointer;
    object-fit: contain;
  }

  .gesture-bar {
    display: flex;
    justify-content: center;
    padding: 6px 0 8px 0;
    background: #000;
  }
  .gesture-pill {
    width: 100px;
    height: 4px;
    background: rgba(255,255,255,0.2);
    border-radius: 2px;
  }

  .nav-keys {
    display: flex;
    justify-content: center;
    gap: 2rem;
    padding: 0.75rem 0 0.25rem 0;
  }
  .nav-key {
    width: 38px;
    height: 38px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    background: rgba(255,255,255,0.04);
    border: 1px solid rgba(255,255,255,0.06);
    color: var(--text-secondary);
    cursor: pointer;
    transition: all var(--transition);
  }
  .nav-key:hover { background: rgba(255,255,255,0.08); color: var(--text-primary); }
  .home-key { border: 2px solid rgba(255,255,255,0.1); }

  .stop-btn { width: 300px; }

  /* ════════════════════════════════════════════════
     NOTIFICATIONS
     ════════════════════════════════════════════════ */
  .notif-list { display: flex; flex-direction: column; gap: 0.6rem; }
  .notif-card {
    background: var(--bg-elevated);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-lg);
    padding: 1rem 1.15rem;
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    transition: border-color var(--transition);
  }
  .notif-card:hover { border-color: var(--border-accent); }
  .notif-header { display: flex; align-items: center; gap: 0.5rem; font-size: 0.75rem; color: var(--text-muted); }
  .notif-app-icon { width: 20px; height: 20px; border-radius: 5px; }
  .notif-app-icon-placeholder { color: var(--text-muted); display: flex; }
  .notif-app-name { font-weight: 700; color: var(--text-secondary); }
  .notif-time { margin-left: auto; }
  .notif-body h4 { margin: 0; font-size: 0.92rem; font-weight: 700; }
  .notif-body p { margin: 0.15rem 0 0 0; font-size: 0.82rem; color: var(--text-secondary); line-height: 1.45; }
  .notif-actions { display: flex; align-items: center; gap: 0.5rem; margin-top: 0.25rem; }
  .reply-row { display: flex; gap: 0.4rem; flex: 1; }
  .reply-row input {
    flex: 1;
    background: rgba(0,0,0,0.3);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-sm);
    padding: 0.4rem 0.6rem;
    color: var(--text-primary);
    font-size: 0.8rem;
    outline: none;
    font-family: var(--font);
    transition: border-color var(--transition);
  }
  .reply-row input:focus { border-color: var(--text-primary); }

  /* ════════════════════════════════════════════════
     FILES / DROPZONES
     ════════════════════════════════════════════════ */
  .dropzone-grid {
    display: grid;
    grid-template-columns: 1fr;
    gap: 1rem;
  }
  @media (min-width: 600px) {
    .dropzone-grid { grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); }
  }
  .dropzone {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 2.5rem 1.5rem;
    text-align: center;
    border: 2px dashed var(--border-subtle);
    border-radius: var(--radius-xl);
    background: rgba(255,255,255,0.01);
    transition: all 0.3s ease;
    gap: 0.5rem;
  }
  .dropzone:hover { border-color: rgba(255,255,255,0.1); }
  .dropzone.drag-active {
    border-color: var(--text-primary);
    background: var(--accent-dim);
    transform: scale(1.01);
    box-shadow: 0 0 30px rgba(167,139,250,0.08);
  }
  .dropzone-icon { color: var(--text-primary); opacity: 0.6; margin-bottom: 0.25rem; }
  .dropzone h3 { margin: 0; font-size: 1.05rem; }
  .dropzone p { margin: 0; font-size: 0.82rem; color: var(--text-secondary); }

  .transfers-section { margin-top: 1.5rem; }
  .transfers-section h3 { margin: 0 0 0.75rem 0; font-size: 0.95rem; font-weight: 700; }
  .transfer-list { display: flex; flex-direction: column; gap: 0.6rem; }
  .transfer-item {
    background: var(--bg-elevated);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-md);
    padding: 0.85rem 1rem;
  }
  .transfer-info { display: flex; justify-content: space-between; margin-bottom: 0.4rem; }
  .transfer-name { font-size: 0.82rem; font-weight: 600; }
  .transfer-pct { font-size: 0.78rem; color: var(--text-primary); font-weight: 700; }
  .progress-track { height: 4px; background: rgba(255,255,255,0.06); border-radius: 2px; overflow: hidden; }
  .progress-fill { height: 100%; background: linear-gradient(90deg, var(--accent), #818cf8); border-radius: 2px; transition: width 0.3s ease; }
  .transfer-bytes { font-size: 0.7rem; color: var(--text-muted); margin-top: 0.3rem; display: block; }

  /* ════════════════════════════════════════════════
     DIALER
     ════════════════════════════════════════════════ */
  .dialer-workspace { display: flex; justify-content: center; }
  .dialer-widget {
    width: 280px;
    background: var(--bg-elevated);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-xl);
    padding: 1.5rem;
  }
  .dialer-input {
    background: rgba(0,0,0,0.3);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-md);
    padding: 0.75rem 0.75rem;
    color: var(--text-primary);
    font-size: 1.4rem;
    font-weight: 700;
    text-align: center;
    outline: none;
    letter-spacing: 1.5px;
    width: 100%;
    box-sizing: border-box;
    margin-bottom: 1rem;
    font-family: var(--font);
    transition: border-color var(--transition);
  }
  .dialer-input:focus { border-color: var(--text-primary); }

  .dial-grid {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 0.5rem;
  }
  .dial-btn {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    width: 100%;
    aspect-ratio: 1;
    border-radius: 50%;
    background: var(--scrollbar-track);
    border: 1px solid rgba(255,255,255,0.06);
    cursor: pointer;
    color: var(--text-primary);
    transition: all var(--transition);
    padding: 0;
  }
  .dial-btn:hover { background: rgba(255,255,255,0.07); border-color: var(--text-primary); }
  .dial-num { font-size: 1.2rem; font-weight: 700; line-height: 1; }
  .dial-sub { font-size: 0.5rem; font-weight: 600; color: var(--text-muted); letter-spacing: 1.5px; margin-top: 2px; }

  .dialer-actions {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 0.75rem;
    margin-top: 1rem;
  }
  .call-btn {
    width: 56px;
    height: 56px;
    border-radius: 50%;
    background: linear-gradient(135deg, #34d399, #10b981);
    border: none;
    color: #fff;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    transition: all var(--transition);
    box-shadow: 0 4px 16px rgba(52,211,153,0.3);
  }
  .call-btn:hover:not(:disabled) { transform: scale(1.05); box-shadow: 0 6px 20px rgba(52,211,153,0.4); }
  .call-btn:disabled { opacity: 0.35; cursor: not-allowed; box-shadow: none; }

  /* ════════════════════════════════════════════════
     SETTINGS
     ════════════════════════════════════════════════ */
  .settings-workspace { display: flex; flex-direction: column; gap: 1.25rem; }
  .settings-section {
    background: var(--bg-elevated);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-lg);
    padding: 1.25rem;
  }
  .section-title {
    margin: 0 0 0.85rem 0;
    font-size: 0.92rem;
    font-weight: 700;
    display: flex;
    align-items: center;
    gap: 0.4rem;
    color: var(--text-primary);
  }
  .detail-grid { display: flex; flex-direction: column; gap: 0.4rem; }
  .detail-row { display: flex; font-size: 0.82rem; }
  .detail-label { color: var(--text-muted); width: 120px; flex-shrink: 0; }
  .detail-value { word-break: break-all; color: var(--text-secondary); }
  .detail-value.mono { font-family: "SF Mono", "Fira Code", monospace; color: var(--text-primary); font-size: 0.75rem; }

  .device-list { display: flex; flex-direction: column; gap: 0.5rem; }
  .device-row {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    padding: 0.75rem;
    border-radius: var(--radius-md);
    background: rgba(255,255,255,0.015);
    border: 1px solid transparent;
    transition: all var(--transition);
  }
  .device-row:hover { background: rgba(255,255,255,0.03); border-color: var(--border-subtle); }
  .device-row.online { border-left: 2px solid var(--success); }
  .device-row-icon { color: var(--text-secondary); flex-shrink: 0; display: flex; }
  .device-row-info { flex: 1; display: flex; flex-direction: column; gap: 0.1rem; }
  .device-row-name { font-weight: 700; font-size: 0.9rem; }
  .device-row-fp { font-size: 0.68rem; color: var(--text-muted); font-family: monospace; }
  .device-row-status { font-size: 0.72rem; color: var(--text-muted); display: flex; align-items: center; gap: 0.3rem; }
  .device-row-status.on { color: var(--success); }
  .device-row-actions { display: flex; gap: 0.4rem; flex-shrink: 0; }
  .hint-text { color: var(--text-muted); font-size: 0.82rem; text-align: center; padding: 1.5rem; margin: 0; }

  /* ════════════════════════════════════════════════
     MODALS
     ════════════════════════════════════════════════ */
  .modal-overlay {
    position: fixed;
    top: 0; left: 0;
    width: 100vw; height: 100vh;
    background: rgba(0,0,0,0.7);
    backdrop-filter: blur(6px);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 5000;
    animation: fadeUp 0.2s ease;
  }
  .modal-card {
    background: #110e1f;
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-xl);
    width: 90%;
    max-width: 380px;
    padding: 1.75rem;
    box-sizing: border-box;
  }
  .modal-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; }
  .modal-top h2 { margin: 0; font-size: 1.15rem; font-weight: 800; }
  .modal-close { background: none; border: none; color: var(--text-muted); cursor: pointer; display: flex; padding: 0.25rem; border-radius: 6px; }
  .modal-close:hover { background: rgba(255,255,255,0.05); color: var(--text-primary); }
  .modal-body p { font-size: 0.85rem; color: var(--text-secondary); margin: 0.5rem 0; line-height: 1.5; }
  .pin-display {
    font-size: 2.2rem;
    font-weight: 800;
    color: var(--text-primary);
    background: rgba(0,0,0,0.35);
    padding: 0.6rem;
    border-radius: var(--radius-md);
    font-family: "SF Mono", "Fira Code", monospace;
    letter-spacing: 3px;
    text-align: center;
    margin: 1rem 0;
  }

  /* ════════════════════════════════════════════════
     CALL OVERLAYS
     ════════════════════════════════════════════════ */
  .call-card { text-align: center; }
  .call-avatar {
    width: 72px; height: 72px;
    border-radius: 50%;
    background: var(--accent-dim);
    border: 2px solid rgba(167,139,250,0.2);
    display: flex;
    align-items: center;
    justify-content: center;
    margin: 0 auto 1rem auto;
    color: var(--text-primary);
  }
  .call-card h2 { font-size: 1.1rem; margin: 0 0 0.5rem 0; }
  .call-details { display: flex; flex-direction: column; gap: 0.2rem; margin-bottom: 1.5rem; }
  .caller-name { font-size: 1.15rem; font-weight: 700; }
  .caller-number { font-size: 0.85rem; color: var(--text-muted); font-family: monospace; }
  .call-controls { display: flex; justify-content: center; gap: 2rem; }
  .call-control-btn {
    width: 56px; height: 56px;
    border-radius: 50%;
    border: none;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    transition: all var(--transition);
    color: #fff;
  }
  .call-control-btn.answer {
    background: linear-gradient(135deg, #34d399, #10b981);
    box-shadow: 0 4px 16px rgba(52,211,153,0.3);
  }
  .call-control-btn.answer:hover { transform: scale(1.08); }
  .call-control-btn.decline {
    background: linear-gradient(135deg, #f87171, #ef4444);
    box-shadow: 0 4px 16px rgba(248,113,113,0.3);
  }
  .call-control-btn.decline:hover { transform: scale(1.08); }

  .call-banner {
    position: fixed;
    top: 0; left: 260px; right: 0;
    background: rgba(16,14,28,0.92);
    backdrop-filter: blur(12px);
    border-bottom: 1px solid var(--border-subtle);
    padding: 0.5rem 1.25rem;
    display: flex;
    align-items: center;
    gap: 0.6rem;
    z-index: 4000;
    color: var(--success);
    font-size: 0.82rem;
    font-weight: 600;
    animation: toastIn 0.3s ease;
  }
  .call-banner .btn { margin-left: auto; }

  /* Audio Route Selector (Dialer Tab) */
  .audio-route-selector {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    padding: 0.75rem 1rem;
    border-radius: var(--radius-md);
    background: var(--bg-elevated);
    border: 1px solid var(--border-subtle);
    margin-top: 1rem;
    width: 100%;
    max-width: 320px;
    box-sizing: border-box;
  }
  .audio-route-selector label {
    font-size: 0.85rem;
    color: var(--text-secondary);
    display: flex;
    align-items: center;
    gap: 0.4rem;
    font-weight: 500;
  }
  .audio-route-selector select {
    flex: 1;
    background: transparent;
    border: none;
    color: var(--text-primary);
    font-family: var(--font);
    font-size: 0.85rem;
    font-weight: 600;
    cursor: pointer;
    outline: none;
  }
  .audio-route-selector select option {
    background: var(--bg-base);
    color: var(--text-primary);
  }

  /* Telemetry signal bars inside phone status bar */
  .signal-bars {
    display: inline-flex;
    align-items: flex-end;
    gap: 2px;
    height: 10px;
  }
  .signal-bars .bar {
    width: 2px;
    background: rgba(255, 255, 255, 0.25);
    border-radius: 1px;
  }
  .signal-bars .bar:nth-child(1) { height: 3px; }
  .signal-bars .bar:nth-child(2) { height: 5px; }
  .signal-bars .bar:nth-child(3) { height: 7px; }
  .signal-bars .bar:nth-child(4) { height: 9px; }
  .signal-bars .bar.filled {
    background: var(--text-primary);
  }

  /* History list */
  .history-workspace {
    width: 100%;
    height: 100%;
  }
  .history-list-container {
    position: relative;
    display: flex;
    flex-direction: column;
    gap: 1rem;
    height: 100%;
  }
  .sync-btn-float {
    align-self: flex-end;
  }
  .history-list {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    overflow-y: auto;
    max-height: calc(100vh - 220px);
    padding-right: 4px;
  }
  .history-item {
    display: flex;
    align-items: center;
    padding: 0.9rem 1.2rem;
    border-radius: var(--radius-md);
    background: var(--bg-surface);
    border: 1px solid var(--border-subtle);
    transition: all var(--transition);
  }
  .history-item:hover {
    border-color: var(--border-accent);
    background: var(--bg-elevated);
  }
  .history-type-icon {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    border-radius: 50%;
    margin-right: 1rem;
  }
  .history-type-icon.incoming { background: rgba(16, 185, 129, 0.1); }
  .history-type-icon.outgoing { background: rgba(129, 140, 248, 0.1); }
  .history-type-icon.missed { background: rgba(239, 68, 68, 0.1); }
  .history-info {
    display: flex;
    flex-direction: column;
    flex: 1;
  }
  .history-name {
    font-size: 0.95rem;
    font-weight: 600;
    color: var(--text-primary);
  }
  .history-number {
    font-size: 0.78rem;
    color: var(--text-secondary);
    margin-top: 1px;
  }
  .history-meta {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    margin-right: 1.5rem;
    text-align: right;
  }
  .history-time {
    font-size: 0.8rem;
    color: var(--text-secondary);
  }
  .history-duration {
    font-size: 0.75rem;
    color: var(--text-muted);
    margin-top: 1px;
  }
  .call-back-btn {
    opacity: 0.6;
    transition: opacity var(--transition);
  }
  .call-back-btn:hover {
    opacity: 1;
    color: var(--text-primary);
  }

  /* SMS Workspace Layout */
  .sms-workspace-layout {
    display: grid;
    grid-template-columns: 280px 1fr;
    gap: 1.5rem;
    height: calc(100vh - 200px);
    overflow: hidden;
  }
  .sms-threads-sidebar {
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
    height: 100%;
    border-right: 1px solid var(--border-subtle);
    padding-right: 1rem;
    overflow-y: auto;
  }
  .threads-list {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    overflow-y: auto;
    flex: 1;
  }
  .thread-item {
    display: flex;
    flex-direction: column;
    text-align: left;
    padding: 0.85rem 1rem;
    border-radius: var(--radius-md);
    background: var(--bg-surface);
    border: 1px solid var(--border-subtle);
    cursor: pointer;
    transition: all var(--transition);
  }
  .thread-item:hover {
    background: var(--bg-elevated);
    border-color: var(--border-accent);
  }
  .thread-item.active {
    background: var(--accent-dim);
    border-color: var(--text-primary);
  }
  .thread-item-header {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    gap: 0.5rem;
  }
  .thread-name {
    font-size: 0.88rem;
    font-weight: 600;
    color: var(--text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .thread-date {
    font-size: 0.75rem;
    color: var(--text-muted);
    flex-shrink: 0;
  }
  .thread-preview {
    font-size: 0.78rem;
    color: var(--text-secondary);
    margin-top: 4px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .sms-thread-view {
    display: flex;
    flex-direction: column;
    background: rgba(16, 14, 28, 0.25);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-lg);
    height: 100%;
    overflow: hidden;
  }
  .thread-view-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 0.9rem 1.2rem;
    border-bottom: 1px solid var(--border-subtle);
    background: var(--bg-elevated);
  }
  .thread-view-contact {
    display: flex;
    flex-direction: column;
  }
  .view-contact-name {
    font-size: 0.95rem;
    font-weight: 700;
  }
  .view-contact-address {
    font-size: 0.75rem;
    color: var(--text-secondary);
  }
  .thread-messages-list {
    flex: 1;
    overflow-y: auto;
    padding: 1.25rem;
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
  }
  .sms-bubble-wrapper {
    display: flex;
    width: 100%;
  }
  .sms-bubble-wrapper.inbox {
    justify-content: flex-start;
  }
  .sms-bubble-wrapper.sent {
    justify-content: flex-end;
  }
  .sms-bubble {
    max-width: 70%;
    padding: 0.65rem 0.9rem;
    border-radius: var(--radius-md);
    box-shadow: var(--shadow-card);
    position: relative;
  }
  .sms-bubble-wrapper.inbox .sms-bubble {
    background: var(--bg-elevated);
    border: 1px solid var(--border-subtle);
    border-bottom-left-radius: 2px;
  }
  .sms-bubble-wrapper.sent .sms-bubble {
    background: var(--accent-dim);
    border: 1px solid rgba(167, 139, 250, 0.25);
    border-bottom-right-radius: 2px;
  }
  .sms-body {
    margin: 0;
    font-size: 0.85rem;
    line-height: 1.4;
    color: var(--text-primary);
    word-break: break-word;
  }
  .sms-time {
    display: block;
    font-size: 0.68rem;
    color: var(--text-muted);
    margin-top: 4px;
    text-align: right;
  }
  .thread-reply-box {
    padding: 0.9rem 1.2rem;
    border-top: 1px solid var(--border-subtle);
    background: rgba(16, 14, 28, 0.4);
  }
  .sms-reply-input {
    width: 100%;
    padding: 0.65rem 1rem;
    border-radius: var(--radius-md);
    background: var(--bg-elevated);
    border: 1px solid var(--border-subtle);
    color: var(--text-primary);
    font-family: var(--font);
    font-size: 0.82rem;
    outline: none;
    box-sizing: border-box;
  }
  .thread-view-empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    flex: 1;
    gap: 0.75rem;
    color: var(--text-muted);
    font-size: 0.85rem;
  }

  .auth-loading-screen {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 100vh;
    background: #09080f;
    color: #f0ecf7;
    font-family: var(--font);
  }
  .auth-loading-card {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 16px;
  }
  .auth-spinner {
    width: 32px;
    height: 32px;
    border: 3px solid rgba(167, 139, 250, 0.2);
    border-radius: 50%;
    border-top-color: #a78bfa;
    animation: authSpin 0.8s linear infinite;
  }
  @keyframes authSpin {
    to { transform: rotate(360deg); }
  }
  .auth-loading-title {
    font-size: 0.85rem;
    color: #8b82a8;
    letter-spacing: 0.05em;
  }


  /* ════════════════════════════════════════════════
     OVERVIEW / HOMESCREEN DASHBOARD STYLES
     ════════════════════════════════════════════════ */
  .overview-tab {
    display: flex;
    flex-direction: column;
    gap: 1.5rem;
  }
  .status-pill {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.4rem 0.85rem;
    border-radius: 30px;
    font-size: 0.75rem;
    font-weight: 600;
    backdrop-filter: blur(8px);
  }
  .status-pill.online {
    background: rgba(52, 211, 153, 0.1);
    border: 1px solid rgba(52, 211, 153, 0.25);
    color: var(--success);
  }
  .status-pill.offline {
    background: rgba(167, 139, 250, 0.1);
    border: 1px solid rgba(167, 139, 250, 0.2);
    color: var(--text-primary);
  }
  .overview-hero-card {
    display: flex;
    justify-content: space-between;
    align-items: center;
    background: linear-gradient(135deg, rgba(30, 21, 53, 0.8) 0%, rgba(15, 10, 28, 0.9) 100%);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-xl);
    padding: 1.5rem;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
    gap: 1.5rem;
  }
  .hero-device-left {
    display: flex;
    align-items: center;
    gap: 1.2rem;
  }
  .hero-avatar {
    width: 64px;
    height: 64px;
    border-radius: var(--radius-lg);
    background: linear-gradient(135deg, rgba(167, 139, 250, 0.2) 0%, rgba(167, 139, 250, 0.05) 100%);
    border: 1px solid rgba(167, 139, 250, 0.3);
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-primary);
  }
  .hero-device-details {
    display: flex;
    flex-direction: column;
    gap: 0.3rem;
  }
  .hero-device-title-row {
    display: flex;
    align-items: center;
    gap: 0.6rem;
  }
  .hero-device-name {
    font-size: 1.35rem;
    font-weight: 700;
    margin: 0;
    color: var(--text-primary);
  }
  .badge-type {
    font-size: 0.65rem;
    padding: 0.15rem 0.5rem;
    background: rgba(167, 139, 250, 0.15);
    border: 1px solid rgba(167, 139, 250, 0.3);
    border-radius: 12px;
    color: var(--text-primary);
    font-weight: 700;
    letter-spacing: 0.5px;
  }
  .hero-device-meta {
    display: flex;
    gap: 0.6rem;
    font-size: 0.75rem;
    color: var(--text-muted);
  }
  .meta-tag {
    background: rgba(255, 255, 255, 0.04);
    padding: 0.15rem 0.45rem;
    border-radius: 4px;
  }
  .hero-device-telemetry {
    display: flex;
    gap: 2rem;
    align-items: center;
  }
  .telemetry-gauge {
    display: flex;
    flex-direction: column;
    gap: 0.4rem;
    min-width: 150px;
  }
  .gauge-label {
    font-size: 0.72rem;
    color: var(--text-muted);
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }
  .gauge-val-row {
    display: flex;
    align-items: baseline;
    gap: 0.5rem;
  }
  .gauge-val {
    font-size: 1.25rem;
    font-weight: 700;
    color: var(--text-primary);
  }
  .charging-icon {
    font-size: 0.75rem;
    color: var(--success);
    font-weight: 600;
  }
  .signal-tag {
    font-size: 0.7rem;
    color: var(--text-primary);
    font-weight: 600;
    background: rgba(167, 139, 250, 0.1);
    padding: 0.1rem 0.35rem;
    border-radius: 4px;
  }
  .progress-track {
    width: 100%;
    height: 6px;
    background: rgba(255, 255, 255, 0.08);
    border-radius: 4px;
    overflow: hidden;
  }
  .progress-bar-fill {
    height: 100%;
    background: linear-gradient(90deg, #34d399, #10b981);
    border-radius: 4px;
    transition: width 0.4s ease;
  }
  .signal-meter-bars {
    display: flex;
    align-items: flex-end;
    gap: 3px;
    height: 14px;
  }
  .sbar {
    width: 4px;
    background: rgba(255, 255, 255, 0.15);
    border-radius: 1px;
  }
  .sbar:nth-child(1) { height: 4px; }
  .sbar:nth-child(2) { height: 7px; }
  .sbar:nth-child(3) { height: 10px; }
  .sbar:nth-child(4) { height: 14px; }
  .sbar.active { background: var(--accent); }

  .overview-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
    gap: 1rem;
  }
  .overview-card {
    background: var(--bg-elevated);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-lg);
    padding: 1.25rem;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    gap: 0.85rem;
    transition: transform var(--transition), border-color var(--transition);
  }
  .overview-card:hover {
    transform: translateY(-2px);
    border-color: rgba(167, 139, 250, 0.3);
  }
  .card-header-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .card-icon-title {
    display: flex;
    align-items: center;
    gap: 0.65rem;
  }
  .card-icon-title h4 {
    margin: 0;
    font-size: 0.95rem;
    font-weight: 700;
  }
  .card-badge-icon {
    width: 32px;
    height: 32px;
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .card-badge-icon.purple { background: rgba(167, 139, 250, 0.15); color: #c084fc; }
  .card-badge-icon.green { background: rgba(52, 211, 153, 0.15); color: #34d399; }
  .card-badge-icon.blue { background: rgba(96, 165, 250, 0.15); color: #60a5fa; }
  .card-badge-icon.orange { background: rgba(251, 146, 60, 0.15); color: #fb923c; }
  .card-status-tag {
    font-size: 0.68rem;
    padding: 0.15rem 0.45rem;
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.05);
    color: var(--text-muted);
    font-weight: 600;
  }
  .card-status-tag.active {
    background: rgba(52, 211, 153, 0.15);
    color: var(--success);
  }
  .card-desc {
    font-size: 0.78rem;
    color: var(--text-secondary);
    line-height: 1.4;
    margin: 0;
  }
  .clipboard-preview-box {
    background: rgba(0, 0, 0, 0.25);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-sm);
    padding: 0.6rem 0.75rem;
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    min-height: 54px;
    justify-content: center;
  }
  .clip-snippet {
    font-size: 0.76rem;
    color: var(--text-primary);
    word-break: break-all;
  }
  .clip-snippet.empty {
    color: var(--text-muted);
    font-style: italic;
  }
  .clip-source {
    font-size: 0.65rem;
    color: var(--text-primary);
  }
  .overview-dropzone {
    border: 1px dashed var(--border-subtle);
    border-radius: var(--radius-sm);
    padding: 0.85rem;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.4rem;
    text-align: center;
    color: var(--text-muted);
    font-size: 0.75rem;
    transition: all var(--transition);
  }
  .overview-dropzone.drag-over {
    border-color: var(--text-primary);
    background: var(--accent-dim);
    color: var(--text-primary);
  }
  .overview-notif-list {
    display: flex;
    flex-direction: column;
    gap: 0.4rem;
    min-height: 54px;
    justify-content: center;
  }
  .empty-notif-hint {
    font-size: 0.75rem;
    color: var(--text-muted);
    margin: 0;
  }
  .mini-notif-item {
    display: flex;
    flex-direction: column;
    font-size: 0.75rem;
    background: rgba(255, 255, 255, 0.02);
    padding: 0.35rem 0.5rem;
    border-radius: 4px;
  }
  .mini-notif-app { font-weight: 700; font-size: 0.68rem; color: var(--text-primary); }
  .mini-notif-title { color: var(--text-primary); }
  .card-actions-bottom {
    display: flex;
    justify-content: flex-end;
    margin-top: 0.25rem;
  }

  /* Pairing Hub Disconnected */
  .overview-pairing-hub {
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
    gap: 1.75rem;
    padding: 1.5rem 0;
  }
  .pairing-hero-header {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.5rem;
  }
  .radar-pulse-ring {
    position: relative;
    width: 90px;
    height: 90px;
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: 0.5rem;
  }
  .pulse-core {
    width: 54px;
    height: 54px;
    border-radius: 50%;
    background: linear-gradient(135deg, #2563eb, #1d4ed8);
    display: flex;
    align-items: center;
    justify-content: center;
    color: #fff;
    box-shadow: 0 0 20px rgba(167, 139, 250, 0.5);
    z-index: 2;
  }
  .pulse-circle {
    position: absolute;
    border-radius: 50%;
    border: 1.5px solid rgba(167, 139, 250, 0.4);
    animation: radarPulse 3s cubic-bezier(0.2, 0.8, 0.2, 1) infinite;
  }
  .pulse-circle.c1 { width: 90px; height: 90px; animation-delay: 0s; }
  .pulse-circle.c2 { width: 120px; height: 120px; animation-delay: 0.8s; }
  .pulse-circle.c3 { width: 150px; height: 150px; animation-delay: 1.6s; }
  @keyframes radarPulse {
    0% { transform: scale(0.6); opacity: 0.8; }
    100% { transform: scale(1.4); opacity: 0; }
  }
  .pairing-subtitle {
    font-size: 0.88rem;
    color: var(--text-secondary);
    max-width: 480px;
    line-height: 1.4;
    margin: 0;
  }
  .pairing-cards-container {
    display: flex;
    gap: 1.5rem;
    justify-content: center;
    flex-wrap: wrap;
  }
  .pairing-box-card {
    background: var(--bg-elevated);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-lg);
    padding: 1.5rem;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.85rem;
    min-width: 240px;
    box-shadow: 0 4px 20px rgba(0, 0, 0, 0.2);
  }
  .pairing-box-title {
    font-size: 0.72rem;
    font-weight: 700;
    letter-spacing: 1px;
    color: var(--text-primary);
  }
  .qr-canvas-wrapper {
    background: #ffffff;
    padding: 10px;
    border-radius: 12px;
    display: inline-block;
    box-shadow: 0 4px 14px rgba(0, 0, 0, 0.25);
  }
  .qr-hint {
    font-size: 0.72rem;
    color: var(--text-muted);
  }
  .pin-large-badge {
    font-size: 2.2rem;
    font-weight: 800;
    letter-spacing: 6px;
    color: var(--text-primary);
    background: rgba(167, 139, 250, 0.08);
    border: 1px solid rgba(167, 139, 250, 0.25);
    padding: 0.75rem 1.5rem;
    border-radius: 12px;
    font-family: var(--font-mono, monospace);
  }
  .pairing-steps-row {
    display: flex;
    align-items: center;
    gap: 1rem;
    background: rgba(255, 255, 255, 0.02);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-md);
    padding: 0.75rem 1.25rem;
    font-size: 0.78rem;
    color: var(--text-secondary);
  }
  .step-item {
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }
  .step-num {
    width: 20px;
    height: 20px;
    border-radius: 50%;
    background: var(--accent-dim);
    border: 1px solid rgba(167, 139, 250, 0.3);
    color: var(--text-primary);
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: 700;
    font-size: 0.7rem;
  }
  .step-arrow {
    color: var(--text-muted);
    font-weight: 700;
  }


  /* ═══════════════════════════════════════════════════════════════ */
  /* 🌟 MOTION.AI / FRAMER SPRING INTRO ANIMATIONS & STYLES         */
  /* ═══════════════════════════════════════════════════════════════ */
  .intro-motion-overlay {
    position: fixed;
    inset: 0;
    z-index: 99999;
    background: radial-gradient(circle at 50% 40%, #170d2b 0%, #090514 70%, #030108 100%);
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    overflow: hidden;
    transition: opacity 0.45s cubic-bezier(0.16, 1, 0.3, 1), transform 0.45s cubic-bezier(0.16, 1, 0.3, 1), filter 0.45s ease;
  }
  .intro-motion-overlay.exit-shutter {
    opacity: 0;
    transform: scale(1.08);
    filter: blur(14px);
    pointer-events: none;
  }
  .intro-particle-canvas {
    position: absolute;
    inset: 0;
    pointer-events: none;
    z-index: 1;
  }
  .intro-ambient-aurora {
    position: absolute;
    width: 600px;
    height: 600px;
    border-radius: 50%;
    background: radial-gradient(circle, rgba(192, 132, 252, 0.18) 0%, rgba(129, 140, 248, 0.12) 40%, rgba(56, 189, 248, 0) 70%);
    filter: blur(60px);
    animation: auroraFloat 8s ease-in-out infinite alternate;
    pointer-events: none;
    z-index: 0;
  }
  @keyframes auroraFloat {
    0% { transform: translate(-30px, -20px) scale(0.9); }
    100% { transform: translate(30px, 20px) scale(1.15); }
  }
  .intro-stage {
    position: relative;
    z-index: 2;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 1.5rem;
    text-align: center;
    transform: translateY(20px);
    opacity: 0;
    transition: all 0.6s cubic-bezier(0.34, 1.56, 0.64, 1);
  }
  .intro-stage.stage-reveal {
    transform: translateY(0);
    opacity: 1;
  }
  .intro-hologram-wrap {
    position: relative;
    width: 110px;
    height: 110px;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .intro-hologram-wrap.hologram-float {
    animation: holoFloating 4s ease-in-out infinite;
  }
  @keyframes holoFloating {
    0%, 100% { transform: translateY(0) rotate(0deg); }
    50% { transform: translateY(-8px) rotate(1deg); }
  }
  .intro-glow-disc {
    position: absolute;
    inset: -15px;
    border-radius: 35%;
    background: radial-gradient(circle, rgba(168, 85, 247, 0.4) 0%, rgba(99, 102, 241, 0.1) 60%, transparent 80%);
    filter: blur(16px);
    animation: glowPulse 2.5s ease-in-out infinite alternate;
  }
  @keyframes glowPulse {
    0% { opacity: 0.5; transform: scale(0.92); }
    100% { opacity: 1; transform: scale(1.08); }
  }
  .intro-laser-svg {
    width: 100px;
    height: 100px;
    filter: drop-shadow(0 0 18px rgba(192, 132, 252, 0.65));
  }
  .laser-rect {
    stroke-dasharray: 340;
    stroke-dashoffset: 340;
    animation: laserDraw 1.2s cubic-bezier(0.2, 0.8, 0.2, 1) forwards;
  }
  .laser-bar {
    stroke-dasharray: 60;
    stroke-dashoffset: 60;
    animation: laserDraw 0.8s cubic-bezier(0.2, 0.8, 0.2, 1) forwards;
  }
  .laser-bar.bar-1 { animation-delay: 0.3s; }
  .laser-bar.bar-2 { animation-delay: 0.45s; }
  .laser-bar.bar-3 { animation-delay: 0.6s; }

  @keyframes laserDraw {
    to { stroke-dashoffset: 0; }
  }
  .intro-typography {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.5rem;
  }
  .intro-brand-title {
    display: flex;
    gap: 0.45rem;
    font-family: var(--font-sans, system-ui);
    font-weight: 900;
    font-size: 3.2rem;
    letter-spacing: 0.18em;
    background: linear-gradient(135deg, #ffffff 0%, #e2e8f0 40%, #c084fc 80%, #38bdf8 100%);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
    text-shadow: 0 4px 30px rgba(192, 132, 252, 0.3);
  }
  .intro-brand-title .letter {
    display: inline-block;
    transform: translateY(30px) scale(0.6);
    opacity: 0;
    transition: all 0.5s cubic-bezier(0.34, 1.56, 0.64, 1);
  }
  .intro-brand-title .letter.pop-in {
    transform: translateY(0) scale(1);
    opacity: 1;
  }
  .l1 { transition-delay: 0.1s; }
  .l2 { transition-delay: 0.18s; }
  .l3 { transition-delay: 0.26s; }
  .l4 { transition-delay: 0.34s; }
  .l5 { transition-delay: 0.42s; }

  .intro-subtitle-row {
    display: flex;
    align-items: center;
    gap: 0.65rem;
    font-size: 0.88rem;
    color: #94a3b8;
    opacity: 0;
    transform: translateY(10px);
    transition: all 0.5s ease 0.4s;
  }
  .intro-subtitle-row.fade-up {
    opacity: 1;
    transform: translateY(0);
  }
  .shimmer-badge {
    font-size: 0.72rem;
    font-weight: 800;
    letter-spacing: 0.12em;
    color: #c084fc;
    background: rgba(192, 132, 252, 0.12);
    border: 1px solid rgba(192, 132, 252, 0.3);
    padding: 3px 9px;
    border-radius: 100px;
    box-shadow: 0 0 12px rgba(192, 132, 252, 0.25);
  }
  .bullet { color: #64748b; }
  .subtitle-text { font-weight: 500; color: #cbd5e1; }

  .intro-hud-row {
    display: flex;
    gap: 0.85rem;
    margin-top: 0.25rem;
    opacity: 0;
    transform: scale(0.92);
    transition: all 0.45s cubic-bezier(0.34, 1.56, 0.64, 1) 0.5s;
  }
  .intro-hud-row.hud-reveal {
    opacity: 1;
    transform: scale(1);
  }
  .hud-pill {
    display: flex;
    align-items: center;
    gap: 0.4rem;
    background: rgba(255, 255, 255, 0.04);
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 100px;
    padding: 4px 12px;
    font-size: 0.68rem;
    font-weight: 700;
    letter-spacing: 0.06em;
    color: #e2e8f0;
    backdrop-filter: blur(10px);
  }
  .hud-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
  }
  .hud-dot.cyan { background: #38bdf8; box-shadow: 0 0 8px #38bdf8; }
  .hud-dot.purple { background: #c084fc; box-shadow: 0 0 8px #c084fc; }
  .hud-dot.green { background: #34d399; box-shadow: 0 0 8px #34d399; }

  .intro-skip-hint {
    margin-top: 1rem;
    display: flex;
    align-items: center;
    gap: 0.35rem;
    font-size: 0.74rem;
    color: #64748b;
    opacity: 0;
    transition: opacity 0.5s ease 0.65s;
  }
  .intro-skip-hint.hint-appear {
    opacity: 0.7;
    animation: hintBreathe 2s ease-in-out infinite alternate;
  }
  @keyframes hintBreathe {
    0% { opacity: 0.4; transform: translateY(0); }
    100% { opacity: 0.9; transform: translateY(2px); }
  }


  /* ════════════════════════════════════════════════
     THEME TOGGLE BUTTON
     ════════════════════════════════════════════════ */
  .theme-toggle-wrap {
    padding: 0 0.75rem;
    margin-bottom: 0.35rem;
  }
  .theme-toggle-btn {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 0.65rem;
    padding: 0.55rem 0.85rem;
    border-radius: var(--radius-md);
    border: 1px solid var(--border-subtle);
    background: var(--hover-bg, rgba(255,255,255,0.03));
    color: var(--text-secondary);
    font-size: 0.82rem;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.25s ease;
  }
  .theme-toggle-btn:hover {
    background: var(--hover-bg-strong, rgba(255,255,255,0.06));
    color: var(--text-primary);
    border-color: var(--text-primary);
  }

  /* ════════════════════════════════════════════════
     LIGHT THEME — COMPONENT OVERRIDES
     ════════════════════════════════════════════════ */
  :global(:root[data-theme="light"]) .sidebar {
    background: var(--sidebar-bg);
    border-right-color: var(--border-subtle);
    box-shadow: 2px 0 8px rgba(0,0,0,0.04);
  }
  :global(:root[data-theme="light"]) .overview-hero-card {
    background: var(--hero-card-bg);
    border-color: var(--border-subtle);
    box-shadow: var(--shadow-card);
  }
  :global(:root[data-theme="light"]) .overview-card {
    background: var(--card-bg);
    border-color: var(--border-subtle);
    box-shadow: var(--shadow-card);
  }
  :global(:root[data-theme="light"]) .device-widget {
    background: var(--card-bg);
    border-color: var(--border-subtle);
  }
  :global(:root[data-theme="light"]) .settings-section {
    background: var(--card-bg);
    border-color: var(--border-subtle);
    box-shadow: var(--shadow-card);
  }
  :global(:root[data-theme="light"]) .detail-row {
    border-bottom-color: var(--border-subtle);
  }
  :global(:root[data-theme="light"]) .toast-container .toast {
    background: rgba(255,255,255,0.95);
    border-color: var(--border-subtle);
    box-shadow: var(--shadow-elevated);
  }
  :global(:root[data-theme="light"]) .btn-primary {
    background: #2563eb;
    color: #ffffff;
    box-shadow: 0 2px 8px rgba(37, 99, 235, 0.2);
  }
  :global(:root[data-theme="light"]) .btn-primary:hover {
    background: #1d4ed8;
  }
  :global(:root[data-theme="light"]) .btn-outline {
    border-color: var(--border-accent);
    color: var(--text-secondary);
  }
  :global(:root[data-theme="light"]) .status-pill.online {
    background: rgba(5,150,105,0.08);
    border-color: rgba(5,150,105,0.2);
    color: #059669;
  }
  :global(:root[data-theme="light"]) .badge-type {
    background: var(--badge-bg);
    color: var(--text-primary);
  }
  :global(:root[data-theme="light"]) .hero-device-meta .meta-tag {
    background: var(--meta-bg);
    color: var(--text-secondary);
  }
  :global(:root[data-theme="light"]) .dw-action-btn {
    background: var(--meta-bg);
    border-color: var(--border-subtle);
  }
  :global(:root[data-theme="light"]) .dw-action-btn:hover {
    background: var(--hover-bg-strong);
    color: var(--text-primary);
  }
  :global(:root[data-theme="light"]) .empty-state {
    color: var(--text-secondary);
  }
  :global(:root[data-theme="light"]) .tab-panel {
    color: var(--text-primary);
  }
  :global(:root[data-theme="light"]) .sms-bubble {
    background: var(--card-bg);
    border-color: var(--border-subtle);
  }
  :global(:root[data-theme="light"]) .thread-item {
    border-bottom-color: var(--border-subtle);
  }
  :global(:root[data-theme="light"]) .thread-item:hover,
  :global(:root[data-theme="light"]) .thread-item.active {
    background: var(--accent-dim);
  }
  :global(:root[data-theme="light"]) input, :global(:root[data-theme="light"]) textarea {
    background: var(--input-bg);
    border-color: var(--input-border);
    color: var(--text-primary);
  }
  :global(:root[data-theme="light"]) .notification-card {
    background: var(--card-bg);
    border-color: var(--border-subtle);
  }
  :global(:root[data-theme="light"]) .logo-text h1 {
    background: linear-gradient(135deg, #1a1625 30%, var(--accent) 100%);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
  }
  :global(:root[data-theme="light"]) .modal-overlay {
    background: rgba(0,0,0,0.3);
    backdrop-filter: blur(6px);
  }
  :global(:root[data-theme="light"]) .modal-card {
    background: rgba(255,255,255,0.98);
    border-color: var(--border-subtle);
    box-shadow: var(--shadow-elevated);
  }

</style>
