<script lang="ts">
  import { auth, signInWithGoogle, signInInstantCloud } from '../firebase';

  let { onSkip = () => {} } = $props<{ onSkip?: () => void }>();

  let isLoading = $state(false);
  let error = $state<string | null>(null);

  async function handleGoogleSignIn() {
    isLoading = true;
    error = null;
    try {
      await signInWithGoogle();
    } catch (e: any) {
      console.warn('Google Sign-In error, falling back to Instant Cloud:', e);
      try {
        await signInInstantCloud();
      } catch (err: any) {
        error = e.message || 'Google Sign-In failed. Please try again.';
      }
    } finally {
      isLoading = false;
    }
  }

  async function handleInstantCloud() {
    isLoading = true;
    error = null;
    try {
      await signInInstantCloud();
    } catch (e: any) {
      error = e.message || 'Instant connection failed.';
    } finally {
      isLoading = false;
    }
  }
</script>

<div class="login-container">
  <div class="glass-card">
    
    <!-- App Logo & Branding -->
    <div class="header">
      <div class="logo-mark">
        <svg width="32" height="32" viewBox="0 0 24 24" fill="none">
          <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" stroke="#a78bfa" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </div>
      <h1>Janus Bridge</h1>
      <p class="subtitle">Cross-Platform macOS ↔ Android Ecosystem</p>
    </div>

    <!-- Error Alert if any -->
    {#if error}
      <div class="error-message">
        <svg class="err-icon" width="16" height="16" viewBox="0 0 24 24" fill="none">
          <circle cx="12" cy="12" r="10" stroke="#f87171" stroke-width="1.8"/>
          <path d="M12 8v4M12 16v.5" stroke="#f87171" stroke-width="2" stroke-linecap="round"/>
        </svg>
        <span>{error}</span>
      </div>
    {/if}

    <!-- 1-Tap Cloud Buttons -->
    <div class="auth-buttons-stack">
      <!-- 1-Tap Google Sign-In Button -->
      <button type="button" class="google-btn" onclick={handleGoogleSignIn} disabled={isLoading}>
        <svg class="google-icon" width="20" height="20" viewBox="0 0 24 24">
          <path fill="#EA4335" d="M12 5c1.6 0 3 .6 4.1 1.7l3.1-3.1C17.3 1.8 14.8 1 12 1 7.5 1 3.7 3.6 1.9 7.3l3.7 2.9C6.5 7.4 9 5 12 5z"/>
          <path fill="#4285F4" d="M23.5 12.3c0-.8-.1-1.6-.2-2.3H12v4.6h6.5c-.3 1.5-1.1 2.8-2.4 3.7l3.7 2.9c2.2-2 3.7-5 3.7-8.9z"/>
          <path fill="#FBBC05" d="M5.6 14.8c-.2-.7-.4-1.5-.4-2.8s.2-2.1.4-2.8L1.9 6.3C.7 8.7 0 10.8 0 12s.7 3.3 1.9 5.7l3.7-2.9z"/>
          <path fill="#34A853" d="M12 23c3.2 0 6-1.1 8-3l-3.7-2.9c-1.1.7-2.5 1.2-4.3 1.2-3 0-5.5-2.4-6.4-5.2L1.9 16C3.7 19.7 7.5 23 12 23z"/>
        </svg>
        <span>Continue with Google</span>
      </button>

      <!-- 1-Tap Instant Cloud Guest Bridge -->
      <button type="button" class="instant-cloud-btn" onclick={handleInstantCloud} disabled={isLoading}>
        {#if isLoading}
          <div class="spinner"></div>
          <span>Connecting to Cloud Bridge...</span>
        {:else}
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
            <path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <span>Instant 1-Tap Cloud Link</span>
        {/if}
      </button>
    </div>

    <!-- Divider -->
    <div class="divider">
      <span>OR USE DIRECT LOCAL WI-FI</span>
    </div>

    <!-- Local Wi-Fi Offline Mode Button -->
    <button type="button" class="local-mode-btn" onclick={onSkip}>
      <div class="local-btn-content">
        <div class="local-icon">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M5 12.55a11 11 0 0114.08 0M1.42 9a16 16 0 0121.16 0M8.53 16.11a6 6 0 016.95 0M12 20h.01" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="local-text">
          <span class="local-title">Continue in Local Wi-Fi Mode</span>
          <span class="local-desc">Direct P2P TLS mesh. Zero cloud required.</span>
        </div>
      </div>
      <svg class="arrow-icon" width="16" height="16" viewBox="0 0 24 24" fill="none">
        <path d="M9 18l6-6-6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
    </button>

  </div>
</div>

<style>
  @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap');

  :global(body) {
    background: #09080f;
    font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
    color: #f0ecf7;
    margin: 0;
    padding: 0;
  }

  .login-container {
    display: flex;
    justify-content: center;
    align-items: center;
    min-height: 100vh;
    padding: 24px;
    box-sizing: border-box;
    background: 
      radial-gradient(ellipse at top left, rgba(192, 132, 252, 0.14) 0%, transparent 60%),
      radial-gradient(ellipse at bottom right, rgba(129, 140, 248, 0.12) 0%, transparent 60%),
      #09080f;
  }

  .glass-card {
    background: rgba(16, 14, 28, 0.88);
    backdrop-filter: blur(28px);
    -webkit-backdrop-filter: blur(28px);
    border: 1px solid rgba(255, 255, 255, 0.1);
    border-radius: 28px;
    padding: 44px 38px;
    width: 100%;
    max-width: 440px;
    box-shadow: 0 28px 64px rgba(0, 0, 0, 0.65), 0 0 40px rgba(167, 139, 250, 0.1);
    text-align: center;
    animation: fadeIn 0.4s cubic-bezier(0.16, 1, 0.3, 1);
  }

  @keyframes fadeIn {
    from { opacity: 0; transform: translateY(16px); }
    to { opacity: 1; transform: translateY(0); }
  }

  .header {
    margin-bottom: 28px;
  }

  .logo-mark {
    display: inline-flex;
    padding: 12px;
    background: rgba(167, 139, 250, 0.12);
    border: 1px solid rgba(167, 139, 250, 0.3);
    border-radius: 20px;
    margin-bottom: 14px;
    box-shadow: 0 4px 20px rgba(167, 139, 250, 0.2);
  }

  h1 {
    font-size: 1.65rem;
    font-weight: 800;
    margin: 0 0 6px 0;
    color: #f0ecf7;
    letter-spacing: -0.02em;
  }

  .subtitle {
    font-size: 0.85rem;
    color: #9d94b8;
    margin: 0;
    font-weight: 500;
  }

  .auth-buttons-stack {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  /* 1-Tap Google Button */
  .google-btn {
    width: 100%;
    padding: 14px 16px;
    border-radius: 14px;
    border: 1px solid rgba(255, 255, 255, 0.2);
    background: #ffffff;
    color: #1f1f1f;
    font-size: 0.95rem;
    font-weight: 700;
    cursor: pointer;
    font-family: inherit;
    transition: all 0.2s ease;
    display: flex;
    justify-content: center;
    align-items: center;
    gap: 12px;
    box-shadow: 0 4px 18px rgba(0, 0, 0, 0.3);
  }

  .google-btn:hover:not(:disabled) {
    background: #f8f9fa;
    transform: translateY(-2px);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
  }

  .google-btn:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  /* Instant Cloud Button */
  .instant-cloud-btn {
    width: 100%;
    padding: 14px 16px;
    border-radius: 14px;
    border: 1px solid rgba(167, 139, 250, 0.35);
    background: linear-gradient(135deg, rgba(167, 139, 250, 0.2) 0%, rgba(129, 140, 248, 0.15) 100%);
    color: #c4b5fd;
    font-size: 0.95rem;
    font-weight: 700;
    cursor: pointer;
    font-family: inherit;
    transition: all 0.2s ease;
    display: flex;
    justify-content: center;
    align-items: center;
    gap: 10px;
  }

  .instant-cloud-btn:hover:not(:disabled) {
    background: linear-gradient(135deg, rgba(167, 139, 250, 0.3) 0%, rgba(129, 140, 248, 0.25) 100%);
    border-color: rgba(167, 139, 250, 0.5);
    color: #ffffff;
    transform: translateY(-2px);
  }

  .instant-cloud-btn:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  /* Divider */
  .divider {
    display: flex;
    align-items: center;
    text-align: center;
    margin: 24px 0 16px 0;
  }

  .divider::before,
  .divider::after {
    content: '';
    flex: 1;
    border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  }

  .divider span {
    padding: 0 10px;
    font-size: 0.68rem;
    font-weight: 700;
    letter-spacing: 0.08em;
    color: #6b6088;
  }

  /* Local Mode Card Button */
  .local-mode-btn {
    width: 100%;
    padding: 14px 16px;
    border-radius: 16px;
    border: 1px solid rgba(255, 255, 255, 0.08);
    background: rgba(255, 255, 255, 0.02);
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: space-between;
    text-align: left;
    transition: all 0.2s ease;
  }

  .local-mode-btn:hover {
    background: rgba(167, 139, 250, 0.08);
    border-color: rgba(167, 139, 250, 0.3);
    transform: translateY(-2px);
  }

  .local-btn-content {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .local-icon {
    width: 36px;
    height: 36px;
    border-radius: 10px;
    background: rgba(167, 139, 250, 0.15);
    border: 1px solid rgba(167, 139, 250, 0.3);
    color: #c4b5fd;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }

  .local-text {
    display: flex;
    flex-direction: column;
  }

  .local-title {
    font-size: 0.85rem;
    font-weight: 700;
    color: #f0ecf7;
  }

  .local-desc {
    font-size: 0.72rem;
    color: #8b82a8;
    margin-top: 1px;
  }

  .arrow-icon {
    color: #8b82a8;
    transition: transform 0.2s ease;
  }

  .local-mode-btn:hover .arrow-icon {
    color: #c4b5fd;
    transform: translateX(3px);
  }

  .error-message {
    background: rgba(239, 68, 68, 0.12);
    border: 1px solid rgba(239, 68, 68, 0.25);
    border-radius: 12px;
    padding: 10px 14px;
    font-size: 0.8rem;
    color: #fca5a5;
    margin-bottom: 16px;
    display: flex;
    align-items: flex-start;
    gap: 8px;
    line-height: 1.4;
    text-align: left;
  }

  .err-icon {
    flex-shrink: 0;
    margin-top: 2px;
  }

  .spinner {
    width: 16px;
    height: 16px;
    border: 2px solid rgba(196, 181, 253, 0.3);
    border-radius: 50%;
    border-top-color: #c4b5fd;
    animation: spin 0.8s linear infinite;
  }

  @keyframes spin {
    to { transform: rotate(360deg); }
  }
</style>
