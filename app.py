import os

import re

import uuid

import glob

import json

import subprocess

import threading

from flask import Flask, request, jsonify, send_file, render_template



app = Flask(__name__)

import sys



@app.before_request

def handle_preflight():

    if request.method == "OPTIONS":

        response = app.make_default_options_response()

        response.headers['Access-Control-Allow-Origin'] = '*'

        response.headers['Access-Control-Allow-Methods'] = 'GET, POST, OPTIONS'

        response.headers['Access-Control-Allow-Headers'] = 'Content-Type, Authorization'

        return response



@app.after_request

def add_cors_headers(response):

    response.headers['Access-Control-Allow-Origin'] = '*'

    response.headers['Access-Control-Allow-Methods'] = 'GET, POST, OPTIONS'

    response.headers['Access-Control-Allow-Headers'] = 'Content-Type, Authorization'

    return response



import time

import shutil



def get_config_dir():

    app_data = os.environ.get("APPDATA") or os.path.expanduser("~")

    reclip_dir = os.path.join(app_data, "ReClip")

    os.makedirs(reclip_dir, exist_ok=True)

    return reclip_dir



def get_config_path():

    return os.path.join(get_config_dir(), "reclip_config.json")



def get_default_download_dir():

    user_downloads = os.path.join(os.path.expanduser("~"), "Downloads")

    if os.path.exists(user_downloads):

        reclip_dl = os.path.join(user_downloads, "ReClip")

        os.makedirs(reclip_dl, exist_ok=True)

        return reclip_dl

    local_dl = os.path.join(os.path.dirname(os.path.abspath(__file__)), "downloads")

    os.makedirs(local_dl, exist_ok=True)

    return local_dl



def get_config():

    config_path = get_config_path()

    config = {

        "download_dir": get_default_download_dir(),

        "auto_update_engine": True,
        "streamable_download": True,

        "last_engine_check": 0

    }

    if os.path.exists(config_path):

        try:

            with open(config_path, "r", encoding="utf-8") as f:

                loaded = json.load(f)

                if isinstance(loaded, dict):

                    config.update(loaded)

        except Exception:

            pass

    return config



def save_config(config):

    config_path = get_config_path()

    try:

        with open(config_path, "w", encoding="utf-8") as f:

            json.dump(config, f, indent=2)

    except Exception:

        pass



def get_download_dir():

    config = get_config()

    d = config.get("download_dir")

    if d and os.path.exists(d):

        return os.path.abspath(d)

    return os.path.abspath(get_default_download_dir())



def set_download_dir(path):

    config = get_config()

    config["download_dir"] = os.path.abspath(path)

    save_config(config)



if getattr(sys, 'frozen', False):

    base_path = sys._MEIPASS

else:

    base_path = os.path.dirname(os.path.abspath(__file__))



def get_ytdlp_bin():

    # 1. User appdata updated binary (guaranteed user-writable without Admin privileges)

    appdata_bin = os.path.join(get_config_dir(), "bin", "yt-dlp.exe")

    if os.path.isfile(appdata_bin):

        return appdata_bin



    # 2. Frozen PyInstaller package binary

    if getattr(sys, 'frozen', False):

        alt_path = os.path.join(os.path.dirname(sys.executable), "_internal", "bin", "yt-dlp.exe")

        if os.path.isfile(alt_path):

            return alt_path

        alt_root = os.path.join(os.path.dirname(sys.executable), "bin", "yt-dlp.exe")

        if os.path.isfile(alt_root):

            return alt_root

        bundled = os.path.join(sys._MEIPASS, "bin", "yt-dlp.exe")

        if os.path.isfile(bundled):

            return bundled

    else:

        local_bin = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bin", "yt-dlp.exe")

        if os.path.isfile(local_bin):

            return local_bin



    # 3. Installed ReClip directory fallback

    d_reclip = r"D:\ReClip\_internal\bin\yt-dlp.exe"

    if os.path.isfile(d_reclip):

        return d_reclip



    # 4. System PATH

    sys_path = shutil.which("yt-dlp.exe") or shutil.which("yt-dlp")

    if sys_path:

        return sys_path



    py_scripts = os.path.join(os.path.dirname(sys.executable), "Scripts", "yt-dlp.exe")

    if os.path.isfile(py_scripts):

        return py_scripts



    return "yt-dlp"



def get_ffmpeg_dir():

    # 1. User appdata bin

    appdata_ff = os.path.join(get_config_dir(), "bin")

    if os.path.isfile(os.path.join(appdata_ff, "ffmpeg.exe")):

        return appdata_ff

    # 2. Frozen package

    if getattr(sys, 'frozen', False):

        alt_ffmpeg = os.path.join(os.path.dirname(sys.executable), "_internal", "bin")

        if os.path.isfile(os.path.join(alt_ffmpeg, "ffmpeg.exe")):

            return alt_ffmpeg

        bundled = os.path.join(sys._MEIPASS, "bin")

        if os.path.isfile(os.path.join(bundled, "ffmpeg.exe")):

            return bundled

    else:

        local_ff = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bin")

        if os.path.isfile(os.path.join(local_ff, "ffmpeg.exe")):

            return local_ff

    d_ff = r"D:\ReClip\_internal\bin"

    if os.path.isfile(os.path.join(d_ff, "ffmpeg.exe")):

        return d_ff

    sys_ff = shutil.which("ffmpeg.exe") or shutil.which("ffmpeg")

    if sys_ff:

        return os.path.dirname(sys_ff)

    return ""



def get_ffmpeg_bin():
    d = get_ffmpeg_dir()
    if d:
        p = os.path.join(d, "ffmpeg.exe" if os.name == "nt" else "ffmpeg")
        if os.path.isfile(p):
            return p
    return shutil.which("ffmpeg.exe") or shutil.which("ffmpeg") or "ffmpeg"

FFMPEG_BIN = get_ffmpeg_bin()

def sanitize_system_path():
    """
    Sanitizes the process PATH environment variable.
    Resolves NTFS junctions and symlinks to real physical directories (os.path.realpath)
    and drops untrusted mount points or unreadable paths to prevent [WinError 448]
    (ERROR_UNTRUSTED_MOUNT_POINT) when Windows Redirection Guard is enforced by WebView2.
    """
    raw_path = os.environ.get("PATH", "")
    if not raw_path:
        return
    clean_parts = []
    seen = set()
    for part in raw_path.split(os.pathsep):
        part = part.strip()
        if not part:
            continue
        try:
            norm = os.path.normpath(part)
            real = os.path.realpath(norm)
            if os.path.isdir(real):
                lower_real = real.lower()
                if lower_real not in seen:
                    seen.add(lower_real)
                    clean_parts.append(real)
        except OSError:
            continue
    os.environ["PATH"] = os.pathsep.join(clean_parts)

# Sanitize system PATH on startup
sanitize_system_path()

def get_clean_env():
    """
    Returns an environment dictionary with sanitized PATH, resolving junctions and
    dropping untrusted mount points to avoid WinError 448 under WebView2 Redirection Guard.
    """
    sanitize_system_path()
    return os.environ.copy()

def get_node_bin():

    candidates = [

        os.path.join(os.path.dirname(sys.executable), "_internal", "bin", "node.exe") if getattr(sys, 'frozen', False) else "",

        os.path.join(os.path.dirname(os.path.abspath(__file__)), "bin", "node.exe"),

        r"C:\Program Files\nodejs\node.exe",

        r"C:\Program Files (x86)\nodejs\node.exe",

        os.path.expandvars(r"%LOCALAPPDATA%\Programs\nodejs\node.exe"),

        shutil.which("node.exe") or shutil.which("node") or ""

    ]

    for c in candidates:

        if c and os.path.isfile(c):

            return os.path.abspath(c)

    return ""



def get_ytdlp_version():

    ytdlp = get_ytdlp_bin()

    try:

        res = subprocess.run([ytdlp, "--version"], capture_output=True, text=True, timeout=8, creationflags=CREATION_FLAGS, env=get_clean_env())

        if res.returncode == 0:

            return res.stdout.strip()

    except Exception:

        pass

    return "Unknown"



engine_update_lock = threading.Lock()

is_engine_updating = False



def perform_engine_update():

    global is_engine_updating

    with engine_update_lock:

        if is_engine_updating:

            return {"success": True, "message": "Engine update already in progress"}

        is_engine_updating = True



    try:

        ytdlp = get_ytdlp_bin()

        updated = False

        update_message = ""



        # Method 1: native yt-dlp update

        try:

            res = subprocess.run(

                [ytdlp, "--update"],

                capture_output=True,

                text=True,

                timeout=120,

                creationflags=CREATION_FLAGS,
                env=get_clean_env()

            )

            output = (res.stdout + "\n" + res.stderr).strip()

            if res.returncode == 0 and ("Updated yt-dlp" in output or "is up to date" in output):

                updated = True

                update_message = output.splitlines()[-1]

        except Exception:

            pass



        # Method 2: Download directly from GitHub Releases to %APPDATA%/ReClip/bin/yt-dlp.exe

        if not updated:

            import urllib.request

            target_dir = os.path.join(get_config_dir(), "bin")

            os.makedirs(target_dir, exist_ok=True)

            target_exe = os.path.join(target_dir, "yt-dlp.exe")

            temp_exe = os.path.join(target_dir, "yt-dlp.exe.tmp")

            url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"

            req = urllib.request.Request(url, headers={"User-Agent": "ReClip-AutoUpdater"})

            with urllib.request.urlopen(req, timeout=60) as resp, open(temp_exe, "wb") as out_file:

                shutil.copyfileobj(resp, out_file)

            if os.path.exists(temp_exe) and os.path.getsize(temp_exe) > 1000000:

                if os.path.exists(target_exe):

                    try:

                        os.remove(target_exe)

                    except Exception:

                        pass

                os.replace(temp_exe, target_exe)

                updated = True

                update_message = "Successfully updated engine from GitHub"



        new_version = get_ytdlp_version()

        config = get_config()

        config["last_engine_check"] = time.time()

        config["engine_version"] = new_version

        save_config(config)

        return {"success": True, "version": new_version, "message": update_message or f"Engine up to date: {new_version}"}

    except Exception as e:

        return {"success": False, "error": str(e)}

    finally:

        with engine_update_lock:

            is_engine_updating = False



def background_auto_check():

    time.sleep(3)

    config = get_config()

    if not config.get("auto_update_engine", True):

        return

    now = time.time()

    last_check = config.get("last_engine_check", 0)

    if now - last_check > 43200:

        perform_engine_update()



def build_ytdlp_base_cmd(output_template=None, is_json=False):

    ytdlp = get_ytdlp_bin()

    cmd = [

        ytdlp,

        "-4",

        "--no-playlist",

        "--geo-bypass",

        "--no-check-certificates",

        "--socket-timeout", "20",
        "--cache-dir", os.path.join(get_config_dir(), "cache"),

        "--remote-components", "ejs:github",

        "--extractor-args", "youtube:player_client=default,web",

    ]

    if output_template:

        cmd += ["-o", output_template, "--newline"]

    if is_json:

        cmd.append("-j")



    ffmpeg_dir = get_ffmpeg_dir()

    if ffmpeg_dir and os.path.exists(ffmpeg_dir):

        cmd += ["--ffmpeg-location", ffmpeg_dir]



    node_bin = get_node_bin()

    if node_bin:

        cmd += ["--js-runtimes", f"node:{node_bin}"]



    return cmd



# Aliases

YTDLP_BIN = get_ytdlp_bin()

FFMPEG_DIR = get_ffmpeg_dir()



# Prevent terminal window creation during subprocess calls on Windows

CREATION_FLAGS = (

    subprocess.CREATE_NO_WINDOW

    if os.name == "nt" and hasattr(subprocess, "CREATE_NO_WINDOW")

    else (0x08000000 if os.name == "nt" else 0)

)



jobs = {}



percent_re = re.compile(r'\[download\]\s+(\d+\.?\d*)%\s+of\s+([~\w\.\s\+]+)\s+at\s+([\w\.\/\s]+)\s+ETA\s+([\d:]+)')

simple_percent_re = re.compile(r'\[download\]\s+(\d+\.?\d*)%')





def format_size(num_bytes):

    if not num_bytes:

        return ""

    try:

        num_bytes = float(num_bytes)

        for unit in ['B', 'KB', 'MB', 'GB']:

            if abs(num_bytes) < 1024.0:

                return f"{num_bytes:.1f} {unit}"

            num_bytes /= 1024.0

        return f"{num_bytes:.1f} TB"

    except (ValueError, TypeError):

        return str(num_bytes)



def get_random_ip():

    import random

    return f"{random.randint(11, 250)}.{random.randint(0, 255)}.{random.randint(0, 255)}.{random.randint(1, 254)}"



def parse_ytdlp_json(stdout):

    """

    yt-dlp can produce warnings or extra text before/after the JSON payload.

    Iterate over lines to find and return the first valid JSON object.

    """

    for line in stdout.splitlines():

        line = line.strip()

        if not line or not (line.startswith("{") and line.endswith("}")):

            continue

        try:

            return json.loads(line)

        except Exception:

            continue

    raise ValueError("yt-dlp returned no valid JSON data")



# Node runtime resolved dynamically via get_node_bin()



def normalize_url(url):

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





def resolve_reddit_url(url):

    """

    Resolves Reddit posts, /s/ share links, redd.it, or v.redd.it to a direct HLS m3u8 stream or mp4 URL.

    Returns dict: {'stream_url': str, 'title': str, 'thumbnail': str} or None.

    """

    if not url:

        return None

    url = url.strip()



    v_match = re.search(r'https?://v\.redd\.it/([a-zA-Z0-9_]+)', url)

    if v_match:

        vid = v_match.group(1)

        return {

            'stream_url': f"https://v.redd.it/{vid}/HLSPlaylist.m3u8",

            'title': f"Reddit_{vid}",

            'thumbnail': ""

        }



    if not any(domain in url for domain in ("reddit.com", "redd.it")):

        return None



    import urllib.request

    import urllib.parse

    import http.cookiejar



    cj = http.cookiejar.CookieJar()

    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

    headers = {

        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',

        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',

        'Accept-Language': 'en-US,en;q=0.9',

    }



    clean_url = url.split('?')[0]

    canonical_url = clean_url

    html = ""

    try:

        req = urllib.request.Request(clean_url, headers=headers)

        with opener.open(req, timeout=12) as resp:

            canonical_url = resp.geturl().split('?')[0]

            html = resp.read().decode('utf-8', errors='ignore')

    except Exception:

        html = ""



    token_match = re.search(r'name=[\'"]jsc_token[\'"]\s+value=[\'"]([^\'"]+)[\'"]', html)

    arg_match = re.search(r'await\(async\s+e=>e\+e\)\([\'"]([^\'"]+)[\'"]\)', html)



    if token_match and arg_match:

        token = token_match.group(1)

        arg = arg_match.group(1)

        solution = arg + arg

        query = urllib.parse.urlencode({

            'solution': solution,

            'js_challenge': '1',

            'jsc_token': token,

            'jsc_orig_r': ''

        })

        base = canonical_url.split('?')[0]

        challenge_url = f"{base}?{query}"

        try:

            req2 = urllib.request.Request(challenge_url, headers=headers)

            with opener.open(req2, timeout=12) as resp2:

                html = resp2.read().decode('utf-8', errors='ignore')

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

                rs_html = resp_rs.read().decode('utf-8', errors='ignore')

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

            'stream_url': stream_url,

            'title': title or "Reddit Video",

            'thumbnail': thumbnail

        }

    return None





def run_download(job_id, url, format_choice, format_id, cookies_browser="", audio_lang=""):

    job = jobs[job_id]

    job["percent"] = 0.0

    job["progress_str"] = "Downloading..."

    job["filesize"] = ""

    url = normalize_url(url)

    reddit_meta = resolve_reddit_url(url)

    if reddit_meta:

        url = reddit_meta['stream_url']

        if not job.get("title") and reddit_meta.get("title"):

            job["title"] = reddit_meta["title"]

    dl_dir = get_download_dir()

    os.makedirs(dl_dir, exist_ok=True)

    out_template = os.path.join(dl_dir, f"{job_id}___%(title).140B.%(ext)s")



    cmd = build_ytdlp_base_cmd(output_template=out_template)



    cookies_txt_path = os.path.join(dl_dir, "cookies.txt")

    if os.path.exists(cookies_txt_path):

        cmd += ["--cookies", cookies_txt_path]

    elif cookies_browser:

        cmd += ["--cookies-from-browser", cookies_browser]



    if audio_lang == "all":

        cmd += ["--audio-multistreams"]

        if format_choice == "audio":

            cmd += ["-x", "--audio-format", "mp3"]

        elif format_id and str(format_id).isdigit():

            h = int(format_id)

            cmd += ["-f", f"bestvideo[height<={h}]+bestaudio/best[height<={h}]/best", "--merge-output-format", "mkv"]

        else:

            cmd += ["-f", "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best", "--merge-output-format", "mkv"]

    elif audio_lang and audio_lang not in ("original", "default", "auto", "none", ""):

        if format_choice == "audio":

            cmd += ["-f", f"bestaudio[language^={audio_lang}][ext=m4a]/bestaudio[language^={audio_lang}]/bestaudio", "-x", "--audio-format", "mp3"]

        elif format_id and str(format_id).isdigit():

            h = int(format_id)

            cmd += ["-f", f"bestvideo[height<={h}]+bestaudio[language^={audio_lang}][ext=m4a]/bestvideo[height<={h}]+bestaudio[language^={audio_lang}]/bestvideo[height<={h}]+bestaudio/best[height<={h}]/best", "--merge-output-format", "mp4"]

        else:

            cmd += ["-f", f"bestvideo[height<=1080]+bestaudio[language^={audio_lang}][ext=m4a]/bestvideo[height<=1080]+bestaudio[language^={audio_lang}]/bestvideo[height<=1080]+bestaudio/best[height<=1080]/best", "--merge-output-format", "mp4"]

    else:

        if format_choice == "audio":

            cmd += ["-x", "--audio-format", "mp3"]

        elif format_id:

            if str(format_id).isdigit():

                h = int(format_id)

                cmd += ["-f", f"bestvideo[height<={h}]+bestaudio[ext=m4a]/bestvideo[height<={h}]+bestaudio/best[height<={h}]/best", "--merge-output-format", "mp4"]

            else:

                cmd += ["-f", f"{format_id}+bestaudio/best", "--merge-output-format", "mp4"]

        else:

            cmd += ["-f", "bestvideo[height<=1080]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best[height<=1080]/best", "--merge-output-format", "mp4"]
    # Check if streamable simultaneous download should be used
    config = get_config()
    is_streamable = config.get("streamable_download", True) and format_choice != "audio"

    if is_streamable:
        try:
            # Build format spec for direct URL extraction, prioritizing H.264 + AAC for universal Windows player compatibility
            if audio_lang == "all":
                fmt_spec = f"bestvideo[vcodec^=avc][height<={format_id}]+bestaudio/bestvideo[height<={format_id}]+bestaudio/best[height<={format_id}]/best" if format_id and str(format_id).isdigit() else "bestvideo[vcodec^=avc]+bestaudio/bestvideo+bestaudio/best"
            elif audio_lang and audio_lang not in ("original", "default", "auto", "none", ""):
                fmt_spec = f"bestvideo[vcodec^=avc][height<={format_id}]+bestaudio[language^={audio_lang}]/bestvideo[height<={format_id}]+bestaudio[language^={audio_lang}]/bestvideo+bestaudio/best" if format_id and str(format_id).isdigit() else f"bestvideo[vcodec^=avc]+bestaudio[language^={audio_lang}]/bestvideo+bestaudio[language^={audio_lang}]/best"
            else:
                if format_id and str(format_id).isdigit():
                    fmt_spec = f"bestvideo[vcodec^=avc][height<={format_id}]+bestaudio[ext=m4a]/bestvideo[height<={format_id}]+bestaudio[ext=m4a]/bestvideo[height<={format_id}]+bestaudio/best[height<={format_id}]/best"
                elif format_id:
                    fmt_spec = f"{format_id}+bestaudio/best"
                else:
                    fmt_spec = "bestvideo[vcodec^=avc][height<=1080]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best"

            g_cmd = [*build_ytdlp_base_cmd(), "-g", "--print", "%(title)s\t%(duration)s", "-f", fmt_spec]
            cookies_txt_path = os.path.join(dl_dir, "cookies.txt")
            if os.path.exists(cookies_txt_path):
                g_cmd += ["--cookies", cookies_txt_path]
            elif cookies_browser:
                g_cmd += ["--cookies-from-browser", cookies_browser]
            g_cmd.append(url)

            g_res = subprocess.run(
                g_cmd,
                capture_output=True,
                text=True,
                timeout=30,
                creationflags=CREATION_FLAGS,
                env=get_clean_env()
            )

            if g_res.returncode == 0:
                raw_lines = [l.strip() for l in g_res.stdout.strip().split("\n") if l.strip()]
                if raw_lines:
                    meta_parts = raw_lines[0].split("\t")
                    vid_title = meta_parts[0] if len(meta_parts) > 0 and meta_parts[0] else job.get("title") or "video"
                    try:
                        total_dur = float(meta_parts[1]) if len(meta_parts) > 1 and meta_parts[1] else 0.0
                    except (ValueError, TypeError):
                        total_dur = 0.0
                    stream_urls = raw_lines[1:]

                    if stream_urls:
                        safe_title = re.sub(r'[\\/*?:\"<>|]', '', vid_title).strip() or "video"
                        safe_title = safe_title[:100]
                        final_out = os.path.join(dl_dir, f"{job_id}___{safe_title}.mp4")
                        job["file"] = final_out
                        job["filename"] = os.path.basename(final_out)
                        job["title"] = vid_title

                        ffmpeg_bin = get_ffmpeg_bin()
                        ff_cmd = [ffmpeg_bin, "-y", "-loglevel", "error"]
                        if len(stream_urls) >= 2:
                            ff_cmd += [
                                "-headers", "User-Agent: Mozilla/5.0\r\n",
                                "-i", stream_urls[0],
                                "-headers", "User-Agent: Mozilla/5.0\r\n",
                                "-i", stream_urls[1],
                                "-c:v", "copy",
                                "-c:a", "copy",
                                "-movflags", "frag_keyframe+empty_moov+default_base_moof",
                                "-progress", "pipe:1",
                                final_out
                            ]
                        else:
                            ff_cmd += [
                                "-headers", "User-Agent: Mozilla/5.0\r\n",
                                "-i", stream_urls[0],
                                "-c", "copy",
                                "-movflags", "frag_keyframe+empty_moov+default_base_moof",
                                "-progress", "pipe:1",
                                final_out
                            ]

                        import psutil
                        ff_proc = subprocess.Popen(
                            ff_cmd,
                            stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL,
                            text=True,
                            bufsize=1,
                            creationflags=CREATION_FLAGS,
                            env=get_clean_env()
                        )
                        job["process"] = ff_proc

                        for line in iter(ff_proc.stdout.readline, ''):
                            line = line.strip()
                            if not line:
                                continue
                            if line.startswith("out_time_us="):
                                try:
                                    us = int(line.split("=")[1])
                                    sec = us / 1_000_000.0
                                    if total_dur > 0:
                                        pct = min(99.0, (sec / total_dur) * 100.0)
                                        job["percent"] = round(pct, 1)
                                except Exception:
                                    pass
                            elif line.startswith("total_size="):
                                try:
                                    sz = int(line.split("=")[1])
                                    if sz > 0:
                                        mb = sz / (1024 * 1024)
                                        job["filesize"] = f"{mb:.1f}MB"
                                except Exception:
                                    pass
                            elif line.startswith("speed="):
                                spd = line.split("=")[1].strip()
                                pct = job.get("percent", 0.0)
                                sz = job.get("filesize", "")
                                job["progress_str"] = f"{pct:.1f}% ({spd})" + (f" of {sz}" if sz else "")

                        ff_proc.stdout.close()
                        ff_rc = ff_proc.wait(timeout=600)
                        if ff_rc == 0 and os.path.exists(final_out) and os.path.getsize(final_out) > 0:
                            # 0.5-second faststart remux
                            try:
                                fs_tmp = final_out + ".fs.mp4"
                                fs_res = subprocess.run(
                                    [ffmpeg_bin, "-y", "-i", final_out, "-c", "copy", "-movflags", "+faststart", fs_tmp],
                                    capture_output=True,
                                    timeout=30,
                                    creationflags=CREATION_FLAGS,
                                    env=get_clean_env()
                                )
                                if fs_res.returncode == 0 and os.path.exists(fs_tmp) and os.path.getsize(fs_tmp) > 0:
                                    os.replace(fs_tmp, final_out)
                            except Exception:
                                pass
                            job["status"] = "done"
                            job["percent"] = 100.0
                            job["progress_str"] = "100%"
                            return
        except Exception as e:
            # Fall back to standard yt-dlp pipeline
            pass





    cmd.append(url)



    try:

        import psutil

        process = subprocess.Popen(

            cmd,

            stdout=subprocess.PIPE,

            stderr=subprocess.STDOUT,

            text=True,

            bufsize=1,

            creationflags=CREATION_FLAGS,
            env=get_clean_env()

        )

        job["process"] = process



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

                job["percent"] = pct

                job["filesize"] = size_str

                job["progress_str"] = f"{pct:.1f}% of {size_str} ({speed}, ETA {eta})"

            else:

                m_simple = simple_percent_re.search(line_str)

                if m_simple:

                    pct = float(m_simple.group(1))

                    job["percent"] = pct

                    job["progress_str"] = f"{pct:.1f}%"

                elif "[ExtractAudio]" in line_str or "[Merger]" in line_str:

                    job["percent"] = 99.0

                    job["progress_str"] = "Processing video/audio..."



        process.stdout.close()

        returncode = process.wait(timeout=300)



        if returncode != 0:

            job["status"] = "error"

            error_msg = "Download failed"

            for l in reversed(output_lines):

                if "ERROR:" in l or "Errno" in l or "Permission denied" in l or "403" in l:

                    error_msg = l.replace("ERROR:", "").strip()

                    break

            else:

                if output_lines:

                    error_msg = output_lines[-1]



            if "HTTP Error 403" in error_msg or "Access denied" in error_msg:

                if get_config().get("auto_update_engine", True):

                    threading.Thread(target=perform_engine_update, daemon=True).start()

                job["error"] = "Access denied by platform (HTTP 403). Try exporting cookies with ReClip extension, or update the engine."

            elif "Could not copy Chrome cookie database" in error_msg or "Failed to decrypt with DPAPI" in error_msg:

                job["error"] = "Chrome security prevents cookie extraction. Drop a 'cookies.txt' file in the downloads folder, or try Edge/Firefox."

            else:

                job["error"] = error_msg

            return



        # Locate downloaded output files in download directory

        try:

            all_dl_files = os.listdir(dl_dir)

        except Exception:

            all_dl_files = []



        files = [

            os.path.join(dl_dir, f) for f in all_dl_files

            if f.startswith(f"{job_id}") and not f.endswith(".part") and not f.endswith(".ytdl") and not f.endswith(".temp")

        ]



        if not files:

            # Check if any error occurred in output lines

            err_cand = [l for l in output_lines if "ERROR:" in l or "Errno" in l or "Permission denied" in l]

            job["status"] = "error"

            job["error"] = err_cand[-1] if err_cand else "Download completed but no file was found on disk"

            return



        if format_choice == "audio":

            target = [f for f in files if f.endswith(".mp3") or f.endswith(".m4a") or f.endswith(".opus")]

            chosen = target[0] if target else files[0]

        else:

            target = [f for f in files if f.endswith(".mp4") or f.endswith(".mkv") or f.endswith(".webm")]

            chosen = target[0] if target else files[0]



        for f in files:

            if f != chosen:

                try:

                    os.remove(f)

                except OSError:

                    pass



        # Extract title from the file or caller metadata

        chosen_basename = os.path.basename(chosen)

        ext = os.path.splitext(chosen)[1]



        if "___" in chosen_basename:

            extracted_title = chosen_basename.split("___", 1)[1]

            extracted_title = os.path.splitext(extracted_title)[0]

        else:

            extracted_title = os.path.splitext(chosen_basename)[0]



        def sanitize_filename(name):

            if not name:

                return ""

            clean = re.sub(r'[\\/*?:"<>|]', "", name).strip()

            clean = re.sub(r'\s+', " ", clean).strip()

            return clean[:120].strip()



        user_title = sanitize_filename(job.get("title", ""))

        video_title = user_title or sanitize_filename(extracted_title) or f"ReClip_Video_{job_id}"



        target_filename = f"{video_title}{ext}"

        target_path = os.path.join(dl_dir, target_filename)



        # Handle collisions if target file already exists in Downloads folder

        if os.path.exists(target_path) and os.path.abspath(target_path) != os.path.abspath(chosen):

            count = 1

            while os.path.exists(os.path.join(dl_dir, f"{video_title} ({count}){ext}")):

                count += 1

            target_filename = f"{video_title} ({count}){ext}"

            target_path = os.path.join(dl_dir, target_filename)



        # Rename disk file to the human-readable title!

        try:

            if os.path.abspath(chosen) != os.path.abspath(target_path):

                os.replace(chosen, target_path)

            final_file = target_path

            final_filename = target_filename

        except Exception:

            final_file = chosen

            final_filename = os.path.basename(chosen)



        actual_size = os.path.getsize(final_file)

        job["status"] = "done"

        job["percent"] = 100.0

        job["filesize"] = format_size(actual_size)

        job["progress_str"] = "Completed"

        job["file"] = final_file

        job["filename"] = final_filename

    except subprocess.TimeoutExpired:

        job["status"] = "error"

        job["error"] = "Download timed out (5 min limit)"

    except Exception as e:

        job["status"] = "error"

        job["error"] = str(e)





@app.route("/")

def index():

    return render_template("index.html")





@app.route("/api/info", methods=["POST"])

def get_info():

    data = request.json

    url = normalize_url(data.get("url", ""))

    cookies = data.get("cookies", "")

    if not url:

        return jsonify({"error": "No URL provided"}), 400



    reddit_meta = resolve_reddit_url(url)

    target_url = reddit_meta['stream_url'] if reddit_meta else url



    cmd = build_ytdlp_base_cmd(is_json=True)

    cookies_txt_path = os.path.join(get_download_dir(), "cookies.txt")

    if os.path.exists(cookies_txt_path):

        cmd += ["--cookies", cookies_txt_path]

    elif cookies:

        cmd += ["--cookies-from-browser", cookies]

    cmd.append(target_url)

    try:

        result = subprocess.run(

            cmd,

            capture_output=True,

            text=True,

            encoding="utf-8",

            errors="replace",

            timeout=25,

            creationflags=CREATION_FLAGS,
            env=get_clean_env()

        )

        if result.returncode != 0:

            raw_err = result.stderr.strip().split("\n")[-1] if result.stderr else "Failed to fetch media details"

            if "HTTP Error 403" in raw_err or "Sign in to confirm" in raw_err:

                if get_config().get("auto_update_engine", True):

                    threading.Thread(target=perform_engine_update, daemon=True).start()

                raw_err = "Access denied by platform (HTTP 403). Try exporting cookies with ReClip extension, or update the engine."

            return jsonify({"error": raw_err}), 400



        info = parse_ytdlp_json(result.stdout)

        if reddit_meta:

            if not info.get("title") or info.get("title") in ("HLSPlaylist", "DASHPlaylist"):

                info["title"] = reddit_meta.get("title") or "Reddit Video"

            if not info.get("thumbnail") and reddit_meta.get("thumbnail"):

                info["thumbnail"] = reddit_meta.get("thumbnail")

            if not info.get("uploader"):

                info["uploader"] = "Reddit"



        # Build resolution choices — keep best format per height

        best_by_height = {}

        for f in info.get("formats", []):

            height = f.get("height")

            if height and isinstance(height, int) and height >= 144:

                tbr = f.get("tbr") or 0

                if height not in best_by_height or tbr > (best_by_height[height].get("tbr") or 0):

                    best_by_height[height] = f



        best_audio_size = 0

        for f in info.get("formats", []):

            if f.get("acodec") != "none" and f.get("vcodec") == "none":

                s = f.get("filesize_approx") or f.get("filesize") or 0

                if not s:

                    abr = f.get("abr") or f.get("tbr")

                    duration = info.get("duration")

                    if abr and duration:

                        s = (abr * 1024 / 8) * duration

                if s > best_audio_size:

                    best_audio_size = s



        formats = []

        for height in sorted(best_by_height.keys(), reverse=True):

            f = best_by_height[height]

            v_size = f.get("filesize_approx") or f.get("filesize") or 0

            if not v_size:

                vbr = f.get("vbr") or f.get("tbr")

                duration = info.get("duration")

                if vbr and duration:

                    v_size = (vbr * 1024 / 8) * duration

            

            total_size = v_size + best_audio_size if (v_size and best_audio_size) else 0

            filesize_str = format_size(total_size) if total_size else ""

            formats.append({

                "id": str(height),

                "label": f"{height}p",

                "height": height,

                "filesize": filesize_str

            })



        if not formats and info.get("height"):

            h = info.get("height")

            formats.append({"id": str(h), "label": f"{h}p", "height": h})



        if not formats:

            formats = [

                {"id": "1080", "label": "1080p", "height": 1080},

                {"id": "720", "label": "720p", "height": 720},

                {"id": "480", "label": "480p", "height": 480},

                {"id": "360", "label": "360p", "height": 360},

            ]



        approx_size = info.get("filesize_approx") or info.get("filesize")

        # Extract available multi-audio dub tracks

        audio_languages = []

        seen_langs = set()

        LANG_NAMES = {

            "ml": "Malayalam (മലയാളം)",

            "hi": "Hindi (हिन्दी)",

            "ta": "Tamil (தமிழ்)",

            "te": "Telugu (తెలుగు)",

            "en": "English (Original)",

            "en-us": "English (US)",

            "en-gb": "English (UK)",

            "es": "Spanish (Español)",

            "fr": "French (Français)",

            "de": "German (Deutsch)",

            "ja": "Japanese (日本語)",

            "ko": "Korean (한국어)",

            "pt": "Portuguese (Português)",

            "ru": "Russian (Русский)",

            "ar": "Arabic (العربية)",

            "id": "Indonesian (Bahasa)",

            "vi": "Vietnamese (Tiếng Việt)",

            "th": "Thai (ไทย)",

            "tr": "Turkish (Türkçe)",

            "it": "Italian (Italiano)",

            "pl": "Polish (Polski)",

            "bn": "Bangla (বাংলা)",

            "mr": "Marathi (मराठी)",

            "pa": "Punjabi (ਪੰਜਾਬੀ)",

        }



        for f in info.get("formats", []):

            if f.get("vcodec") in ("none", None) and f.get("language"):

                raw_lang = (f.get("language") or "").lower().strip()

                base_lang = raw_lang.split("-")[0]

                if base_lang and base_lang not in seen_langs:

                    seen_langs.add(base_lang)

                    name = LANG_NAMES.get(raw_lang) or LANG_NAMES.get(base_lang) or raw_lang.upper()

                    audio_languages.append({

                        "code": base_lang,

                        "lang_id": raw_lang,

                        "name": name,

                    })



        return jsonify({

            "url": target_url,

            "title": info.get("title", ""),

            "thumbnail": info.get("thumbnail", ""),

            "duration": info.get("duration"),

            "uploader": info.get("uploader", ""),

            "filesize": filesize_str,

            "audio_filesize": format_size(best_audio_size) if best_audio_size else "",

            "formats": formats,

            "audio_languages": audio_languages,

        })

    except subprocess.TimeoutExpired:

        return jsonify({"error": "Timed out fetching video info"}), 400

    except Exception as e:

        return jsonify({"error": str(e)}), 400





@app.route("/api/playlist", methods=["POST"])

def get_playlist_info():

    data = request.json

    url = data.get("url", "").strip()

    if not url:

        return jsonify({"error": "No URL provided"}), 400



    cmd = [*build_ytdlp_base_cmd(is_json=True), "--flat-playlist", url]

    try:

        result = subprocess.run(

            cmd,

            capture_output=True,

            text=True,

            timeout=60,

            creationflags=CREATION_FLAGS,
            env=get_clean_env()

        )

        if result.returncode != 0:

            raw_err = result.stderr.strip().split("\n")[-1] if result.stderr else "Failed to fetch media details"

            if "HTTP Error 403" in raw_err or "Sign in to confirm" in raw_err:

                if get_config().get("auto_update_engine", True):

                    threading.Thread(target=perform_engine_update, daemon=True).start()

                raw_err = "Access denied by platform (HTTP 403). Try exporting cookies with ReClip extension, or update the engine."

            return jsonify({"error": raw_err}), 400



        info = json.loads(result.stdout)

        entries = info.get("entries", [])

        urls = [entry.get("url") for entry in entries if entry.get("url")]

        return jsonify({"urls": urls})

    except subprocess.TimeoutExpired:

        return jsonify({"error": "Timed out fetching playlist info"}), 400

    except Exception as e:

        return jsonify({"error": str(e)}), 400





@app.route("/api/download", methods=["POST", "OPTIONS"])

def start_download():

    if request.method == "OPTIONS":

        return "", 200

    data = request.json or {}

    url = data.get("url", "").strip()

    format_choice = data.get("format", "video")

    format_id = data.get("format_id")

    title = data.get("title", "")

    cookies = data.get("cookies", "")

    audio_lang = data.get("audio_lang", "").strip().lower()



    if not url:

        return jsonify({"error": "No URL provided"}), 400



    job_id = uuid.uuid4().hex[:10]

    jobs[job_id] = {

        "status": "downloading",

        "url": url,

        "title": title,

        "percent": 0.0,

        "progress_str": "Downloading...",

        "filesize": "",

        "audio_lang": audio_lang

    }



    thread = threading.Thread(target=run_download, args=(job_id, url, format_choice, format_id, cookies, audio_lang))

    thread.daemon = True

    thread.start()



    return jsonify({"job_id": job_id})





@app.route("/api/status/<job_id>")

def status(job_id):

    job = jobs.get(job_id)

    if not job:

        return jsonify({"error": "Not found"}), 404

    return jsonify({

        "status": job["status"],

        "percent": job["percent"],

        "progress_str": job["progress_str"],

        "filesize": job.get("filesize", ""),

        "error": job.get("error", ""),

        "file": job.get("file", ""),

        "filename": job.get("filename", "")

    })





@app.route("/api/pause/<job_id>", methods=["POST"])

def pause_job(job_id):

    import psutil

    job = jobs.get(job_id)

    if not job:

        return jsonify({"error": "Not found"}), 404

    if job.get("status") != "downloading":

        return jsonify({"error": "Can only pause downloading jobs"}), 400

    

    process = job.get("process")

    if process and process.poll() is None:

        try:

            parent = psutil.Process(process.pid)

            for child in parent.children(recursive=True):

                child.suspend()

            parent.suspend()

            job["status"] = "paused"

            return jsonify({"status": "paused"}), 200

        except psutil.NoSuchProcess:

            return jsonify({"error": "Process no longer exists"}), 400

        except Exception as e:

            return jsonify({"error": str(e)}), 500

    return jsonify({"error": "Process not found or already finished"}), 400





@app.route("/api/resume/<job_id>", methods=["POST"])

def resume_job(job_id):

    import psutil

    job = jobs.get(job_id)

    if not job:

        return jsonify({"error": "Not found"}), 404

    if job.get("status") != "paused":

        return jsonify({"error": "Can only resume paused jobs"}), 400

    

    process = job.get("process")

    if process and process.poll() is None:

        try:

            parent = psutil.Process(process.pid)

            for child in parent.children(recursive=True):

                child.resume()

            parent.resume()

            job["status"] = "downloading"

            return jsonify({"status": "downloading"}), 200

        except psutil.NoSuchProcess:

            return jsonify({"error": "Process no longer exists"}), 400

        except Exception as e:

            return jsonify({"error": str(e)}), 500

    return jsonify({"error": "Process not found or already finished"}), 400





@app.route("/api/file/<job_id>")

def download_file(job_id):

    job = jobs.get(job_id)

    if not job or job["status"] != "done":

        return jsonify({"error": "File not ready"}), 404

    return send_file(job["file"], as_attachment=True, download_name=job["filename"])





@app.route("/api/open-downloads", methods=["GET", "POST", "OPTIONS"])

def open_downloads():

    if request.method == "OPTIONS":

        return "", 200

    try:

        dl_dir = os.path.abspath(get_download_dir())

        os.makedirs(dl_dir, exist_ok=True)

        if os.name == "nt":

            try:

                os.startfile(dl_dir)

            except Exception:

                subprocess.Popen(["explorer", dl_dir], creationflags=CREATION_FLAGS)

        elif sys.platform == "darwin":

            subprocess.run(["open", dl_dir], creationflags=CREATION_FLAGS)

        else:

            subprocess.run(["xdg-open", dl_dir], creationflags=CREATION_FLAGS)

        return jsonify({"success": True})

    except Exception as e:

        return jsonify({"error": str(e)}), 500







@app.route("/api/streamable_download/toggle", methods=["POST"])
def toggle_streamable_download():
    data = request.json or {}
    val = data.get("enabled")
    config = get_config()
    if val is None:
        config["streamable_download"] = not config.get("streamable_download", True)
    else:
        config["streamable_download"] = bool(val)
    save_config(config)
    return jsonify({"streamable_download": config["streamable_download"]})


@app.route("/api/play/<job_id>", methods=["POST"])
def play_job(job_id):
    job = jobs.get(job_id)
    dl_dir = get_download_dir()
    target_file = None
    has_part_file = False

    if job and job.get("file") and os.path.exists(job["file"]):
        target_file = job["file"]
    else:
        try:
            candidates = [
                os.path.join(dl_dir, f) for f in os.listdir(dl_dir)
                if f.startswith(f"{job_id}")
            ]
            non_part = [
                c for c in candidates 
                if not any(c.lower().endswith(ext) for ext in [".part", ".ytdl", ".temp", ".fs.mp4"])
            ]
            if non_part:
                target_file = non_part[0]
            elif any(c.lower().endswith((".part", ".ytdl", ".temp")) for c in candidates):
                has_part_file = True
        except Exception:
            pass

    if not target_file or not os.path.exists(target_file):
        if has_part_file:
            return jsonify({
                "error": "This download was started in standard mode as an incomplete (.part) file. Standard temporary files cannot be played while downloading. Enable 'Play While Downloading' before starting the download to stream."
            }), 400
        return jsonify({"error": "Media stream is initializing. Please wait a few seconds and try again."}), 404

    # Check minimum file size to prevent player decode failure on empty or 0-byte headers
    try:
        sz = os.path.getsize(target_file)
        if sz < 150 * 1024:
            return jsonify({
                "error": "Buffering initial media stream. Please wait 3 seconds and click Play again."
            }), 400
    except Exception:
        pass

    try:
        if os.name == "nt":
            os.startfile(target_file)
        elif sys.platform == "darwin":
            subprocess.Popen(["open", target_file], env=get_clean_env())
        else:
            subprocess.Popen(["xdg-open", target_file], env=get_clean_env())
        return jsonify({"success": True, "file": target_file})
    except Exception as e:
        return jsonify({"error": f"Failed to launch media player: {str(e)}"}), 500

@app.route("/api/engine/status", methods=["GET"])

def engine_status():

    config = get_config()

    return jsonify({

        "version": get_ytdlp_version(),

        "auto_update": config.get("auto_update_engine", True),
        "streamable_download": config.get("streamable_download", True),

        "is_updating": is_engine_updating,

        "ytdlp_path": get_ytdlp_bin(),

        "node_available": bool(get_node_bin()),

        "ffmpeg_available": bool(get_ffmpeg_dir())

    })



@app.route("/api/engine/update", methods=["POST"])

def engine_update():

    res = perform_engine_update()

    return jsonify(res)



@app.route("/api/engine/toggle_auto", methods=["POST"])

def engine_toggle_auto():

    data = request.json or {}

    val = data.get("enabled")

    config = get_config()

    if val is None:

        config["auto_update_engine"] = not config.get("auto_update_engine", True)

    else:

        config["auto_update_engine"] = bool(val)

    save_config(config)

    return jsonify({"auto_update": config["auto_update_engine"]})



# Start background auto-updater thread on app launch

threading.Thread(target=background_auto_check, daemon=True).start()



if __name__ == "__main__":

    port = int(os.environ.get("PORT", 8899))

    host = os.environ.get("HOST", "127.0.0.1")

    app.run(host=host, port=port)

