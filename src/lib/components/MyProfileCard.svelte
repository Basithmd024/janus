<script lang="ts">
  interface Props {
    username: string;
    deviceName: string;
    uuid: string;
    onSaveProfile: (username: string, deviceName: string) => void;
    onToast: (msg: string, type?: 'info' | 'success' | 'error') => void;
  }

  let { username, deviceName, uuid, onSaveProfile, onToast }: Props = $props();

  let editUsername = $state("");
  let editDeviceName = $state("");

  $effect(() => {
    editUsername = username;
    editDeviceName = deviceName;
  });

  function handleBlur() {
    if ((editUsername && editUsername !== username) || (editDeviceName && editDeviceName !== deviceName)) {
      onSaveProfile(editUsername || username, editDeviceName || deviceName);
    }
  }

  function openUrl(url: string) {
    if (typeof window !== 'undefined') {
      window.open(url, '_blank');
    }
  }
</script>

<div class="settings-section">
  <h3 class="section-title">
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none"><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2" stroke="currentColor" stroke-width="1.5"/><circle cx="12" cy="7" r="4" stroke="currentColor" stroke-width="1.5"/></svg>
    My Profile
  </h3>
  
  <div class="detail-grid">
    <div class="detail-row">
      <span class="detail-label">Username</span>
      <input
        type="text"
        class="profile-input"
        bind:value={editUsername}
        onblur={handleBlur}
        placeholder="e.g. Babbi"
      />
    </div>
    
    <div class="detail-row">
      <span class="detail-label">Device Name</span>
      <input
        type="text"
        class="profile-input"
        bind:value={editDeviceName}
        onblur={handleBlur}
        placeholder="e.g. Babbi's MacBook"
      />
    </div>
    
    <div class="detail-row">
      <span class="detail-label">Persistent UUID</span>
      <div class="uuid-wrapper">
        <span class="detail-value mono">{uuid}</span>
        <button class="btn btn-xs btn-outline" onclick={() => { navigator.clipboard.writeText(uuid); onToast("UUID copied to clipboard!", "success"); }}>Copy</button>
      </div>
    </div>
    
    <div class="detail-row">
      <span class="detail-label">App Version</span>
      <span class="detail-value mono">v1.0.0</span>
    </div>
  </div>

  <div class="actions-row">
    <button class="btn btn-sm btn-outline" onclick={() => openUrl("https://github.com/Basithmd024/janus/releases")}>View Changelog</button>
  </div>
</div>

<style>
  .settings-section {
    background: var(--bg-elevated, rgba(30, 41, 59, 0.5));
    border: 1px solid var(--border-subtle, rgba(255, 255, 255, 0.08));
    border-radius: 16px;
    padding: 1.25rem;
    backdrop-filter: blur(12px);
  }

  .section-title {
    margin: 0 0 1rem 0;
    font-size: 0.95rem;
    font-weight: 700;
    display: flex;
    align-items: center;
    gap: 0.4rem;
    color: var(--text-primary, #f8fafc);
  }

  .detail-grid {
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
  }

  .detail-row {
    display: flex;
    align-items: center;
    font-size: 0.85rem;
  }

  .detail-label {
    color: var(--text-muted, rgba(255, 255, 255, 0.6));
    width: 140px;
    flex-shrink: 0;
    font-weight: 500;
  }

  .profile-input {
    background: rgba(0, 0, 0, 0.25);
    border: 1px solid var(--border-subtle, rgba(255, 255, 255, 0.12));
    border-radius: 8px;
    padding: 6px 12px;
    font-size: 0.85rem;
    color: var(--text-primary, #ffffff);
    outline: none;
    transition: all 0.2s ease;
    width: 260px;
  }

  .profile-input:focus {
    border-color: var(--accent-color, #3b82f6);
    background: rgba(0, 0, 0, 0.4);
  }

  .uuid-wrapper {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .detail-value {
    color: var(--text-secondary, rgba(255, 255, 255, 0.8));
    word-break: break-all;
  }

  .detail-value.mono {
    font-family: "SF Mono", "Fira Code", monospace;
    font-size: 0.78rem;
    color: var(--text-primary, #ffffff);
  }

  .actions-row {
    margin-top: 1.25rem;
    display: flex;
    gap: 10px;
    justify-content: flex-end;
  }

  .btn {
    border: none;
    border-radius: 8px;
    padding: 7px 14px;
    font-size: 0.8rem;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.2s ease;
  }

  .btn-xs {
    padding: 3px 8px;
    font-size: 0.72rem;
  }

  .btn-sm {
    padding: 7px 14px;
    font-size: 0.8rem;
  }

  .btn-primary {
    background: #2563eb;
    color: #ffffff;
  }

  .btn-primary:hover {
    background: #1d4ed8;
  }

  .btn-outline {
    background: transparent;
    border: 1px solid var(--border-subtle, rgba(255, 255, 255, 0.2));
    color: var(--text-primary, #ffffff);
  }

  .btn-outline:hover {
    background: rgba(255, 255, 255, 0.08);
  }
</style>
