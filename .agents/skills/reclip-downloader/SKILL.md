---
name: reclip-downloader
description: >-
  Download high-quality video and audio from 1000+ platforms (YouTube, TikTok, Instagram, Twitter/X, Reddit, Vimeo, SoundCloud, etc.) on Windows using ReClip MCP. Use when the user asks to download media, convert to MP3, inspect video resolutions/qualities, dub tracks, or view local downloads.
---

# ReClip Media Downloader Skill

Use this skill whenever the user requests downloading video or audio from the internet, checking available video formats/resolutions, inspecting audio dub tracks, or browsing local downloaded media on Windows.

## Engine & Tools

ReClip runs as a native Model Context Protocol (MCP) server named `reclip` backed by `yt-dlp`, embedded `ffmpeg`, and the ReClip Windows engine.

### Available MCP Tools

All tools are called via the `reclip` MCP server:

1. **`download_media`**:
   - `url` (str, required): Direct link or share link from YouTube, Reddit, Instagram, TikTok, Twitter/X, Facebook, Twitch, Vimeo, etc.
   - `format_type` (str): `"video"` (default) or `"audio"` (converts to high-bitrate MP3).
   - `quality` (str): Max video height (`"360"`, `"480"`, `"720"`, `"1080"`, `"1440"`, `"2160"`, or `"best"`). Default: `"1080"`.
   - `audio_language` (str): Language code for multi-language videos (e.g., `"en"`, `"es"`, `"ml"`, `"hi"`) or `"all"` for multi-track MKV.
   - `custom_title` (str): Optional custom filename for the saved file.
   - `output_dir` (str): Optional destination directory (defaults to configured ReClip Downloads folder: `D:\reclip downloads`).
   - `wait_for_completion` (bool): `True` (default) waits for completion and returns final path and size; `False` runs in background and returns `job_id`.

2. **`get_media_info`**:
   - Inspect title, duration, author, thumbnail, available resolutions, and dub tracks without downloading.

3. **`get_download_status`**:
   - Check real-time progress (`percent`, `speed`, `ETA`) of an active background download job.

4. **`list_downloads`**:
   - Inspect recent downloads from the ReClip directory. Returns filename, path, size, and date.

5. **`get_config`**:
   - View active download directory, yt-dlp version, and cookies status.

6. **`set_reclip_download_dir`**:
   - Update default download folder across ReClip.

7. **`open_downloads_folder`**:
   - Open the download directory in Windows File Explorer.

8. **`list_supported_sites`**:
   - Search or list supported media sites.

## Best Practices

- When the user asks to download media, call `download_media` with `wait_for_completion=True`.
- Once finished, inform the user of the final file path, file size, and title.
- If the user asks for audio or a podcast, set `format_type="audio"`.
- If a video has multiple dub tracks (e.g. MrBeast) and the user requests a specific language, specify `audio_language`.
