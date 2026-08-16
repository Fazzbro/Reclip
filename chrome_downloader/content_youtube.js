// ReClip YouTube Content Script & In-Page HUD v1.2.0

// In-Page HUD Toast Manager
function getOrCreateHudContainer() {
  let hud = document.getElementById('reclip-hud-container');
  if (!hud) {
    hud = document.createElement('div');
    hud.id = 'reclip-hud-container';
    hud.className = 'reclip-hud-container';
    document.body.appendChild(hud);
  }
  return hud;
}

function updateHudCard(data) {
  const hud = getOrCreateHudContainer();
  const cardId = `reclip-card-${data.job_id}`;
  let card = document.getElementById(cardId);

  if (!card) {
    card = document.createElement('div');
    card.id = cardId;
    card.className = 'reclip-hud-card';
    card.innerHTML = `
      <div class="reclip-card-header">
        <div class="reclip-card-brand">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#e04a32" stroke-width="2.5">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
            <polyline points="7 10 12 15 17 10"></polyline>
            <line x1="12" y1="15" x2="12" y2="3"></line>
          </svg>
          <span>ReClip Downloader</span>
        </div>
        <button class="reclip-card-close" title="Dismiss">✕</button>
      </div>
      <div class="reclip-card-title">${data.title || data.filename || 'Downloading Video...'}</div>
      <div class="reclip-card-bar-bg">
        <div class="reclip-card-bar-fill" style="width: 0%;"></div>
      </div>
      <div class="reclip-card-footer">
        <span class="reclip-card-status">Connecting to ReClip...</span>
        <span class="reclip-card-pct">0%</span>
      </div>
      <div class="reclip-card-actions" style="display: none;">
        <button class="reclip-card-open-btn">📂 Open Downloads Folder</button>
      </div>
    `;

    // Close button
    card.querySelector('.reclip-card-close').addEventListener('click', () => {
      card.classList.add('reclip-card-fadeout');
      setTimeout(() => card.remove(), 300);
    });

    // Open folder button
    card.querySelector('.reclip-card-open-btn').addEventListener('click', () => {
      chrome.runtime.sendMessage({ action: 'open_downloads' });
    });

    hud.appendChild(card);
  }

  const fill = card.querySelector('.reclip-card-bar-fill');
  const statusSpan = card.querySelector('.reclip-card-status');
  const pctSpan = card.querySelector('.reclip-card-pct');
  const titleDiv = card.querySelector('.reclip-card-title');
  const actionsDiv = card.querySelector('.reclip-card-actions');

  if (data.filename && (!data.title || data.title === 'Downloading Video...')) {
    titleDiv.innerText = data.filename;
  }

  const pct = Math.min(100, Math.max(0, data.percent || 0));
  fill.style.width = `${pct}%`;
  pctSpan.innerText = `${pct.toFixed(0)}%`;

  if (data.status === 'downloading') {
    statusSpan.innerText = data.progress_str || 'Downloading...';
    card.classList.remove('reclip-card-success', 'reclip-card-error');
  } else if (data.status === 'done') {
    fill.style.width = '100%';
    pctSpan.innerText = '100%';
    statusSpan.innerText = '✅ Download Complete!';
    card.classList.add('reclip-card-success');
    actionsDiv.style.display = 'block';

    // Auto dismiss after 10 seconds if not clicked
    setTimeout(() => {
      if (document.body.contains(card)) {
        card.classList.add('reclip-card-fadeout');
        setTimeout(() => card.remove(), 300);
      }
    }, 10000);
  } else if (data.status === 'error') {
    statusSpan.innerText = `❌ Error: ${data.error || 'Failed'}`;
    card.classList.add('reclip-card-error');
    pctSpan.innerText = 'Failed';
  }
}

// Update thumbnail button on progress
function updateThumbnailProgress(data) {
  if (!data.url) return;
  const buttons = document.querySelectorAll(`[data-reclip-url="${CSS.escape(data.url)}"]`);
  buttons.forEach(btn => {
    const span = btn.querySelector('span');
    if (!span) return;
    if (data.status === 'downloading') {
      const pct = (data.percent || 0).toFixed(0);
      span.innerText = `${pct}%`;
      btn.style.opacity = '0.9';
    } else if (data.status === 'done') {
      span.innerText = '✅ Done';
      btn.classList.add('reclip-success');
      setTimeout(() => {
        span.innerText = 'ReClip';
        btn.classList.remove('reclip-success');
      }, 4000);
    } else if (data.status === 'error') {
      span.innerText = '❌ Failed';
      btn.classList.add('reclip-error');
      setTimeout(() => {
        span.innerText = 'ReClip';
        btn.classList.remove('reclip-error');
      }, 4000);
    }
  });
}

// Listen to broadcast messages from background service worker
chrome.runtime.onMessage.addListener((msg) => {
  if (msg.type === 'RECLIP_JOB_UPDATE' && msg.data) {
    updateHudCard(msg.data);
    updateThumbnailProgress(msg.data);
  }
});

// Helper to extract YouTube video URL
function extractVideoUrl(container) {
  if (container.tagName === 'A') {
    const h = container.getAttribute('href') || container.href;
    if (h && (h.includes('/watch') || h.includes('/shorts/'))) {
      return h.startsWith('http') ? h : 'https://www.youtube.com' + h;
    }
  }

  const inner = container.querySelector('a[href*="/watch"], a[href*="/shorts/"]');
  if (inner) {
    const h = inner.getAttribute('href') || inner.href;
    if (h) return h.startsWith('http') ? h : 'https://www.youtube.com' + h;
  }

  const parentCard = container.closest('yt-lockup-view-model, ytd-rich-item-renderer, ytd-video-renderer, ytd-compact-video-renderer, ytd-grid-video-renderer, ytd-reel-item-renderer, ytd-rich-grid-media');
  if (parentCard) {
    const cardLink = parentCard.querySelector('a#video-title-link, a#video-title, a.yt-lockup-view-model__content-image, a[href*="/watch"], a[href*="/shorts/"]');
    if (cardLink) {
      const h = cardLink.getAttribute('href') || cardLink.href;
      if (h) return h.startsWith('http') ? h : 'https://www.youtube.com' + h;
    }
  }

  return null;
}

// Attach ReClip button to thumbnail
function attachButtonToContainer(container) {
  if (container.querySelector('.reclip-yt-btn') || container.classList.contains('reclip-injected-done')) {
    return;
  }
  container.classList.add('reclip-injected-done');

  try {
    const pos = window.getComputedStyle(container).position;
    if (!pos || pos === 'static') {
      container.style.setProperty('position', 'relative', 'important');
    }
  } catch (e) {
    container.style.position = 'relative';
  }

  const btn = document.createElement('div');
  btn.className = 'reclip-yt-btn';
  btn.setAttribute('role', 'button');
  btn.setAttribute('tabindex', '0');
  btn.title = 'Download with ReClip';
  btn.innerHTML = `
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="display:inline-block; vertical-align:middle; margin-right:3px;">
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
      <polyline points="7 10 12 15 17 10"></polyline>
      <line x1="12" y1="15" x2="12" y2="3"></line>
    </svg>
    <span>ReClip</span>
  `;

  const triggerDownload = (e) => {
    e.preventDefault();
    e.stopPropagation();
    e.stopImmediatePropagation();

    const videoUrl = extractVideoUrl(container);
    if (!videoUrl) {
      alert('ReClip: Could not detect video link from this thumbnail.');
      return;
    }

    btn.setAttribute('data-reclip-url', videoUrl);

    const span = btn.querySelector('span');
    const originalText = span ? span.innerText : 'ReClip';
    if (span) span.innerText = 'Starting...';
    btn.style.opacity = '0.7';

    // Show initial in-page HUD immediately
    const tempJobId = 'temp_' + Date.now();
    updateHudCard({
      job_id: tempJobId,
      url: videoUrl,
      title: 'Connecting to ReClip backend...',
      percent: 5,
      progress_str: 'Sending download request...',
      status: 'downloading'
    });

    chrome.runtime.sendMessage({ action: 'download', url: videoUrl }, (response) => {
      btn.style.opacity = '1';
      const tempCard = document.getElementById(`reclip-card-${tempJobId}`);
      if (tempCard) tempCard.remove();

      if (response && response.success) {
        if (span) span.innerText = 'Downloading...';
        btn.classList.add('reclip-active-dl');
      } else {
        if (span) span.innerText = 'Failed';
        btn.classList.add('reclip-error');
        alert('ReClip Error: ' + (response?.error || 'Make sure ReClip desktop app is open on port 8899!'));
        setTimeout(() => {
          if (span) span.innerText = originalText;
          btn.classList.remove('reclip-error');
        }, 3000);
      }
    });
  };

  btn.addEventListener('click', triggerDownload, true);
  btn.addEventListener('mousedown', (e) => { e.stopPropagation(); }, true);
  btn.addEventListener('mouseup', (e) => { e.stopPropagation(); }, true);

  container.appendChild(btn);
}

// Process all thumbnails across viewports
function processAllYouTubeThumbnails() {
  const viewModels = document.querySelectorAll('yt-lockup-view-model, yt-thumbnail-view-model, .yt-lockup-view-model__image');
  viewModels.forEach(el => attachButtonToContainer(el));

  const classicThumbs = document.querySelectorAll('ytd-thumbnail, a#thumbnail, a.ytd-thumbnail');
  classicThumbs.forEach(el => attachButtonToContainer(el));

  const anchorThumbs = document.querySelectorAll('a[href*="/watch?v="], a[href*="/shorts/"]');
  anchorThumbs.forEach(a => {
    if (a.querySelector('img, yt-image, picture, .yt-core-image')) {
      attachButtonToContainer(a);
    }
  });

  // Watch page action bar
  if (window.location.pathname.startsWith('/watch')) {
    const actionBars = document.querySelectorAll('#top-level-buttons-computed, #actions ytd-menu-renderer #top-level-buttons-computed, ytd-watch-metadata #actions');
    actionBars.forEach(bar => {
      if (bar.querySelector('.reclip-yt-watch-btn') || bar.classList.contains('reclip-watch-processed')) return;
      bar.classList.add('reclip-watch-processed');

      const watchBtn = document.createElement('div');
      watchBtn.className = 'reclip-yt-watch-btn';
      watchBtn.setAttribute('role', 'button');
      watchBtn.setAttribute('tabindex', '0');
      watchBtn.title = 'Download current video with ReClip';
      watchBtn.innerHTML = `
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="margin-right:6px; display:inline-block; vertical-align:middle;">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
          <polyline points="7 10 12 15 17 10"></polyline>
          <line x1="12" y1="15" x2="12" y2="3"></line>
        </svg>
        <span>ReClip</span>
      `;

      watchBtn.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();

        const currentUrl = window.location.href;
        watchBtn.setAttribute('data-reclip-url', currentUrl);

        const span = watchBtn.querySelector('span');
        const originalText = span ? span.innerText : 'ReClip';
        if (span) span.innerText = 'Starting...';

        chrome.runtime.sendMessage({ action: 'download', url: currentUrl }, (response) => {
          if (response && response.success) {
            if (span) span.innerText = 'Downloading...';
          } else {
            if (span) span.innerText = 'Failed';
            alert('ReClip Error: ' + (response?.error || 'Make sure ReClip is running on port 8899!'));
            setTimeout(() => { if (span) span.innerText = originalText; }, 3000);
          }
        });
      }, true);

      bar.prepend(watchBtn);
    });
  }
}

// Observers & events
const observer = new MutationObserver(() => processAllYouTubeThumbnails());
observer.observe(document.documentElement || document.body, { childList: true, subtree: true });

window.addEventListener('yt-navigate-finish', processAllYouTubeThumbnails);
window.addEventListener('load', processAllYouTubeThumbnails);
document.addEventListener('DOMContentLoaded', processAllYouTubeThumbnails);

setInterval(processAllYouTubeThumbnails, 1000);
processAllYouTubeThumbnails();
