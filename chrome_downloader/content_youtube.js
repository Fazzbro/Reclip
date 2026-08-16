function createThumbnailButton(getUrlFn) {
  const btn = document.createElement('button');
  btn.className = 'reclip-yt-btn';
  btn.title = 'Download with ReClip';
  btn.innerHTML = `
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="display:inline-block; vertical-align:middle; margin-right:4px;">
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
      <polyline points="7 10 12 15 17 10"></polyline>
      <line x1="12" y1="15" x2="12" y2="3"></line>
    </svg>
    <span>ReClip</span>
  `;

  btn.addEventListener('click', (e) => {
    e.preventDefault();
    e.stopPropagation();
    e.stopImmediatePropagation();

    const rawUrl = typeof getUrlFn === 'function' ? getUrlFn() : getUrlFn;
    if (!rawUrl) {
      alert('Could not detect video URL.');
      return;
    }

    const fullUrl = rawUrl.startsWith('http') ? rawUrl : 'https://www.youtube.com' + rawUrl;

    const span = btn.querySelector('span');
    const originalText = span ? span.innerText : 'ReClip';
    if (span) span.innerText = 'Downloading...';
    btn.style.opacity = '0.7';

    chrome.runtime.sendMessage({ action: 'download', url: fullUrl }, (response) => {
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
        alert('ReClip Error: ' + (response?.error || 'Make sure the ReClip application is running on port 8899!'));
        setTimeout(() => {
          if (span) span.innerText = originalText;
          btn.classList.remove('reclip-error');
        }, 3000);
      }
    });
  });

  return btn;
}

function injectYouTubeButtons() {
  // 1. Inject into standard thumbnails across Home, Search, Channel, Subscriptions, Sidebar
  const thumbnailAnchors = document.querySelectorAll('a#thumbnail:not(.reclip-injected), a.ytd-thumbnail:not(.reclip-injected)');
  
  thumbnailAnchors.forEach(a => {
    a.classList.add('reclip-injected');
    a.style.position = 'relative';

    const getUrl = () => {
      if (a.href && a.href.includes('/watch')) return a.href;
      if (a.href && a.href.includes('/shorts/')) return a.href;
      // Fallback: look at parent container for title link
      const container = a.closest('ytd-rich-item-renderer, ytd-video-renderer, ytd-compact-video-renderer, ytd-grid-video-renderer, ytd-reel-item-renderer');
      if (container) {
        const titleLink = container.querySelector('a#video-title-link, a#video-title, a[href*="/watch"], a[href*="/shorts/"]');
        if (titleLink && titleLink.href) return titleLink.href;
      }
      return a.getAttribute('href') || a.href;
    };

    const btn = createThumbnailButton(getUrl);
    a.appendChild(btn);
  });

  // 2. Inject on YouTube Watch Page action bar (next to Like / Share)
  if (window.location.pathname.startsWith('/watch')) {
    const actionBars = document.querySelectorAll('#top-level-buttons-computed:not(.reclip-watch-injected), #actions ytd-menu-renderer #top-level-buttons-computed:not(.reclip-watch-injected)');
    
    actionBars.forEach(bar => {
      bar.classList.add('reclip-watch-injected');

      const watchBtn = document.createElement('button');
      watchBtn.className = 'reclip-yt-watch-btn';
      watchBtn.title = 'Download video with ReClip';
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
      });

      bar.prepend(watchBtn);
    });
  }
}

// Observe DOM updates for dynamic/infinite scroll loading
const observer = new MutationObserver(() => injectYouTubeButtons());
observer.observe(document.body || document.documentElement, { childList: true, subtree: true });

// Listen to YouTube SPA navigation events
window.addEventListener('yt-navigate-finish', injectYouTubeButtons);
window.addEventListener('load', injectYouTubeButtons);

// Regular periodic check
setInterval(injectYouTubeButtons, 1200);
setTimeout(injectYouTubeButtons, 500);
