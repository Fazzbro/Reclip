function injectButton() {
  // Find thumbnails that don't have the button yet
  const thumbnails = document.querySelectorAll('ytd-thumbnail:not(.reclip-injected)');
  
  thumbnails.forEach(thumbnail => {
    thumbnail.classList.add('reclip-injected');
    
    // Create button
    const btn = document.createElement('button');
    btn.className = 'reclip-download-btn';
    btn.innerHTML = '??';
    btn.title = 'Download with ReClip';
    
    // Prevent clicking the video when clicking the button
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      
      const a = thumbnail.querySelector('a#thumbnail');
      if (!a || !a.href) return;
      
      const originalText = btn.innerHTML;
      btn.innerHTML = '?';
      
      chrome.runtime.sendMessage({ action: 'download', url: a.href }, (response) => {
        if (response && response.success) {
          btn.innerHTML = '?';
          setTimeout(() => btn.innerHTML = originalText, 3000);
        } else {
          btn.innerHTML = '?';
          alert('ReClip Error: ' + (response?.error || 'Ensure ReClip app/server is running!'));
          setTimeout(() => btn.innerHTML = originalText, 3000);
        }
      });
    });
    
    // Inject into thumbnail container
    const overlays = thumbnail.querySelector('#overlays');
    if (overlays) {
      overlays.appendChild(btn);
    }
  });
}

// Observe DOM for new thumbnails
const observer = new MutationObserver(() => injectButton());
observer.observe(document.body, { childList: true, subtree: true });

// Initial injection
setTimeout(injectButton, 1500);
