chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.action === 'download') {
    chrome.storage.sync.get(['formatId'], (result) => {
      const formatId = result.formatId || '1080';
      const format = formatId === 'audio' ? 'audio' : 'video';
      const f_id = format === 'audio' ? '' : formatId;
      
      const payload = {
        url: request.url,
        format: format,
        format_id: f_id,
        title: ''
      };

      fetch('http://127.0.0.1:8899/api/download', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      })
      .then(res => {
        if (!res.ok) throw new Error('Local server returned ' + res.status);
        return res.json();
      })
      .then(data => {
        if (data.error) {
          console.error('ReClip Error:', data.error);
          sendResponse({ success: false, error: data.error });
        } else {
          // Download has started in ReClip
          sendResponse({ success: true, job_id: data.job_id });
        }
      })
      .catch(err => {
        console.error('Fetch error:', err);
        sendResponse({ 
          success: false, 
          error: 'Could not connect to ReClip. Is the ReClip app or background server running?' 
        });
      });
    });
    
    return true; 
  }
});
