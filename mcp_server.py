"""
ReClip MCP Server - Windows Edition
Model Context Protocol server for downloading media and managing ReClip downloads on Windows.
Powered by yt-dlp, FFmpeg, and the ReClip engine.
"""

import os
import sys
import re
import json
import uuid
import glob
import time
import random
import logging
import urllib.request
import urllib.parse
import http.cookiejar
import subprocess
import threading
from typing import Optional, Dict, Any, List

# Configure logging strictly to sys.stderr to prevent stdout JSON-RPC corruption
logging.basicConfig(
    stream=sys.stderr,
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [ReClip-MCP] %(message)s"
)
logger = logging.getLogger("reclip_mcp")

from mcp.server.fastmcp import FastMCP, Context

# Initialize FastMCP Server
mcp = FastMCP(
    "reclip",
    instructions="ReClip Media Downloader MCP Server for Windows. Downloads high-quality video and audio from 1000+ platforms (YouTube, TikTok, Reddit, Instagram, Twitter/X, and more) with format selection and dub track support."
)

# Base Paths & Executables
CREATION_FLAGS = (
    subprocess.CREATE_NO_WINDOW
    if os.name == "nt" and hasattr(subprocess, "CREATE_NO_WINDOW")
    else (0x08000000 if os.name == "nt" else 0)
)

def get_base_dir() -> str:
    return os.path.dirname(os.path.abspath(__file__))

def get_config_dir() -> str:
    app_data = os.environ.get("APPDATA") or os.path.expanduser("~")
    reclip_dir = os.path.join(app_data, "ReClip")
    os.makedirs(reclip_dir, exist_ok=True)
    return reclip_dir

def get_config_path() -> str:
    return os.path.join(get_config_dir(), "reclip_config.json")

def get_default_download_dir() -> str:
    user_downloads = os.path.join(os.path.expanduser("~"), "Downloads")
    if os.path.exists(user_downloads):
        reclip_dl = os.path.join(user_downloads, "ReClip")
        os.makedirs(reclip_dl, exist_ok=True)
        return reclip_dl
    local_dl = os.path.join(get_base_dir(), "downloads")
    os.makedirs(local_dl, exist_ok=True)
    return local_dl

def get_download_dir() -> str:
    config_path = get_config_path()
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                config = json.load(f)
                d = config.get("download_dir")
                if d and os.path.exists(d):
                    return os.path.abspath(d)
        except Exception as e:
            logger.warning(f"Error reading reclip_config.json: {e}")
    return os.path.abspath(get_default_download_dir())

def set_download_dir(path: str) -> str:
    config_path = get_config_path()
    config: Dict[str, Any] = {}
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                config = json.load(f)
        except Exception:
            pass
    abs_path = os.path.abspath(path)
    os.makedirs(abs_path, exist_ok=True)
    config["download_dir"] = abs_path
    with open(config_path, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2)
    return abs_path

def resolve_ytdlp_bin() -> str:
    # 1. Bundled bin/yt-dlp.exe
    bundled = os.path.join(get_base_dir(), "bin", "yt-dlp.exe")
    if os.path.exists(bundled):
        return bundled
    # 2. System PATH
    import shutil
    sys_path = shutil.which("yt-dlp.exe") or shutil.which("yt-dlp")
    if sys_path:
        return sys_path
    # 3. Python script path
    py_script = os.path.join(os.path.dirname(sys.executable), "Scripts", "yt-dlp.exe")
    if os.path.exists(py_script):
        return py_script
    return "yt-dlp"

def resolve_ffmpeg_dir() -> str:
    # 1. Bundled bin/
    bundled = os.path.join(get_base_dir(), "bin")
    if os.path.exists(os.path.join(bundled, "ffmpeg.exe")):
        return bundled
    # 2. System PATH
    import shutil
    sys_ff = shutil.which("ffmpeg.exe") or shutil.which("ffmpeg")
    if sys_ff:
        return os.path.dirname(sys_ff)
    return ""

def format_size(num_bytes: Optional[float]) -> str:
    if not num_bytes:
        return ""
    try:
        n = float(num_bytes)
        for unit in ["B", "KB", "MB", "GB"]:
            if abs(n) < 1024.0:
                return f"{n:.1f} {unit}"
            n /= 1024.0
        return f"{n:.1f} TB"
    except Exception:
        return str(num_bytes)

def get_random_ip() -> str:
    return f"{random.randint(11, 250)}.{random.randint(0, 255)}.{random.randint(0, 255)}.{random.randint(1, 254)}"

def normalize_url(url: str) -> str:
    if not url:
        return ""
    url = url.strip()
    yt_short = re.search(r'youtu\.be/([a-zA-Z0-9_-]{11})', url)
    if yt_short:
        return f"https://www.youtube.com/watch?v={yt_short.group(1)}"
    yt_watch = re.search(r'youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})', url)
    if yt_watch:
        return f"https://www.youtube.com/watch?v={yt_watch.group(1)}"
    yt_shorts = re.search(r'youtube\.com/shorts/([a-zA-Z0-9_-]{11})', url)
    if yt_shorts:
        return f"https://www.youtube.com/watch?v={yt_shorts.group(1)}"
    reddit_match = re.search(r'(https?://(?:www\.|v\.)?reddit\.com/(?:r|user)/[a-zA-Z0-9_]+/(?:comments|s)/[a-zA-Z0-9_]+(?:/[^/?#]+)?)', url)
    if reddit_match:
        return reddit_match.group(1)
    reddit_short = re.search(r'(https?://redd\.it/[a-zA-Z0-9_]+)', url)
    if reddit_short:
        return reddit_short.group(1)
    return url

def resolve_reddit_url(url: str) -> Optional[Dict[str, str]]:
    if not url:
        return None
    url = url.strip()

    v_match = re.search(r'https?://v\.redd\.it/([a-zA-Z0-9_]+)', url)
    if v_match:
        vid = v_match.group(1)
        return {
            "stream_url": f"https://v.redd.it/{vid}/HLSPlaylist.m3u8",
            "title": f"Reddit_{vid}",
            "thumbnail": ""
        }

    if not any(domain in url for domain in ("reddit.com", "redd.it")):
        return None

    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
    }

    clean_url = url.split("?")[0]
    canonical_url = clean_url
    html = ""
    try:
        req = urllib.request.Request(clean_url, headers=headers)
        with opener.open(req, timeout=12) as resp:
            canonical_url = resp.geturl().split("?")[0]
            html = resp.read().decode("utf-8", errors="ignore")
    except Exception:
        html = ""

    token_match = re.search(r'name=[\'"]jsc_token[\'"]\s+value=[\'"]([^\'"]+)[\'"]', html)
    arg_match = re.search(r'await\(async\s+e=>e\+e\)\([\'"]([^\'"]+)[\'"]\)', html)

    if token_match and arg_match:
        token = token_match.group(1)
        arg = arg_match.group(1)
        solution = arg + arg
        query = urllib.parse.urlencode({
            "solution": solution,
            "js_challenge": "1",
            "jsc_token": token,
            "jsc_orig_r": ""
        })
        base = canonical_url.split("?")[0]
        challenge_url = f"{base}?{query}"
        try:
            req2 = urllib.request.Request(challenge_url, headers=headers)
            with opener.open(req2, timeout=12) as resp2:
                html = resp2.read().decode("utf-8", errors="ignore")
        except Exception:
            pass

    title = ""
    thumbnail = ""
    stream_url = ""

    t_match = re.search(r'<shreddit-title[^>]*\stitle=[\'"]([^\'"]+)[\'"]', html)
    if t_match:
        title = re.sub(r'\s*:\s*r/\w+.*$', '', t_match.group(1)).strip()
    if not title:
        slug_match = re.search(r'/comments/[a-zA-Z0-9]+/([^/?#]+)', canonical_url)
        if slug_match:
            title = slug_match.group(1).replace('_', ' ').replace('-', ' ').title()

    p_match = re.search(r'poster=[\'"]([^\'"]+)[\'"]', html)
    if p_match:
        thumbnail = p_match.group(1).replace('&amp;', '&')
    if not thumbnail:
        og_img = re.search(r'property=[\'"]og:image[\'"]\s+content=[\'"]([^\'"]+)[\'"]', html)
        if og_img:
            thumbnail = og_img.group(1).replace('&amp;', '&')

    shreddit_src = re.search(r'<shreddit-player[^>]*\ssrc=[\'"]([^\'"]+)[\'"]', html)
    if shreddit_src:
        stream_url = urllib.parse.unquote(shreddit_src.group(1)).replace('&amp;', '&')
    else:
        v_in_html = re.search(r'https?://v\.redd\.it/([a-zA-Z0-9_]+)', html)
        if v_in_html:
            stream_url = f"https://v.redd.it/{v_in_html.group(1)}/HLSPlaylist.m3u8"

    if not stream_url:
        try:
            rs_url = f"https://rapidsave.com/info?url={urllib.parse.quote(canonical_url)}"
            req_rs = urllib.request.Request(rs_url, headers=headers)
            with urllib.request.urlopen(req_rs, timeout=8) as resp_rs:
                rs_html = resp_rs.read().decode("utf-8", errors="ignore")
                rs_v = re.search(r'https?://v\.redd\.it/([a-zA-Z0-9_]+)', rs_html)
                if rs_v:
                    stream_url = f"https://v.redd.it/{rs_v.group(1)}/HLSPlaylist.m3u8"
                else:
                    rs_dl = re.search(r'href=[\'"](https?://sd\.rapidsave\.com/download\.php\?[^\'"]+)[\'"]', rs_html)
                    if rs_dl:
                        stream_url = rs_dl.group(1).replace('&amp;', '&')
        except Exception:
            pass

    if stream_url:
        return {
            "stream_url": stream_url,
            "title": title or "Reddit Video",
            "thumbnail": thumbnail
        }
    return None

def parse_ytdlp_json(stdout: str) -> Dict[str, Any]:
    for line in stdout.splitlines():
        line = line.strip()
        if not line or not (line.startswith("{") and line.endswith("}")):
            continue
        try:
            return json.loads(line)
        except Exception:
            continue
    raise ValueError("yt-dlp returned no valid JSON data")

def sanitize_filename(name: str) -> str:
    if not name:
        return ""
    clean = re.sub(r'[\\/*?:"<>|]', "", name).strip()
    clean = re.sub(r'\s+', " ", clean).strip()
    return clean[:120].strip()

# Background download tracking
active_jobs: Dict[str, Dict[str, Any]] = {}
job_lock = threading.Lock()

percent_re = re.compile(r'\[download\]\s+(\d+\.?\d*)%\s+of\s+([~\w\.\s\+]+)\s+at\s+([\w\.\/\s]+)\s+ETA\s+([\d:]+)')
simple_percent_re = re.compile(r'\[download\]\s+(\d+\.?\d*)%')

def execute_download(
    job_id: str,
    url: str,
    format_type: str = "video",
    quality: str = "1080",
    audio_lang: str = "",
    custom_title: str = "",
    output_dir: str = "",
    cookies_file: str = ""
) -> Dict[str, Any]:
    with job_lock:
        job = active_jobs.setdefault(job_id, {
            "job_id": job_id,
            "status": "downloading",
            "percent": 0.0,
            "progress_str": "Starting download...",
            "filesize": "",
            "file": "",
            "filename": "",
            "error": "",
            "created_at": time.time(),
        })

    url = normalize_url(url)
    reddit_meta = resolve_reddit_url(url)
    target_url = url
    resolved_title = custom_title

    if reddit_meta:
        target_url = reddit_meta["stream_url"]
        if not resolved_title and reddit_meta.get("title"):
            resolved_title = reddit_meta["title"]

    dl_dir = os.path.abspath(output_dir) if output_dir else get_download_dir()
    os.makedirs(dl_dir, exist_ok=True)

    out_template = os.path.join(dl_dir, f"{job_id}___%(title).140B.%(ext)s")
    ytdlp_bin = resolve_ytdlp_bin()
    ffmpeg_dir = resolve_ffmpeg_dir()

    cmd = [
        ytdlp_bin,
        "-4",
        "--newline",
        "--no-playlist",
        "--geo-bypass",
        "--no-check-certificates",
        "--socket-timeout", "20",
        "--add-header", f"X-Forwarded-For: {get_random_ip()}",
        "-o", out_template
    ]

    if ffmpeg_dir and os.path.exists(ffmpeg_dir):
        cmd += ["--ffmpeg-location", ffmpeg_dir]

    # Resolve Cookies
    if cookies_file and os.path.exists(cookies_file):
        cmd += ["--cookies", cookies_file]
    else:
        default_cookies = os.path.join(dl_dir, "cookies.txt")
        if os.path.exists(default_cookies):
            cmd += ["--cookies", default_cookies]

    # Format & Quality handling
    f_type = (format_type or "video").lower()
    q_str = str(quality or "1080").lower().strip()
    a_lang = (audio_lang or "").strip().lower()

    if a_lang == "all":
        cmd += ["--audio-multistreams"]
        if f_type == "audio":
            cmd += ["-x", "--audio-format", "mp3"]
        elif q_str.isdigit():
            h = int(q_str)
            cmd += ["-f", f"bestvideo[height<={h}]+bestaudio/best[height<={h}]/best", "--merge-output-format", "mkv"]
        else:
            cmd += ["-f", "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best", "--merge-output-format", "mkv"]
    elif a_lang and a_lang not in ("original", "default", "auto", "none", ""):
        if f_type == "audio":
            cmd += ["-f", f"bestaudio[language^={a_lang}][ext=m4a]/bestaudio[language^={a_lang}]/bestaudio", "-x", "--audio-format", "mp3"]
        elif q_str.isdigit():
            h = int(q_str)
            cmd += ["-f", f"bestvideo[height<={h}]+bestaudio[language^={a_lang}][ext=m4a]/bestvideo[height<={h}]+bestaudio[language^={a_lang}]/bestvideo[height<={h}]+bestaudio/best[height<={h}]/best", "--merge-output-format", "mp4"]
        else:
            cmd += ["-f", f"bestvideo[height<=1080]+bestaudio[language^={a_lang}][ext=m4a]/bestvideo[height<=1080]+bestaudio[language^={a_lang}]/bestvideo[height<=1080]+bestaudio/best[height<=1080]/best", "--merge-output-format", "mp4"]
    else:
        if f_type == "audio":
            cmd += ["-x", "--audio-format", "mp3"]
        elif q_str.isdigit():
            h = int(q_str)
            cmd += ["-f", f"bestvideo[height<={h}]+bestaudio[ext=m4a]/bestvideo[height<={h}]+bestaudio/best[height<={h}]/best", "--merge-output-format", "mp4"]
        elif q_str == "best":
            cmd += ["-f", "bestvideo+bestaudio/best", "--merge-output-format", "mp4"]
        else:
            cmd += ["-f", "bestvideo[height<=1080]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best[height<=1080]/best", "--merge-output-format", "mp4"]

    cmd.append(target_url)

    logger.info(f"Running download job {job_id} with command: {' '.join(cmd)}")

    try:
        process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            creationflags=CREATION_FLAGS
        )

        output_lines = []
        for line in iter(process.stdout.readline, ''):
            line_str = line.strip()
            if not line_str:
                continue
            output_lines.append(line_str)

            m = percent_re.search(line_str)
            if m:
                pct = float(m.group(1))
                size_str = m.group(2).strip()
                speed = m.group(3).strip()
                eta = m.group(4).strip()
                with job_lock:
                    job["percent"] = pct
                    job["filesize"] = size_str
                    job["progress_str"] = f"{pct:.1f}% of {size_str} ({speed}, ETA {eta})"
            else:
                m_simple = simple_percent_re.search(line_str)
                if m_simple:
                    pct = float(m_simple.group(1))
                    with job_lock:
                        job["percent"] = pct
                        job["progress_str"] = f"{pct:.1f}%"
                elif "[ExtractAudio]" in line_str or "[Merger]" in line_str:
                    with job_lock:
                        job["percent"] = 99.0
                        job["progress_str"] = "Processing video/audio multiplexing..."

        process.stdout.close()
        returncode = process.wait(timeout=600)

        if returncode != 0:
            error_msg = "Download process terminated with non-zero exit code"
            for l in reversed(output_lines):
                if "ERROR:" in l or "Errno" in l or "Permission denied" in l:
                    error_msg = l.replace("ERROR:", "").strip()
                    break
            with job_lock:
                job["status"] = "error"
                job["error"] = error_msg
            return job

        # Locate downloaded output files in destination directory
        try:
            all_dl_files = os.listdir(dl_dir)
        except Exception:
            all_dl_files = []

        files = [
            os.path.join(dl_dir, f) for f in all_dl_files
            if f.startswith(f"{job_id}") and not f.endswith((".part", ".ytdl", ".temp"))
        ]

        if not files:
            err_cand = [l for l in output_lines if "ERROR:" in l or "Errno" in l or "Permission denied" in l]
            with job_lock:
                job["status"] = "error"
                job["error"] = err_cand[-1] if err_cand else "Download completed but output file was not found on disk"
            return job

        if f_type == "audio":
            target = [f for f in files if f.endswith((".mp3", ".m4a", ".opus"))]
            chosen = target[0] if target else files[0]
        else:
            target = [f for f in files if f.endswith((".mp4", ".mkv", ".webm"))]
            chosen = target[0] if target else files[0]

        # Cleanup leftover fragments
        for f in files:
            if f != chosen:
                try:
                    os.remove(f)
                except OSError:
                    pass

        # Extract & sanitize human readable title
        chosen_basename = os.path.basename(chosen)
        ext = os.path.splitext(chosen)[1]

        if "___" in chosen_basename:
            extracted_title = chosen_basename.split("___", 1)[1]
            extracted_title = os.path.splitext(extracted_title)[0]
        else:
            extracted_title = os.path.splitext(chosen_basename)[0]

        sanitized_user = sanitize_filename(resolved_title)
        video_title = sanitized_user or sanitize_filename(extracted_title) or f"ReClip_Media_{job_id}"

        target_filename = f"{video_title}{ext}"
        target_path = os.path.join(dl_dir, target_filename)

        # Handle collisions
        if os.path.exists(target_path) and os.path.abspath(target_path) != os.path.abspath(chosen):
            count = 1
            while os.path.exists(os.path.join(dl_dir, f"{video_title} ({count}){ext}")):
                count += 1
            target_filename = f"{video_title} ({count}){ext}"
            target_path = os.path.join(dl_dir, target_filename)

        try:
            if os.path.abspath(chosen) != os.path.abspath(target_path):
                os.replace(chosen, target_path)
            final_file = target_path
            final_filename = target_filename
        except Exception:
            final_file = chosen
            final_filename = os.path.basename(chosen)

        actual_size = os.path.getsize(final_file)
        formatted_size = format_size(actual_size)

        with job_lock:
            job["status"] = "done"
            job["percent"] = 100.0
            job["filesize"] = formatted_size
            job["filesize_bytes"] = actual_size
            job["progress_str"] = "Completed"
            job["file"] = final_file
            job["filename"] = final_filename
            job["title"] = video_title

        logger.info(f"Download complete for job {job_id}: {final_file} ({formatted_size})")
        return job

    except subprocess.TimeoutExpired:
        with job_lock:
            job["status"] = "error"
            job["error"] = "Download timed out (10 minute limit exceeded)"
        return job
    except Exception as e:
        with job_lock:
            job["status"] = "error"
            job["error"] = str(e)
        return job


# -----------------------------------------------------------------------------
# FastMCP Tools
# -----------------------------------------------------------------------------

@mcp.tool()
def download_media(
    url: str,
    format_type: str = "video",
    quality: str = "1080",
    audio_language: str = "",
    custom_title: str = "",
    output_dir: str = "",
    cookies_file: str = "",
    wait_for_completion: bool = True
) -> Dict[str, Any]:
    """Download video or audio from YouTube, TikTok, Reddit, Instagram, Twitter/X, and 1000+ sites to Windows disk.

    Args:
        url: URL of the media (YouTube, Reddit, TikTok, Instagram, Twitter/X, Twitch, etc.).
        format_type: 'video' or 'audio' (audio will be converted to MP3). Default is 'video'.
        quality: Video height limit ('360', '480', '720', '1080', '1440', '2160', or 'best'). Default is '1080'.
        audio_language: Multi-audio dubbed language code (e.g., 'en', 'es', 'hi', 'ml') or 'all' for multi-audio MKV.
        custom_title: Optional custom name for the saved file.
        output_dir: Custom download directory path. Defaults to ReClip Downloads directory (e.g. D:\\reclip downloads).
        cookies_file: Optional path to cookies.txt (defaults to cookies.txt in the downloads folder).
        wait_for_completion: If True, waits for the download to finish and returns the final file path and size. If False, runs in background and returns job_id immediately.
    """
    job_id = uuid.uuid4().hex[:10]

    if not wait_for_completion:
        t = threading.Thread(
            target=execute_download,
            args=(job_id, url, format_type, quality, audio_language, custom_title, output_dir, cookies_file),
            daemon=True
        )
        t.start()
        return {
            "status": "downloading",
            "job_id": job_id,
            "url": url,
            "message": "Download started in background. Use get_download_status(job_id) to check progress."
        }

    # Synchronous execution
    result = execute_download(
        job_id, url, format_type, quality, audio_language, custom_title, output_dir, cookies_file
    )

    if result.get("status") == "done":
        return {
            "status": "success",
            "job_id": job_id,
            "title": result.get("title", ""),
            "file_path": result.get("file", ""),
            "filename": result.get("filename", ""),
            "filesize": result.get("filesize", ""),
            "filesize_bytes": result.get("filesize_bytes", 0),
            "format": format_type,
            "quality": quality,
            "url": url,
            "message": f"Successfully downloaded '{result.get('filename')}' to {result.get('file')}"
        }
    else:
        return {
            "status": "error",
            "job_id": job_id,
            "error": result.get("error", "Unknown error during download"),
            "url": url
        }


@mcp.tool()
def get_media_info(url: str, cookies_file: str = "") -> Dict[str, Any]:
    """Inspect and extract detailed metadata, available resolutions, and dub tracks for a media URL without downloading.

    Args:
        url: The media URL to inspect.
        cookies_file: Optional path to cookies.txt.
    """
    url = normalize_url(url)
    reddit_meta = resolve_reddit_url(url)
    target_url = reddit_meta["stream_url"] if reddit_meta else url

    ytdlp_bin = resolve_ytdlp_bin()
    cmd = [
        ytdlp_bin,
        "-4",
        "--no-playlist",
        "--geo-bypass",
        "--no-check-certificates",
        "--socket-timeout", "20",
        "--add-header", f"X-Forwarded-For: {get_random_ip()}",
        "-j"
    ]

    dl_dir = get_download_dir()
    if cookies_file and os.path.exists(cookies_file):
        cmd += ["--cookies", cookies_file]
    elif os.path.exists(os.path.join(dl_dir, "cookies.txt")):
        cmd += ["--cookies", os.path.join(dl_dir, "cookies.txt")]

    cmd.append(target_url)

    try:
        res = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
            creationflags=CREATION_FLAGS
        )

        if res.returncode != 0:
            err = res.stderr.strip().splitlines()[-1] if res.stderr else "Failed to inspect media URL"
            return {"status": "error", "error": err, "url": url}

        info = parse_ytdlp_json(res.stdout)
        title = info.get("title", "")
        if reddit_meta:
            if not title or title in ("HLSPlaylist", "DASHPlaylist"):
                title = reddit_meta.get("title") or "Reddit Video"

        # Formats extraction
        best_by_height: Dict[int, Any] = {}
        for f in info.get("formats", []):
            h = f.get("height")
            if h and isinstance(h, int) and h >= 144:
                tbr = f.get("tbr") or 0
                if h not in best_by_height or tbr > (best_by_height[h].get("tbr") or 0):
                    best_by_height[h] = f

        best_audio_size = 0.0
        for f in info.get("formats", []):
            if f.get("acodec") != "none" and f.get("vcodec") == "none":
                s = f.get("filesize_approx") or f.get("filesize") or 0
                if not s:
                    abr = f.get("abr") or f.get("tbr")
                    dur = info.get("duration")
                    if abr and dur:
                        s = (abr * 1024 / 8) * dur
                if s > best_audio_size:
                    best_audio_size = float(s)

        formats = []
        for h in sorted(best_by_height.keys(), reverse=True):
            f = best_by_height[h]
            v_size = f.get("filesize_approx") or f.get("filesize") or 0
            if not v_size:
                vbr = f.get("vbr") or f.get("tbr")
                dur = info.get("duration")
                if vbr and dur:
                    v_size = (vbr * 1024 / 8) * dur
            total_size = (float(v_size) + best_audio_size) if (v_size and best_audio_size) else 0
            formats.append({
                "height": h,
                "label": f"{h}p",
                "estimated_size": format_size(total_size) if total_size else "Variable"
            })

        # Audio dub languages
        audio_languages = []
        seen = set()
        for f in info.get("formats", []):
            if f.get("vcodec") in ("none", None) and f.get("language"):
                lang = (f.get("language") or "").lower().strip()
                base_lang = lang.split("-")[0]
                if base_lang and base_lang not in seen:
                    seen.add(base_lang)
                    audio_languages.append(base_lang)

        return {
            "status": "success",
            "title": title,
            "uploader": info.get("uploader", ""),
            "duration": info.get("duration"),
            "duration_str": f"{int(info.get('duration', 0)) // 60}:{int(info.get('duration', 0)) % 60:02d}" if info.get("duration") else "Unknown",
            "thumbnail": reddit_meta.get("thumbnail") if (reddit_meta and reddit_meta.get("thumbnail")) else info.get("thumbnail", ""),
            "available_resolutions": formats,
            "audio_dub_languages": audio_languages,
            "platform": info.get("extractor", ""),
            "url": url
        }

    except subprocess.TimeoutExpired:
        return {"status": "error", "error": "Metadata inspection timed out after 30 seconds", "url": url}
    except Exception as e:
        return {"status": "error", "error": str(e), "url": url}


@mcp.tool()
def get_download_status(job_id: str) -> Dict[str, Any]:
    """Check the real-time progress and status of a background media download job.

    Args:
        job_id: The job ID returned from a background download_media call.
    """
    with job_lock:
        job = active_jobs.get(job_id)
        if not job:
            return {"status": "not_found", "error": f"No job found with ID {job_id}"}
        return {
            "job_id": job_id,
            "status": job["status"],
            "percent": job.get("percent", 0.0),
            "progress_str": job.get("progress_str", ""),
            "filesize": job.get("filesize", ""),
            "file": job.get("file", ""),
            "filename": job.get("filename", ""),
            "error": job.get("error", "")
        }


@mcp.tool()
def list_downloads(limit: int = 20, output_dir: str = "") -> List[Dict[str, Any]]:
    """List recent downloaded media files from the ReClip downloads folder on Windows.

    Args:
        limit: Maximum number of files to return (default: 20).
        output_dir: Optional custom directory to scan.
    """
    dl_dir = os.path.abspath(output_dir) if output_dir else get_download_dir()
    if not os.path.exists(dl_dir):
        return []

    entries = []
    try:
        for f in os.listdir(dl_dir):
            if f.endswith((".part", ".ytdl", ".temp", ".log")) or f == "cookies.txt":
                continue
            full_path = os.path.join(dl_dir, f)
            if os.path.isfile(full_path):
                stat = os.stat(full_path)
                ext = os.path.splitext(f)[1].lower()
                is_media = ext in (".mp4", ".mkv", ".webm", ".mp3", ".m4a", ".opus", ".wav", ".flac")
                entries.append({
                    "filename": f,
                    "file_path": full_path,
                    "size": format_size(stat.st_size),
                    "size_bytes": stat.st_size,
                    "modified_time": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(stat.st_mtime)),
                    "extension": ext,
                    "is_media": is_media,
                    "_mtime": stat.st_mtime
                })
    except Exception as e:
        logger.warning(f"Error listing downloads: {e}")

    entries.sort(key=lambda x: x["_mtime"], reverse=True)
    for e in entries:
        del e["_mtime"]
    return entries[:limit]


@mcp.tool()
def get_config() -> Dict[str, Any]:
    """Retrieve ReClip Windows configuration, paths, yt-dlp version, and cookies status."""
    dl_dir = get_download_dir()
    cookies_path = os.path.join(dl_dir, "cookies.txt")

    ytdlp_bin = resolve_ytdlp_bin()
    ytdlp_ver = "Unknown"
    try:
        res = subprocess.run([ytdlp_bin, "--version"], capture_output=True, text=True, timeout=5, creationflags=CREATION_FLAGS)
        if res.returncode == 0:
            ytdlp_ver = res.stdout.strip()
    except Exception:
        pass

    ffmpeg_dir = resolve_ffmpeg_dir()
    ffmpeg_ver = "Unknown"
    try:
        ff_bin = os.path.join(ffmpeg_dir, "ffmpeg.exe") if ffmpeg_dir else "ffmpeg"
        res = subprocess.run([ff_bin, "-version"], capture_output=True, text=True, timeout=5, creationflags=CREATION_FLAGS)
        if res.returncode == 0:
            ffmpeg_ver = res.stdout.splitlines()[0] if res.stdout else "Available"
    except Exception:
        pass

    return {
        "download_directory": dl_dir,
        "default_download_directory": get_default_download_dir(),
        "config_path": get_config_path(),
        "cookies_file": cookies_path if os.path.exists(cookies_path) else None,
        "cookies_present": os.path.exists(cookies_path),
        "ytdlp_path": ytdlp_bin,
        "ytdlp_version": ytdlp_ver,
        "ffmpeg_directory": ffmpeg_dir,
        "ffmpeg_version": ffmpeg_ver
    }


@mcp.tool()
def set_reclip_download_dir(directory_path: str) -> Dict[str, Any]:
    """Set the default download directory for ReClip Windows Edition.

    Args:
        directory_path: Absolute directory path on Windows to save future downloads.
    """
    try:
        saved_path = set_download_dir(directory_path)
        return {
            "status": "success",
            "download_directory": saved_path,
            "message": f"Successfully updated ReClip download directory to: {saved_path}"
        }
    except Exception as e:
        return {"status": "error", "error": str(e)}


@mcp.tool()
def open_downloads_folder() -> Dict[str, Any]:
    """Open the ReClip downloads folder in Windows File Explorer."""
    dl_dir = get_download_dir()
    os.makedirs(dl_dir, exist_ok=True)
    try:
        if os.name == "nt":
            try:
                os.startfile(dl_dir)
            except Exception:
                subprocess.Popen(["explorer", dl_dir], creationflags=CREATION_FLAGS)
        return {"status": "success", "opened_directory": dl_dir}
    except Exception as e:
        return {"status": "error", "error": str(e)}


@mcp.tool()
def list_supported_sites(search_query: str = "") -> Dict[str, Any]:
    """List or search media platforms supported by ReClip.

    Args:
        search_query: Optional keyword to filter supported platforms (e.g., 'tiktok', 'reddit', 'youtube').
    """
    popular = [
        "YouTube (Videos, Shorts, Live Streams, Playlists)",
        "TikTok (Videos, Slideshows, Audio)",
        "Instagram (Reels, Posts, Stories, IGTV)",
        "Reddit (Posts, Videos, v.redd.it, HLS streams)",
        "Twitter / X (Videos, GIFs)",
        "Facebook (Reels, Public Videos)",
        "Twitch (Clips, VODs)",
        "Vimeo",
        "SoundCloud",
        "Pinterest",
        "Loom",
        "Threads",
        "Dailymotion",
        "Bilibili",
        "Bandcamp",
        "Streamable"
    ]

    if not search_query:
        return {
            "popular_platforms": popular,
            "total_supported": "1000+ websites and services via yt-dlp extraction engine"
        }

    q = search_query.lower()
    matches = [p for p in popular if q in p.lower()]
    return {
        "query": search_query,
        "matches": matches if matches else [f"Generic extraction supported for '{search_query}' via yt-dlp"]
    }


# -----------------------------------------------------------------------------
# FastMCP Resources
# -----------------------------------------------------------------------------

@mcp.resource("reclip://config")
def resource_config() -> str:
    """Current ReClip configuration as JSON."""
    return json.dumps(get_config(), indent=2)

@mcp.resource("reclip://downloads")
def resource_downloads() -> str:
    """Latest 20 downloaded files in ReClip as JSON."""
    return json.dumps(list_downloads(20), indent=2)


# -----------------------------------------------------------------------------
# Main Entry Point
# -----------------------------------------------------------------------------

if __name__ == "__main__":
    logger.info("Starting ReClip MCP Server for Windows (transport=stdio)...")
    mcp.run(transport="stdio")
