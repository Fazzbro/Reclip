// ReClip Twitter / X Content Script & In-Page HUD v1.2.0

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
      <div class="reclip-card-title">${data.title || data.filename || 'Downloading Media...'}</div>
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

    card.querySelector('.reclip-card-close').addEventListener('click', () => {
      card.classList.add('reclip-card-fadeout');
      setTimeout(() => card.remove(), 300);
    });

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

  if (data.filename && (!data.title || data.title === 'Downloading Media...')) {
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

// Update tweet action buttons on progress
function updateTweetProgress(data) {
  if (!data.url) return;
  const buttons = document.querySelectorAll(`[data-reclip-url="${CSS.escape(data.url)}"]`);
  buttons.forEach(btn => {
    const span = btn.querySelector('span');
    if (!span) return;
    if (data.status === 'downloading') {
      const pct = (data.percent || 0).toFixed(0);
      span.innerText = `${pct}%`;
    } else if (data.status === 'done') {
      span.innerText = '✅ Done';
      setTimeout(() => { span.innerText = 'ReClip'; }, 4000);
    } else if (data.status === 'error') {
      span.innerText = '❌ Failed';
      setTimeout(() => { span.innerText = 'ReClip'; }, 4000);
    }
  });
}

chrome.runtime.onMessage.addListener((msg) => {
  if (msg.type === 'RECLIP_JOB_UPDATE' && msg.data) {
    updateHudCard(msg.data);
    updateTweetProgress(msg.data);
  }
});

function processTwitterTweets() {
  const articles = document.querySelectorAll('article[data-testid="tweet"]');
  
  articles.forEach(article => {
    if (article.querySelector('.reclip-x-btn') || article.classList.contains('reclip-x-done')) return;
    
    const hasMedia = article.querySelector('video, [data-testid="videoComponent"], [data-testid="videoPlayer"], [data-testid="tweetPhoto"]');
    if (!hasMedia) return;

    const actionRow = article.querySelector('[role="group"]');
    if (!actionRow) return;

    article.classList.add('reclip-x-done');

    const container = document.createElement('div');
    container.className = 'reclip-x-btn-container';
    container.style.cssText = 'display:inline-flex; align-items:center; justify-content:center; padding:0 4px;';
    
    const btn = document.createElement('div');
    btn.className = 'reclip-x-btn';
    btn.setAttribute('role', 'button');
    btn.setAttribute('tabindex', '0');
    btn.title = 'Download with ReClip';
    btn.innerHTML = `
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="display:inline-block; vertical-align:middle; margin-right:3px;">
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

      const timeLink = article.querySelector('a[href*="/status/"]');
      const tweetUrl = timeLink ? (timeLink.href.startsWith('http') ? timeLink.href : 'https://x.com' + timeLink.getAttribute('href')) : window.location.href;

      btn.setAttribute('data-reclip-url', tweetUrl);

      const span = btn.querySelector('span');
      const originalText = span ? span.innerText : 'ReClip';
      if (span) span.innerText = 'Starting...';

      // Initial temporary toast
      const tempJobId = 'temp_' + Date.now();
      updateHudCard({
        job_id: tempJobId,
        url: tweetUrl,
        title: 'Downloading Twitter Video...',
        percent: 5,
        progress_str: 'Sending download request...',
        status: 'downloading'
      });

      chrome.runtime.sendMessage({ action: 'download', url: tweetUrl }, (response) => {
        const tempCard = document.getElementById(`reclip-card-${tempJobId}`);
        if (tempCard) tempCard.remove();

        if (response && response.success) {
          if (span) span.innerText = 'Downloading...';
        } else {
          if (span) span.innerText = 'Failed';
          alert('ReClip Error: ' + (response?.error || 'Make sure ReClip desktop app is open on port 8899!'));
          setTimeout(() => { if (span) span.innerText = originalText; }, 3000);
        }
      });
    }, true);

    container.appendChild(btn);
    actionRow.appendChild(container);
  });
}

const observer = new MutationObserver(() => processTwitterTweets());
observer.observe(document.documentElement || document.body, { childList: true, subtree: true });

setInterval(processTwitterTweets, 1200);
processTwitterTweets();
