import React, { useState, useEffect } from 'react';
import {
  Download,
  Folder,
  Shield,
  Search,
  ClipboardPaste,
  Share2,
  Trash2,
  Play,
  RotateCw,
  X,
  ExternalLink,
  Moon,
  Sun,
  CheckCircle2,
  AlertCircle,
  FileText,
  Clock,
  Layers,
  Sparkles,
} from 'lucide-react';
import {
  nativeBridge,
  MediaInfo,
  FormatOption,
  DownloadItem,
} from './lib/nativeBridge';
import { TextEffect } from './components/motion-primitives/text-effect';
import { AnimatedNumber } from './components/motion-primitives/animated-number';
import { AnimatedBackground } from './components/motion-primitives/animated-background';
import { InfiniteSlider } from './components/motion-primitives/infinite-slider';
import { TransitionPanel } from './components/motion-primitives/transition-panel';
import { BorderTrail } from './components/motion-primitives/border-trail';

export default function App() {
  const [activeTab, setActiveTab] = useState<'home' | 'history' | 'cookies' | 'settings'>('home');
  const [tabIndex, setTabIndex] = useState(0);
  const [darkMode, setDarkMode] = useState(false);

  // Home State
  const [urlInput, setUrlInput] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [mediaInfo, setMediaInfo] = useState<MediaInfo | null>(null);
  const [selectedFormat, setSelectedFormat] = useState<'VIDEO' | 'AUDIO'>('VIDEO');
  const [selectedQuality, setSelectedQuality] = useState<FormatOption | null>(null);
  const [selectedAudioLang, setSelectedAudioLang] = useState<string>('default');

  // Active Downloads & History
  const [activeDownloads, setActiveDownloads] = useState<DownloadItem[]>([]);
  const [historyItems, setHistoryItems] = useState<DownloadItem[]>([]);
  const [hasCookies, setHasCookies] = useState(false);

  // Cookies State
  const [cookieText, setCookieText] = useState('');

  // Settings State
  const [engineStatus, setEngineStatus] = useState<string | null>(null);
  const [isUpdatingEngine, setIsUpdatingEngine] = useState(false);

  // Initialize and check dark mode
  useEffect(() => {
    const isDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
    setDarkMode(isDark);
    if (isDark) document.documentElement.classList.add('dark');

    setHasCookies(nativeBridge.hasCookies());
    setHistoryItems(nativeBridge.getHistory());
    setCookieText(nativeBridge.getCookieContent());

    // Listen for incoming URLs from Android Share Intent
    const unsubUrl = nativeBridge.onUrlReceived((sharedUrl) => {
      setUrlInput(sharedUrl);
      setActiveTab('home');
      setTabIndex(0);
      handleFetch(sharedUrl);
    });

    // Listen for live download progress updates from Android service
    const unsubProgress = nativeBridge.onProgress((item) => {
      if (item.status === 'COMPLETED' || item.status === 'ERROR' || item.status === 'CANCELLED') {
        setActiveDownloads((prev) => prev.filter((d) => d.id !== item.id));
        setHistoryItems(nativeBridge.getHistory());
      } else {
        setActiveDownloads((prev) => {
          const index = prev.findIndex((d) => d.id === item.id);
          if (index >= 0) {
            const next = [...prev];
            next[index] = item;
            return next;
          }
          return [item, ...prev];
        });
      }
    });

    return () => {
      unsubUrl();
      unsubProgress();
    };
  }, []);

  const toggleDarkMode = () => {
    setDarkMode((prev) => {
      const next = !prev;
      if (next) {
        document.documentElement.classList.add('dark');
      } else {
        document.documentElement.classList.remove('dark');
      }
      return next;
    });
  };

  const handleFetch = async (targetUrl?: string) => {
    const url = (targetUrl || urlInput).trim();
    if (!url) return;
    setIsLoading(true);
    setErrorMsg(null);
    setMediaInfo(null);

    try {
      const info = await nativeBridge.fetchInfo(url);
      setMediaInfo(info);
      if (info.formats && info.formats.length > 0) {
        setSelectedQuality(info.formats[0]);
      }
      if (info.audioLanguages && info.audioLanguages.length > 0) {
        setSelectedAudioLang(info.audioLanguages[0].code);
      }
    } catch (err: any) {
      setErrorMsg(err.message || 'Failed to fetch media details');
    } finally {
      setIsLoading(false);
    }
  };

  const handleStartDownload = () => {
    if (!mediaInfo) return;
    nativeBridge.startDownload(
      mediaInfo,
      selectedFormat,
      selectedQuality || undefined,
      selectedAudioLang
    );
  };

  const handlePasteClipboard = async () => {
    try {
      const text = await navigator.clipboard.readText();
      if (text) {
        setUrlInput(text);
        handleFetch(text);
      }
    } catch (e) {
      // Fallback
    }
  };

  const switchTab = (tab: 'home' | 'history' | 'cookies' | 'settings', index: number) => {
    setActiveTab(tab);
    setTabIndex(index);
    if (tab === 'history') {
      setHistoryItems(nativeBridge.getHistory());
    }
    if (tab === 'cookies') {
      setHasCookies(nativeBridge.hasCookies());
      setCookieText(nativeBridge.getCookieContent());
    }
  };

  const supportedPlatforms = [
    'YouTube', 'TikTok', 'Instagram', 'Twitter / X', 'Reddit', 'SoundCloud',
    'Facebook', 'Vimeo', 'Twitch', 'Pinterest', 'Loom', 'Threads', '1000+ Sites'
  ];

  return (
    <div className="min-h-screen flex flex-col justify-between max-w-md mx-auto relative px-4 pt-6 pb-24 sm:px-6">
      
      {/* Top Header */}
      <header className="flex items-center justify-between pb-4 border-b border-paper-border dark:border-obsidian-border">
        <div>
          <div className="flex items-baseline gap-2">
            <h1 className="font-serif text-4xl font-normal tracking-tight">
              Re<em className="text-reclip-terracotta not-italic italic">Clip</em>
            </h1>
            <span className="text-[10px] font-mono tracking-widest uppercase text-paper-muted dark:text-obsidian-muted">
              v1.0 Mobile
            </span>
          </div>
          <div className="h-4">
            <TextEffect per="word" preset="fade" className="text-[11px] font-mono text-paper-muted dark:text-obsidian-muted uppercase tracking-wider">
              Native Media Extraction • Anti-Slop
            </TextEffect>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {/* Dark mode button */}
          <button
            onClick={toggleDarkMode}
            className="p-2 rounded-lg border border-paper-border dark:border-obsidian-border bg-paper-surface dark:bg-obsidian-surface text-paper-text dark:text-obsidian-text hover:border-reclip-terracotta transition-colors"
            title="Toggle Theme"
          >
            {darkMode ? <Sun size={15} /> : <Moon size={15} />}
          </button>

          {/* Open Folder */}
          <button
            onClick={() => nativeBridge.openFolder()}
            className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg border border-paper-border dark:border-obsidian-border bg-paper-surface dark:bg-obsidian-surface text-[11px] font-mono uppercase text-paper-text dark:text-obsidian-text hover:border-reclip-terracotta transition-colors"
          >
            <Folder size={13} className="text-reclip-terracotta" />
            <span>Files</span>
          </button>
        </div>
      </header>

      {/* Main Content with TransitionPanel */}
      <main className="flex-1 mt-5">
        <TransitionPanel activeIndex={tabIndex}>
          
          {/* TAB 1: DOWNLOADER (HOME) */}
          {activeTab === 'home' && (
            <div className="space-y-5">
              
              {/* URL Input Box */}
              <div className="space-y-2">
                <div className="relative flex items-center">
                  <input
                    type="url"
                    value={urlInput}
                    onChange={(e) => setUrlInput(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && handleFetch()}
                    placeholder="Paste YouTube, TikTok, X, IG link..."
                    className="w-full px-4 py-3.5 pr-20 rounded-xl border border-paper-border dark:border-obsidian-border bg-paper-surface dark:bg-obsidian-surface text-sm font-sans placeholder:text-paper-muted dark:placeholder:text-obsidian-muted focus:outline-none focus:border-reclip-terracotta transition-all"
                  />
                  <div className="absolute right-2 flex items-center gap-1">
                    <button
                      onClick={handlePasteClipboard}
                      className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-paper-bg dark:bg-obsidian-bg text-reclip-terracotta hover:bg-reclip-terracotta hover:text-white transition-all text-xs font-mono font-medium"
                    >
                      <ClipboardPaste size={13} />
                      <span>PASTE</span>
                    </button>
                  </div>
                </div>

                <button
                  onClick={() => handleFetch()}
                  disabled={!urlInput.trim() || isLoading}
                  className="w-full py-3 rounded-xl bg-paper-text dark:bg-obsidian-text text-paper-bg dark:text-obsidian-bg font-mono text-xs uppercase font-medium tracking-wider flex items-center justify-center gap-2 hover:opacity-90 active:scale-[0.99] transition-all disabled:opacity-50"
                >
                  {isLoading ? (
                    <>
                      <RotateCw size={15} className="animate-spin" />
                      <span>Extracting Stream Info...</span>
                    </>
                  ) : (
                    <>
                      <Search size={15} />
                      <span>Fetch Media Info</span>
                    </>
                  )}
                </button>
              </div>

              {/* Error Message */}
              {errorMsg && (
                <div className="p-3.5 rounded-xl border border-red-500/30 bg-red-500/10 text-red-600 dark:text-red-400 text-xs font-mono flex items-start gap-2.5">
                  <AlertCircle size={16} className="shrink-0 mt-0.5" />
                  <div>
                    <p className="font-semibold">Extraction Failed</p>
                    <p className="opacity-90 mt-0.5">{errorMsg}</p>
                    {errorMsg.toLowerCase().includes('bot') && (
                      <button
                        onClick={() => switchTab('cookies', 2)}
                        className="underline text-reclip-terracotta mt-1.5 block font-bold"
                      >
                        → Configure Cookies to Bypass
                      </button>
                    )}
                  </div>
                </div>
              )}

              {/* Active Downloads List with BorderTrail & AnimatedNumber */}
              {activeDownloads.length > 0 && (
                <div className="space-y-3 pt-2">
                  <p className="text-[11px] font-mono uppercase tracking-widest text-paper-muted dark:text-obsidian-muted">
                    Active Downloads ({activeDownloads.length})
                  </p>
                  {activeDownloads.map((item) => (
                    <div
                      key={item.id}
                      className="relative p-3.5 rounded-xl border border-paper-border dark:border-obsidian-border bg-paper-surface dark:bg-obsidian-surface overflow-hidden shadow-sm"
                    >
                      <BorderTrail size={50} />

                      <div className="flex items-center justify-between gap-3">
                        <div className="min-w-0 flex-1">
                          <p className="text-xs font-medium font-sans truncate">{item.title}</p>
                          <p className="text-[10px] font-mono text-reclip-terracotta mt-0.5">
                            {item.qualityLabel} • {item.formatType}
                          </p>
                        </div>
                        <button
                          onClick={() => nativeBridge.cancelDownload(item.id)}
                          className="p-1 rounded-md text-paper-muted dark:text-obsidian-muted hover:text-red-500"
                        >
                          <X size={15} />
                        </button>
                      </div>

                      {/* Progress Bar */}
                      <div className="w-full h-1.5 rounded-full bg-paper-border dark:bg-obsidian-border overflow-hidden mt-3">
                        <div
                          className="h-full bg-reclip-terracotta transition-all duration-300"
                          style={{ width: `${item.progress}%` }}
                        />
                      </div>

                      {/* Monospace live metrics with AnimatedNumber */}
                      <div className="flex items-center justify-between mt-2 font-mono text-[11px]">
                        <div className="flex items-center gap-1 text-reclip-terracotta font-medium">
                          <AnimatedNumber value={item.progress} precision={1} />
                          <span>%</span>
                        </div>
                        <div className="text-paper-muted dark:text-obsidian-muted flex items-center gap-2">
                          {item.speed && <span>{item.speed}</span>}
                          {item.eta && <span>ETA {item.eta}</span>}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}

              {/* Media Preview Card */}
              {mediaInfo && (
                <div className="p-4 rounded-2xl border border-paper-border dark:border-obsidian-border bg-paper-surface dark:bg-obsidian-surface shadow-sm space-y-4 animate-in fade-in slide-in-from-bottom-2 duration-300">
                  
                  {/* Thumbnail & Duration */}
                  <div className="relative aspect-video rounded-xl overflow-hidden bg-black/5 dark:bg-white/5">
                    {mediaInfo.thumbnail && (
                      <img
                        src={mediaInfo.thumbnail}
                        alt={mediaInfo.title}
                        className="w-full h-full object-cover"
                      />
                    )}
                    {mediaInfo.durationFormatted && (
                      <div className="absolute bottom-2 right-2 px-2 py-0.5 rounded-md bg-black/80 text-white font-mono text-[10px] tracking-wider">
                        {mediaInfo.durationFormatted}
                      </div>
                    )}
                  </div>

                  {/* Title & Channel */}
                  <div>
                    <h2 className="font-serif text-lg leading-snug line-clamp-2">
                      {mediaInfo.title}
                    </h2>
                    {mediaInfo.uploader && (
                      <p className="text-[11px] font-mono text-paper-muted dark:text-obsidian-muted mt-1">
                        Channel: {mediaInfo.uploader}
                      </p>
                    )}
                  </div>

                  {/* Format Selector with AnimatedBackground */}
                  <div className="space-y-1.5">
                    <span className="text-[10px] font-mono uppercase tracking-wider text-paper-muted dark:text-obsidian-muted">
                      Select Format
                    </span>
                    <div className="flex rounded-lg border border-paper-border dark:border-obsidian-border p-1 bg-paper-bg dark:bg-obsidian-bg">
                      <AnimatedBackground
                        defaultValue={selectedFormat}
                        className="rounded-md bg-paper-text dark:bg-obsidian-text shadow-sm"
                        onValueChange={(id) => id && setSelectedFormat(id as 'VIDEO' | 'AUDIO')}
                      >
                        <button
                          data-id="VIDEO"
                          className={`flex-1 py-1.5 text-xs font-mono font-medium tracking-wider transition-colors ${
                            selectedFormat === 'VIDEO' ? 'text-paper-bg dark:text-obsidian-bg font-semibold' : 'text-paper-muted dark:text-obsidian-muted'
                          }`}
                        >
                          VIDEO (MP4)
                        </button>
                        <button
                          data-id="AUDIO"
                          className={`flex-1 py-1.5 text-xs font-mono font-medium tracking-wider transition-colors ${
                            selectedFormat === 'AUDIO' ? 'text-paper-bg dark:text-obsidian-bg font-semibold' : 'text-paper-muted dark:text-obsidian-muted'
                          }`}
                        >
                          AUDIO (MP3)
                        </button>
                      </AnimatedBackground>
                    </div>
                  </div>

                  {/* Video Resolution Chips with AnimatedBackground */}
                  {selectedFormat === 'VIDEO' && mediaInfo.formats?.length > 0 && (
                    <div className="space-y-1.5">
                      <span className="text-[10px] font-mono uppercase tracking-wider text-paper-muted dark:text-obsidian-muted">
                        Resolution & Quality
                      </span>
                      <div className="flex flex-wrap gap-1.5">
                        <AnimatedBackground
                          defaultValue={selectedQuality?.id || mediaInfo.formats[0].id}
                          className="rounded-lg bg-reclip-terracotta"
                          onValueChange={(id) => {
                            const opt = mediaInfo.formats.find((f) => f.id === id);
                            if (opt) setSelectedQuality(opt);
                          }}
                        >
                          {mediaInfo.formats.map((opt) => {
                            const isSelected = selectedQuality?.id === opt.id;
                            return (
                              <button
                                key={opt.id}
                                data-id={opt.id}
                                className={`px-3 py-1.5 rounded-lg border border-paper-border dark:border-obsidian-border text-xs font-mono transition-colors ${
                                  isSelected ? 'text-white border-transparent' : 'text-paper-text dark:text-obsidian-text'
                                }`}
                              >
                                {opt.label} {opt.filesizeFormatted ? `• ${opt.filesizeFormatted}` : ''}
                              </button>
                            );
                          })}
                        </AnimatedBackground>
                      </div>
                    </div>
                  )}

                  {/* Audio Dub Language selector if present */}
                  {mediaInfo.audioLanguages?.length > 0 && (
                    <div className="space-y-1.5">
                      <span className="text-[10px] font-mono uppercase tracking-wider text-paper-muted dark:text-obsidian-muted">
                        Audio Track / Dub
                      </span>
                      <select
                        value={selectedAudioLang}
                        onChange={(e) => setSelectedAudioLang(e.target.value)}
                        className="w-full px-3 py-2 rounded-lg border border-paper-border dark:border-obsidian-border bg-paper-surface dark:bg-obsidian-surface text-xs font-mono"
                      >
                        <option value="default">Original / Default Track</option>
                        {mediaInfo.audioLanguages.map((l) => (
                          <option key={l.code} value={l.code}>
                            {l.name}
                          </option>
                        ))}
                      </select>
                    </div>
                  )}

                  {/* Download Action Button */}
                  <button
                    onClick={handleStartDownload}
                    className="w-full py-3.5 rounded-xl bg-reclip-terracotta text-white font-mono text-xs uppercase font-semibold tracking-wider flex items-center justify-center gap-2 hover:bg-reclip-terracottaLight active:scale-[0.99] transition-all shadow-md shadow-reclip-terracotta/20"
                  >
                    <Download size={16} />
                    <span>Download Media</span>
                    <span className="opacity-80">
                      (
                      {selectedFormat === 'AUDIO'
                        ? mediaInfo.audioFilesizeFormatted || 'Audio'
                        : selectedQuality?.filesizeFormatted || mediaInfo.approxFilesizeFormatted}
                      )
                    </span>
                  </button>
                </div>
              )}

              {/* Supported Platforms Ribbon with InfiniteSlider */}
              <div className="pt-4 border-t border-paper-border dark:border-obsidian-border space-y-2">
                <span className="text-[10px] font-mono uppercase tracking-widest text-paper-muted dark:text-obsidian-muted">
                  Supported Platforms
                </span>
                <InfiniteSlider gap={24} duration={35} className="py-2">
                  {supportedPlatforms.map((platform, i) => (
                    <span
                      key={i}
                      className="text-xs font-mono tracking-wider px-2.5 py-1 rounded-md bg-paper-surface dark:bg-obsidian-surface border border-paper-border dark:border-obsidian-border text-paper-muted dark:text-obsidian-muted whitespace-nowrap"
                    >
                      {platform}
                    </span>
                  ))}
                </InfiniteSlider>
              </div>

            </div>
          )}

          {/* TAB 2: DOWNLOADS HISTORY */}
          {activeTab === 'history' && (
            <div className="space-y-4">
              <div className="flex items-center justify-between pb-2 border-b border-paper-border dark:border-obsidian-border">
                <h2 className="font-serif text-2xl">Completed Downloads</h2>
                {historyItems.length > 0 && (
                  <button
                    onClick={() => {
                      nativeBridge.clearHistory();
                      setHistoryItems([]);
                    }}
                    className="text-xs font-mono text-red-500 hover:underline flex items-center gap-1"
                  >
                    <Trash2 size={13} />
                    <span>Clear All</span>
                  </button>
                )}
              </div>

              {historyItems.length === 0 ? (
                <div className="py-16 text-center space-y-2 text-paper-muted dark:text-obsidian-muted">
                  <Download size={32} className="mx-auto opacity-40" />
                  <p className="font-serif text-lg text-paper-text dark:text-obsidian-text">No Saved Downloads</p>
                  <p className="text-xs font-mono">Downloaded video and audio will appear here.</p>
                </div>
              ) : (
                <div className="space-y-3">
                  {historyItems.map((item) => (
                    <div
                      key={item.id}
                      className="p-3.5 rounded-xl border border-paper-border dark:border-obsidian-border bg-paper-surface dark:bg-obsidian-surface flex items-center justify-between gap-3 shadow-sm hover:border-reclip-terracotta transition-colors"
                    >
                      <div
                        onClick={() => nativeBridge.playMedia(item.filePath)}
                        className="flex-1 min-w-0 cursor-pointer"
                      >
                        <p className="text-xs font-medium font-sans truncate">{item.title}</p>
                        <p className="text-[10px] font-mono text-paper-muted dark:text-obsidian-muted mt-0.5">
                          <span className="text-reclip-terracotta">{item.qualityLabel}</span> • {item.formatType} • {item.filesizeFormatted}
                        </p>
                      </div>

                      <div className="flex items-center gap-1">
                        <button
                          onClick={() => nativeBridge.playMedia(item.filePath)}
                          className="p-2 rounded-lg hover:bg-paper-bg dark:hover:bg-obsidian-bg text-reclip-terracotta"
                          title="Play Media"
                        >
                          <Play size={15} />
                        </button>
                        <button
                          onClick={() => nativeBridge.shareMedia(item.filePath)}
                          className="p-2 rounded-lg hover:bg-paper-bg dark:hover:bg-obsidian-bg text-paper-muted dark:text-obsidian-muted hover:text-paper-text"
                          title="Share"
                        >
                          <Share2 size={15} />
                        </button>
                        <button
                          onClick={() => {
                            nativeBridge.deleteHistory(item.id, false);
                            setHistoryItems(nativeBridge.getHistory());
                          }}
                          className="p-2 rounded-lg hover:bg-paper-bg dark:hover:bg-obsidian-bg text-paper-muted dark:text-obsidian-muted hover:text-red-500"
                          title="Remove"
                        >
                          <Trash2 size={15} />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* TAB 3: COOKIES BYPASS */}
          {activeTab === 'cookies' && (
            <div className="space-y-4">
              <div>
                <h2 className="font-serif text-2xl">Cookie Bypass</h2>
                <p className="text-xs font-mono text-paper-muted dark:text-obsidian-muted mt-0.5">
                  Bypass YouTube "Sign in to confirm you're not a bot" checks
                </p>
              </div>

              {/* Status Banner */}
              <div
                className={`p-3.5 rounded-xl border flex items-center gap-3 ${
                  hasCookies
                    ? 'border-green-500/30 bg-green-500/10 text-green-700 dark:text-green-300'
                    : 'border-paper-border dark:border-obsidian-border bg-paper-surface dark:bg-obsidian-surface'
                }`}
              >
                {hasCookies ? <CheckCircle2 size={18} className="text-green-500" /> : <Shield size={18} className="text-paper-muted" />}
                <div>
                  <p className="text-xs font-mono font-semibold">
                    {hasCookies ? 'Cookie File Active' : 'No Cookies Installed'}
                  </p>
                  <p className="text-[11px] opacity-80 font-sans">
                    {hasCookies ? 'All requests will use your authenticated session.' : 'Age-restricted or bot-checked videos may require cookies.'}
                  </p>
                </div>
              </div>

              {/* Cookie Input */}
              <div className="space-y-2">
                <span className="text-[10px] font-mono uppercase tracking-wider text-paper-muted dark:text-obsidian-muted">
                  Paste cookies.txt or Netscape cookie format
                </span>
                <textarea
                  value={cookieText}
                  onChange={(e) => setCookieText(e.target.value)}
                  placeholder="# Netscape HTTP Cookie File&#10;.youtube.com&#9;TRUE&#9;/&#9;TRUE&#9;..."
                  className="w-full h-44 p-3 rounded-xl border border-paper-border dark:border-obsidian-border bg-paper-surface dark:bg-obsidian-surface font-mono text-[11px] focus:outline-none focus:border-reclip-terracotta"
                />
              </div>

              <div className="flex gap-2">
                <button
                  onClick={() => {
                    nativeBridge.saveCookies(cookieText);
                    setHasCookies(true);
                    alert('Cookies saved successfully');
                  }}
                  className="flex-1 py-3 rounded-xl bg-reclip-terracotta text-white font-mono text-xs uppercase font-medium hover:bg-reclip-terracottaLight transition-colors"
                >
                  Save Cookies
                </button>
                {hasCookies && (
                  <button
                    onClick={() => {
                      nativeBridge.clearCookies();
                      setCookieText('');
                      setHasCookies(false);
                    }}
                    className="px-4 py-3 rounded-xl border border-red-500/30 text-red-500 font-mono text-xs uppercase hover:bg-red-500/10 transition-colors"
                  >
                    Clear
                  </button>
                )}
              </div>
            </div>
          )}

          {/* TAB 4: SETTINGS */}
          {activeTab === 'settings' && (
            <div className="space-y-4">
              <div>
                <h2 className="font-serif text-2xl">Configuration</h2>
                <p className="text-xs font-mono text-paper-muted dark:text-obsidian-muted mt-0.5">
                  Internal extraction engines & preferences
                </p>
              </div>

              {/* yt-dlp Core Updater */}
              <div className="p-4 rounded-xl border border-paper-border dark:border-obsidian-border bg-paper-surface dark:bg-obsidian-surface space-y-3">
                <div className="flex items-center gap-2">
                  <RotateCw size={16} className="text-reclip-terracotta" />
                  <h3 className="font-sans font-medium text-sm">yt-dlp Core Engine</h3>
                </div>
                <p className="text-xs text-paper-muted dark:text-obsidian-muted">
                  Keep extraction algorithms up-to-date with the latest YouTube, TikTok, and X changes.
                </p>
                <button
                  onClick={async () => {
                    setIsUpdatingEngine(true);
                    try {
                      const res = nativeBridge.updateYtDlp();
                      setEngineStatus(res);
                    } finally {
                      setIsUpdatingEngine(false);
                    }
                  }}
                  disabled={isUpdatingEngine}
                  className="w-full py-2.5 rounded-lg border border-paper-border dark:border-obsidian-border bg-paper-bg dark:bg-obsidian-bg text-xs font-mono uppercase font-medium hover:border-reclip-terracotta transition-colors flex items-center justify-center gap-2"
                >
                  {isUpdatingEngine ? <RotateCw size={13} className="animate-spin" /> : null}
                  <span>Check Engine Updates</span>
                </button>
                {engineStatus && (
                  <p className="text-[11px] font-mono text-reclip-terracotta">{engineStatus}</p>
                )}
              </div>

              {/* Storage Path */}
              <div className="p-4 rounded-xl border border-paper-border dark:border-obsidian-border bg-paper-surface dark:bg-obsidian-surface space-y-2">
                <div className="flex items-center justify-between">
                  <div>
                    <h3 className="font-sans font-medium text-sm">Download Directory</h3>
                    <p className="text-[11px] font-mono text-reclip-terracotta mt-0.5">
                      /Download/ReClip/
                    </p>
                  </div>
                  <button
                    onClick={() => nativeBridge.openFolder()}
                    className="p-2 rounded-lg border border-paper-border dark:border-obsidian-border hover:border-reclip-terracotta"
                  >
                    <ExternalLink size={15} />
                  </button>
                </div>
              </div>

              {/* Attribution */}
              <div className="p-4 rounded-xl border border-paper-border dark:border-obsidian-border bg-paper-surface dark:bg-obsidian-surface text-[11px] font-mono text-paper-muted dark:text-obsidian-muted space-y-1">
                <p className="text-paper-text dark:text-obsidian-text font-semibold">ReClip for Android</p>
                <p>Architecture: Jetpack Compose + React Motion Primitives + yt-dlp + FFmpeg</p>
                <p>Licensed under MIT. Zero tracking, zero telemetry, zero AI slop.</p>
              </div>
            </div>
          )}

        </TransitionPanel>
      </main>

      {/* Bottom Navigation with AnimatedBackground */}
      <nav className="fixed bottom-0 left-0 right-0 max-w-md mx-auto p-3 bg-paper-bg/90 dark:bg-obsidian-bg/90 backdrop-blur-md border-t border-paper-border dark:border-obsidian-border z-50">
        <div className="flex rounded-xl border border-paper-border dark:border-obsidian-border p-1 bg-paper-surface dark:bg-obsidian-surface">
          <AnimatedBackground
            defaultValue={activeTab}
            className="rounded-lg bg-paper-text dark:bg-obsidian-text shadow-sm"
            onValueChange={(id) => {
              if (id === 'home') switchTab('home', 0);
              if (id === 'history') switchTab('history', 1);
              if (id === 'cookies') switchTab('cookies', 2);
              if (id === 'settings') switchTab('settings', 3);
            }}
          >
            <button
              data-id="home"
              className={`flex-1 py-2 text-[11px] font-mono font-medium tracking-wider transition-colors text-center ${
                activeTab === 'home' ? 'text-paper-bg dark:text-obsidian-bg font-semibold' : 'text-paper-muted dark:text-obsidian-muted'
              }`}
            >
              FETCH
            </button>
            <button
              data-id="history"
              className={`flex-1 py-2 text-[11px] font-mono font-medium tracking-wider transition-colors text-center ${
                activeTab === 'history' ? 'text-paper-bg dark:text-obsidian-bg font-semibold' : 'text-paper-muted dark:text-obsidian-muted'
              }`}
            >
              HISTORY
            </button>
            <button
              data-id="cookies"
              className={`flex-1 py-2 text-[11px] font-mono font-medium tracking-wider transition-colors text-center ${
                activeTab === 'cookies' ? 'text-paper-bg dark:text-obsidian-bg font-semibold' : 'text-paper-muted dark:text-obsidian-muted'
              }`}
            >
              COOKIES
            </button>
            <button
              data-id="settings"
              className={`flex-1 py-2 text-[11px] font-mono font-medium tracking-wider transition-colors text-center ${
                activeTab === 'settings' ? 'text-paper-bg dark:text-obsidian-bg font-semibold' : 'text-paper-muted dark:text-obsidian-muted'
              }`}
            >
              SETTINGS
            </button>
          </AnimatedBackground>
        </div>
      </nav>

    </div>
  );
}
