// ReClip Popup Script v1.2.0

document.addEventListener('DOMContentLoaded', () => {
  const qualitySelect = document.getElementById('quality');
  const audioLangSelect = document.getElementById('audioLang');
  const saveBtn = document.getElementById('save');
  const saveMsg = document.getElementById('saveMsg');
  const openFolderBtn = document.getElementById('openFolder');
  const serverDot = document.getElementById('serverDot');
  const serverText = document.getElementById('serverText');

  // Load saved preferences
  chrome.storage.sync.get(['formatId', 'audioLang'], (result) => {
    if (result.formatId) {
      qualitySelect.value = result.formatId;
    }
    if (result.audioLang) {
      audioLangSelect.value = result.audioLang;
    }
  });

  // Check server health
  chrome.runtime.sendMessage({ action: 'check_server' }, (res) => {
    if (res && res.online) {
      serverDot.className = 'status-dot online';
      serverText.innerText = 'Connected';
    } else {
      serverDot.className = 'status-dot offline';
      serverText.innerText = 'Offline';
    }
  });

  // Save settings
  saveBtn.addEventListener('click', () => {
    const formatId = qualitySelect.value;
    const audioLang = audioLangSelect.value;
    chrome.storage.sync.set({ formatId: formatId, audioLang: audioLang }, () => {
      saveMsg.innerText = 'Settings Saved!';
      setTimeout(() => { saveMsg.innerText = ''; }, 2000);
    });
  });

  // Open downloads folder
  openFolderBtn.addEventListener('click', () => {
    chrome.runtime.sendMessage({ action: 'open_downloads' });
  });
});
