// ReClip Twitter / X Content Script v1.2.0

function processTwitterTweets() {
  const articles = document.querySelectorAll('article[data-testid="tweet"]');
  
  articles.forEach(article => {
    if (article.querySelector('.reclip-x-btn') || article.classList.contains('reclip-x-done')) return;
    
    // Check if tweet has media (video or photo)
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

      const span = btn.querySelector('span');
      const originalText = span ? span.innerText : 'ReClip';
      if (span) span.innerText = 'Downloading...';

      chrome.runtime.sendMessage({ action: 'download', url: tweetUrl }, (response) => {
        if (response && response.success) {
          if (span) span.innerText = 'Started!';
          setTimeout(() => { if (span) span.innerText = originalText; }, 3000);
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
