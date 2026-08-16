// ReClip YouTube Content Script v1.2.0

function extractVideoUrl(container) {
  // 1. Direct anchor with watch/shorts
  if (container.tagName === 'A') {
    const h = container.getAttribute('href') || container.href;
    if (h && (h.includes('/watch') || h.includes('/shorts/'))) {
      return h.startsWith('http') ? h : 'https://www.youtube.com' + h;
    }
  }

  // 2. Look for inner anchor
  const inner = container.querySelector('a[href*="/watch"], a[href*="/shorts/"]');
  if (inner) {
    const h = inner.getAttribute('href') || inner.href;
    if (h) return h.startsWith('http') ? h : 'https://www.youtube.com' + h;
  }

  // 3. Look in parent card / view-model
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

function attachButtonToContainer(container) {
  if (container.querySelector('.reclip-yt-btn') || container.classList.contains('reclip-injected-done')) {
    return;
  }
  container.classList.add('reclip-injected-done');

  // Ensure positioning context
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

    const span = btn.querySelector('span');
    const originalText = span ? span.innerText : 'ReClip';
    if (span) span.innerText = 'Downloading...';
    btn.style.opacity = '0.7';

    chrome.runtime.sendMessage({ action: 'download', url: videoUrl }, (response) => {
      btn.style.opacity = '1';
      if (response && response.success) {
        if (span) span.innerText = 'Started!';
        btn.classList.add('reclip-success');
        setTimeout(() => {
          if (span) span.innerText = originalText;
          btn.classList.remove('reclip-success');
        }, 3000);
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

function processAllYouTubeThumbnails() {
  // Strategy 1: Target modern 2024-2026 YouTube ViewModels
  const viewModels = document.querySelectorAll('yt-lockup-view-model, yt-thumbnail-view-model, .yt-lockup-view-model__image');
  viewModels.forEach(el => attachButtonToContainer(el));

  // Strategy 2: Target classic thumbnail containers
  const classicThumbs = document.querySelectorAll('ytd-thumbnail, a#thumbnail, a.ytd-thumbnail');
  classicThumbs.forEach(el => attachButtonToContainer(el));

  // Strategy 3: Target any anchor wrapping video thumbnails
  const anchorThumbs = document.querySelectorAll('a[href*="/watch?v="], a[href*="/shorts/"]');
  anchorThumbs.forEach(a => {
    if (a.querySelector('img, yt-image, picture, .yt-core-image')) {
      attachButtonToContainer(a);
    }
  });

  // Strategy 4: YouTube Watch Page action bar (below active video)
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

        const span = watchBtn.querySelector('span');
        const originalText = span ? span.innerText : 'ReClip';
        if (span) span.innerText = 'Downloading...';

        chrome.runtime.sendMessage({ action: 'download', url: window.location.href }, (response) => {
          if (response && response.success) {
            if (span) span.innerText = 'Started in ReClip!';
            setTimeout(() => { if (span) span.innerText = originalText; }, 3000);
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

// Attach observers & listeners
const observer = new MutationObserver(() => processAllYouTubeThumbnails());
observer.observe(document.documentElement || document.body, { childList: true, subtree: true });

window.addEventListener('yt-navigate-finish', processAllYouTubeThumbnails);
window.addEventListener('load', processAllYouTubeThumbnails);
document.addEventListener('DOMContentLoaded', processAllYouTubeThumbnails);

setInterval(processAllYouTubeThumbnails, 1000);
processAllYouTubeThumbnails();
