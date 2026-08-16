function injectButton() {
  const thumbnails = document.querySelectorAll('ytd-thumbnail:not(.reclip-injected)');
  
  thumbnails.forEach(thumbnail => {
    thumbnail.classList.add('reclip-injected');
    
    const btn = document.createElement('button');
    btn.className = 'reclip-download-btn';
    btn.innerHTML = '⬇️';
    btn.title = 'Download with ReClip';
    
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      
      const a = thumbnail.querySelector('a#thumbnail');
      if (!a || !a.href) return;
      
      const originalText = btn.innerHTML;
      btn.innerHTML = '⏳';
      
      chrome.runtime.sendMessage({ action: 'download', url: a.href }, (response) => {
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
    
    // Sometimes overlays doesn't exist immediately, so append to the thumbnail directly
    // but ensure thumbnail has relative positioning
    thumbnail.style.position = 'relative';
    thumbnail.appendChild(btn);
  });
}

const observer = new MutationObserver(() => injectButton());
observer.observe(document.body, { childList: true, subtree: true });

setTimeout(injectButton, 1500);
