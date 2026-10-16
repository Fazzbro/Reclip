export interface FormatOption {
  id: string;
  label: string;
  height: number;
  ext: string;
  filesize: number;
  filesizeFormatted: string;
}

export interface AudioLanguage {
  code: string;
  name: string;
  langId: string;
}

export interface MediaInfo {
  url: string;
  title: string;
  thumbnail: string;
  duration: number;
  durationFormatted: string;
  uploader: string;
  formats: FormatOption[];
  audioLanguages: AudioLanguage[];
  approxFilesizeFormatted: string;
  audioFilesizeFormatted: string;
}

export interface DownloadItem {
  id: string;
  url: string;
  title: string;
  thumbnail: string;
  formatType: 'VIDEO' | 'AUDIO';
  qualityLabel: string;
  status: 'QUEUED' | 'DOWNLOADING' | 'PAUSED' | 'COMPLETED' | 'ERROR' | 'CANCELLED';
  progress: number;
  progressString: string;
  speed: string;
  eta: string;
  filesizeFormatted: string;
  filePath: string;
  fileName: string;
  completedAt: number;
}

declare global {
  interface Window {
    AndroidBridge?: {
      fetchInfo: (url: string) => string;
      startDownload: (requestJson: string) => string;
      cancelDownload: (jobId: string) => void;
      getHistory: () => string;
      deleteHistoryItem: (jobId: string, deleteFile: boolean) => void;
      clearHistory: () => void;
      playMedia: (filePath: string) => void;
      shareMedia: (filePath: string) => void;
      openFolder: () => void;
      hasCookies: () => boolean;
      getCookieContent: () => string;
      saveCookies: (content: string) => boolean;
      clearCookies: () => boolean;
      updateYtDlp: () => string;
      onUrlReceived?: (url: string) => void;
      onDownloadProgress?: (itemJson: string) => void;
    };
  }
}

class NativeBridgeService {
  private progressListeners: ((item: DownloadItem) => void)[] = [];
  private urlListeners: ((url: string) => void)[] = [];

  constructor() {
    if (typeof window !== 'undefined') {
      // Setup global callbacks from Android Kotlin to React
      (window as any).onAndroidDownloadProgress = (jsonStr: string) => {
        try {
          const item: DownloadItem = JSON.parse(jsonStr);
          this.progressListeners.forEach((fn) => fn(item));
        } catch (e) {
          console.error('Error parsing progress json', e);
        }
      };

      (window as any).onAndroidUrlReceived = (url: string) => {
        this.urlListeners.forEach((fn) => fn(url));
      };
    }
  }

  isAndroidApp(): boolean {
    return typeof window !== 'undefined' && Boolean(window.AndroidBridge);
  }

  async fetchInfo(url: string): Promise<MediaInfo> {
    if (this.isAndroidApp() && window.AndroidBridge?.fetchInfo) {
      const resultJson = window.AndroidBridge.fetchInfo(url);
      const parsed = JSON.parse(resultJson);
      if (parsed.error) {
        throw new Error(parsed.error);
      }
      return parsed as MediaInfo;
    }

    // Web / Dev mock fallback
    await new Promise((r) => setTimeout(r, 900));
    return {
      url,
      title: 'Sample Video — ReClip Ultra High Definition',
      thumbnail: 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&auto=format&fit=crop&q=80',
      duration: 245,
      durationFormatted: '04:05',
      uploader: 'Studio Productions',
      formats: [
        { id: '1080', label: '1080p', height: 1080, ext: 'mp4', filesize: 44200000, filesizeFormatted: '42.1 MB' },
        { id: '720', label: '720p', height: 720, ext: 'mp4', filesize: 23100000, filesizeFormatted: '22.0 MB' },
        { id: '480', label: '480p', height: 480, ext: 'mp4', filesize: 12500000, filesizeFormatted: '11.9 MB' },
        { id: '360', label: '360p', height: 360, ext: 'mp4', filesize: 7800000, filesizeFormatted: '7.4 MB' },
      ],
      audioLanguages: [
        { code: 'en', name: 'English (Original)', langId: 'en' },
        { code: 'es', name: 'Spanish (Español)', langId: 'es' },
      ],
      approxFilesizeFormatted: '42.1 MB',
      audioFilesizeFormatted: '5.2 MB',
    };
  }

  startDownload(
    info: MediaInfo,
    formatType: 'VIDEO' | 'AUDIO',
    qualityOption?: FormatOption,
    audioLang: string = ''
  ): string {
    const payload = {
      url: info.url,
      title: info.title,
      thumbnail: info.thumbnail,
      formatType,
      qualityLabel: qualityOption?.label || (formatType === 'AUDIO' ? 'MP3 Audio' : '1080p'),
      qualityId: qualityOption?.id || '',
      qualityHeight: qualityOption?.height || 1080,
      audioLang,
    };

    if (this.isAndroidApp() && window.AndroidBridge?.startDownload) {
      return window.AndroidBridge.startDownload(JSON.stringify(payload));
    }

    // Mock progress in browser/dev
    const mockId = Math.random().toString(36).substring(2, 9);
    let progress = 0;
    const interval = setInterval(() => {
      progress += 10;
      const item: DownloadItem = {
        id: mockId,
        url: info.url,
        title: info.title,
        thumbnail: info.thumbnail,
        formatType,
        qualityLabel: payload.qualityLabel,
        status: progress >= 100 ? 'COMPLETED' : 'DOWNLOADING',
        progress: Math.min(progress, 100),
        progressString: progress >= 100 ? 'Completed' : `${progress}% • 4.2 MB/s`,
        speed: '4.2 MB/s',
        eta: `00:${String(Math.max(0, 10 - Math.floor(progress / 10))).padStart(2, '0')}`,
        filesizeFormatted: payload.qualityLabel,
        filePath: `/storage/emulated/0/Download/ReClip/${info.title}.mp4`,
        fileName: `${info.title}.mp4`,
        completedAt: Date.now(),
      };
      this.progressListeners.forEach((fn) => fn(item));
      if (progress >= 100) clearInterval(interval);
    }, 600);

    return mockId;
  }

  cancelDownload(jobId: string) {
    if (this.isAndroidApp() && window.AndroidBridge?.cancelDownload) {
      window.AndroidBridge.cancelDownload(jobId);
    }
  }

  getHistory(): DownloadItem[] {
    if (this.isAndroidApp() && window.AndroidBridge?.getHistory) {
      try {
        const json = window.AndroidBridge.getHistory();
        return JSON.parse(json);
      } catch (e) {
        return [];
      }
    }
    return [];
  }

  deleteHistory(jobId: string, deleteFile: boolean) {
    if (this.isAndroidApp() && window.AndroidBridge?.deleteHistoryItem) {
      window.AndroidBridge.deleteHistoryItem(jobId, deleteFile);
    }
  }

  clearHistory() {
    if (this.isAndroidApp() && window.AndroidBridge?.clearHistory) {
      window.AndroidBridge.clearHistory();
    }
  }

  playMedia(filePath: string) {
    if (this.isAndroidApp() && window.AndroidBridge?.playMedia) {
      window.AndroidBridge.playMedia(filePath);
    } else {
      alert(`Playing media: ${filePath}`);
    }
  }

  shareMedia(filePath: string) {
    if (this.isAndroidApp() && window.AndroidBridge?.shareMedia) {
      window.AndroidBridge.shareMedia(filePath);
    } else {
      alert(`Sharing media: ${filePath}`);
    }
  }

  openFolder() {
    if (this.isAndroidApp() && window.AndroidBridge?.openFolder) {
      window.AndroidBridge.openFolder();
    } else {
      alert('Opened Downloads/ReClip directory');
    }
  }

  hasCookies(): boolean {
    if (this.isAndroidApp() && window.AndroidBridge?.hasCookies) {
      return window.AndroidBridge.hasCookies();
    }
    return false;
  }

  getCookieContent(): string {
    if (this.isAndroidApp() && window.AndroidBridge?.getCookieContent) {
      return window.AndroidBridge.getCookieContent();
    }
    return '';
  }

  saveCookies(content: string): boolean {
    if (this.isAndroidApp() && window.AndroidBridge?.saveCookies) {
      return window.AndroidBridge.saveCookies(content);
    }
    return true;
  }

  clearCookies(): boolean {
    if (this.isAndroidApp() && window.AndroidBridge?.clearCookies) {
      return window.AndroidBridge.clearCookies();
    }
    return true;
  }

  updateYtDlp(): string {
    if (this.isAndroidApp() && window.AndroidBridge?.updateYtDlp) {
      return window.AndroidBridge.updateYtDlp();
    }
    return 'yt-dlp is up to date (2026.04.10)';
  }

  onProgress(listener: (item: DownloadItem) => void) {
    this.progressListeners.push(listener);
    return () => {
      this.progressListeners = this.progressListeners.filter((l) => l !== listener);
    };
  }

  onUrlReceived(listener: (url: string) => void) {
    this.urlListeners.push(listener);
    return () => {
      this.urlListeners = this.urlListeners.filter((l) => l !== listener);
    };
  }
}

export const nativeBridge = new NativeBridgeService();
