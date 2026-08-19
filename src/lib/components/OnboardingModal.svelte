<script lang="ts">
  interface Props {
    show: boolean;
    onComplete: (username: string, deviceName: string) => void;
  }

  let { show, onComplete }: Props = $props();

  let inputUsername = $state("");

  let suggestedDeviceName = $derived(
    inputUsername.trim() ? `${inputUsername.trim()}'s MacBook` : "Janus MacBook"
  );

  function handleSave() {
    if (!inputUsername.trim()) return;
    onComplete(inputUsername.trim(), suggestedDeviceName);
  }
</script>

{#if show}
  <div class="modal-overlay" style="z-index: 9999; backdrop-filter: blur(12px); background: rgba(0, 0, 0, 0.75);">
    <div class="modal-card" style="max-width: 440px; padding: 2rem; border-radius: 20px; border: 1px solid var(--border-subtle); box-shadow: 0 20px 50px rgba(0,0,0,0.5);">
      <div style="text-align: center; margin-bottom: 1.5rem;">
        <div style="width: 56px; height: 56px; border-radius: 16px; background: rgba(255,255,255,0.06); display: inline-flex; align-items: center; justify-content: center; margin-bottom: 1rem; color: var(--accent-color, #3b82f6);">
          <svg width="28" height="28" viewBox="0 0 24 24" fill="none"><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2" stroke="currentColor" stroke-width="1.8"/><circle cx="12" cy="7" r="4" stroke="currentColor" stroke-width="1.8"/></svg>
        </div>
        <h2 style="font-size: 1.4rem; font-weight: 700; color: var(--text-primary); margin-bottom: 0.4rem;">Welcome to Janus Bridge</h2>
        <p style="font-size: 0.82rem; color: var(--text-muted); line-height: 1.4;">
          Set your username to identify your MacBook when wirelessly pairing with your phone.
        </p>
      </div>

      <form onsubmit={(e) => { e.preventDefault(); handleSave(); }} style="display: flex; flex-direction: column; gap: 14px;">
        <div>
          <label for="onboarding-username" style="display: block; font-size: 0.75rem; font-weight: 600; color: var(--text-secondary); margin-bottom: 6px;">Your Username</label>
          <input
            id="onboarding-username"
            type="text"
            placeholder="e.g. Babbi"
            bind:value={inputUsername}
            style="width: 100%; background: var(--bg-elevated); border: 1px solid var(--border-subtle); border-radius: 10px; padding: 10px 14px; font-size: 0.9rem; color: var(--text-primary); outline: none;"
            required
          />
        </div>

        <div style="background: rgba(255,255,255,0.03); border: 1px dashed var(--border-subtle); border-radius: 10px; padding: 10px 14px;">
          <span style="display: block; font-size: 0.7rem; color: var(--text-muted); font-weight: 500;">Default Device Name</span>
          <span style="display: block; font-size: 0.85rem; color: var(--text-primary); font-weight: 600; margin-top: 2px;">{suggestedDeviceName}</span>
        </div>

        <button
          type="submit"
          class="btn btn-primary"
          disabled={!inputUsername.trim()}
          style="width: 100%; padding: 11px; margin-top: 6px; font-weight: 600; font-size: 0.9rem; border-radius: 10px;"
        >
          Get Started
        </button>
      </form>
    </div>
  </div>
{/if}
