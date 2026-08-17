// ReClip Extension Background Service Worker v1.2.0

const activeJobs = new Map();

// Open downloads folder on local computer
function openDownloadsFolder() {
  fetch('http://127.0.0.1:8899/api/open-downloads', {
    method: 'POST'
  })
  .then(res => res.json())
  .then(data => console.log('[ReClip] Opened downloads folder:', data))
  .catch(err => {
    // Fallback to GET
    fetch('http://127.0.0.1:8899/api/open-downloads')
      .catch(e => console.error('[ReClip] Could not open downloads folder:', e));
  });
}

// Show system desktop notifications
function showNotification(id, title, message, isSuccess = false) {
  try {
    chrome.notifications.create(id, {
      type: 'basic',
      iconUrl: 'icon.png',
      title: title,
      message: message,
      priority: 2
    });
  } catch (e) {
    console.error('[ReClip] Notification error:', e);
  }
}

// Handle notification click to open downloads folder
chrome.notifications.onClicked.addListener((notifId) => {
  openDownloadsFolder();
});

// Broadcast progress updates to all tabs
function broadcastJobUpdate(jobData) {
  chrome.tabs.query({}, (tabs) => {
    tabs.forEach(tab => {
      if (tab.id) {
        chrome.tabs.sendMessage(tab.id, {
          type: 'RECLIP_JOB_UPDATE',
          data: jobData
        }).catch(() => {}); // Ignore tabs that don't have content script
      }
    });
  });
}

// Start polling status of a download job
function pollJobStatus(jobId, videoUrl) {
  if (activeJobs.has(jobId)) return;
  
  const pollInterval = setInterval(() => {
    fetch(`http://127.0.0.1:8899/api/status/${jobId}`)
      .then(res => res.json())
      .then(statusData => {
        const payload = {
          job_id: jobId,
          url: videoUrl,
          ...statusData
        };

        broadcastJobUpdate(payload);

        if (statusData.status === 'done') {
          clearInterval(pollInterval);
          activeJobs.delete(jobId);
          
          const fileName = statusData.filename || 'Your video';
          showNotification(
            `reclip_done_${jobId}`,
            '✅ ReClip: Download Complete!',
            `${fileName} has been saved. Click here to open folder.`,
            true
          );
        } else if (statusData.status === 'error') {
          clearInterval(pollInterval);
          activeJobs.delete(jobId);

          showNotification(
            `reclip_err_${jobId}`,
            '❌ ReClip: Download Failed',
            statusData.error || 'Download encountered an error.'
          );
        }
      })
      .catch(err => {
        console.error('[ReClip] Status polling error:', err);
      });
  }, 700);

  activeJobs.set(jobId, pollInterval);
}

chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.action === 'download') {
    chrome.storage.sync.get(['formatId', 'audioLang'], (result) => {
      const formatId = result.formatId || '1080';
      let audioLang = result.audioLang || 'original';
      if (audioLang === 'original' && request.detected_audio_lang) {
        audioLang = request.detected_audio_lang;
      }
      const format = formatId === 'audio' ? 'audio' : 'video';
      const f_id = format === 'audio' ? '' : formatId;
      
      const payload = {
        url: request.url,
        format: format,
        format_id: f_id,
        title: request.title || '',
        audio_lang: audioLang
      };

      console.log('[ReClip Extension] Starting download:', payload);

      fetch('http://127.0.0.1:8899/api/download', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      })
      .then(res => {
        if (!res.ok) throw new Error('Local server returned HTTP ' + res.status);
        return res.json();
      })
      .then(data => {
        if (data.error) {
          sendResponse({ success: false, error: data.error });
          showNotification(`reclip_err_${Date.now()}`, '❌ ReClip: Error', data.error);
        } else {
          sendResponse({ success: true, job_id: data.job_id });
          
          showNotification(
            `reclip_start_${data.job_id}`,
            '🚀 ReClip: Download Started',
            `Started download ${request.title ? `"${request.title.slice(0, 40)}..."` : ''}`
          );

          pollJobStatus(data.job_id, request.url);
        }
      })
      .catch(err => {
        const errorMsg = 'Could not connect to ReClip at http://127.0.0.1:8899. Please make sure ReClip desktop app is running!';
        sendResponse({ success: false, error: errorMsg });
        showNotification(`reclip_err_conn`, '⚠️ ReClip: Server Offline', errorMsg);
      });
    });
    
    return true; // Keep channel open for async response
  }

  if (request.action === 'open_downloads') {
    openDownloadsFolder();
    sendResponse({ success: true });
  }

  if (request.action === 'check_server') {
    fetch('http://127.0.0.1:8899/api/open-downloads', { method: 'OPTIONS' })
      .then(() => sendResponse({ online: true }))
      .catch(() => {
        fetch('http://127.0.0.1:8899/')
          .then(() => sendResponse({ online: true }))
          .catch(() => sendResponse({ online: false }));
      });
    return true;
  }
});
