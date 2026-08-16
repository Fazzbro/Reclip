document.addEventListener('DOMContentLoaded', () => {
  const qualitySelect = document.getElementById('quality');
  const saveBtn = document.getElementById('save');
  const status = document.getElementById('status');

  chrome.storage.sync.get(['formatId'], (result) => {
    if (result.formatId) {
      qualitySelect.value = result.formatId;
    }
  });

  saveBtn.addEventListener('click', () => {
    chrome.storage.sync.set({ formatId: qualitySelect.value }, () => {
      status.textContent = 'Saved successfully!';
      setTimeout(() => { status.textContent = ''; }, 2000);
    });
  });
});
