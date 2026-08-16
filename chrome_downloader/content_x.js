function injectButtonX() {
  const articles = document.querySelectorAll('article:not(.reclip-injected)');
  
  articles.forEach(article => {
    // Basic check to see if article has video/media
    const hasMedia = article.querySelector('video') || article.querySelector('[data-testid="videoComponent"]');
    if (!hasMedia) return;

    article.classList.add('reclip-injected');
    
    // Find share button row to inject our button
    const actionRow = article.querySelector('[role="group"]');
    if (!actionRow) return;

    // Create container
    const container = document.createElement('div');
    container.className = 'reclip-x-btn-container';
    container.style.display = 'flex';
    container.style.alignItems = 'center';
    container.style.justifyContent = 'center';
    container.style.padding = '0 12px';
    
    // Create button
    const btn = document.createElement('button');
    btn.className = 'reclip-download-btn-x';
    btn.innerHTML = '⬇️';
    btn.title = 'Download with ReClip';
    btn.style.background = 'transparent';
    btn.style.border = 'none';
    btn.style.cursor = 'pointer';
    btn.style.fontSize = '16px';
    
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      
      // Get the tweet link
      const timeLink = article.querySelector('a[href*="/status/"]');
      if (!timeLink) return;
      
      let url = timeLink.href;
      
      const originalText = btn.innerHTML;
      btn.innerHTML = '⏳';
      
      chrome.runtime.sendMessage({ action: 'download', url: url }, (response) => {
        if (response && response.success) {
          btn.innerHTML = '✅';
          setTimeout(() => btn.innerHTML = originalText, 3000);
        } else {
          btn.innerHTML = '❌';
          alert('ReClip Error: ' + (response?.error || 'Ensure ReClip app/server is running!'));
          setTimeout(() => btn.innerHTML = originalText, 3000);
        }
      });
    });
    
    container.appendChild(btn);
    actionRow.appendChild(container);
  });
}

const observer = new MutationObserver(() => injectButtonX());
observer.observe(document.body, { childList: true, subtree: true });

setTimeout(injectButtonX, 2000);
