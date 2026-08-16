// ReClip Extension Background Service Worker v1.2.0

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

      console.log('[ReClip Extension] Dispatching download:', payload);

      fetch('http://127.0.0.1:8899/api/download', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      })
      .then(res => {
        if (!res.ok) throw new Error('ReClip server HTTP error: ' + res.status);
        return res.json();
      })
      .then(data => {
        if (data.error) {
          console.error('[ReClip Extension] Server returned error:', data.error);
          sendResponse({ success: false, error: data.error });
        } else {
          console.log('[ReClip Extension] Download initiated with job:', data.job_id);
          sendResponse({ success: true, job_id: data.job_id });
        }
      })
      .catch(err => {
        console.error('[ReClip Extension] Connection error:', err);
        sendResponse({ 
          success: false, 
          error: 'Could not connect to ReClip on http://127.0.0.1:8899. Please make sure the ReClip application is running!' 
        });
      });
    });
    
    return true; // Keep message channel open for async response
  }
});
