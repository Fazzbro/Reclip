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

def get_download_dir():
    config_path = get_config_path()
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                config = json.load(f)
                d = config.get("download_dir")
                if d and os.path.exists(d):
                    return os.path.abspath(d)
        except:
            pass
    
    return os.path.abspath(get_default_download_dir())

def set_download_dir(path):
    config_path = get_config_path()
    config = {}
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                config = json.load(f)
        except:
            pass
    config["download_dir"] = os.path.abspath(path)
    try:
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(config, f)
    except:
        pass


if getattr(sys, 'frozen', False):
    base_path = sys._MEIPASS
else:
    base_path = os.path.dirname(os.path.abspath(__file__))

YTDLP_BIN = os.path.join(base_path, "bin", "yt-dlp.exe") if os.name == "nt" else "yt-dlp"
if not os.path.exists(YTDLP_BIN):
    alt_path = os.path.join(os.path.dirname(sys.executable), "_internal", "bin", "yt-dlp.exe")
    if os.path.exists(alt_path):
        YTDLP_BIN = alt_path
    else:
        YTDLP_BIN = "yt-dlp"

FFMPEG_DIR = os.path.join(base_path, "bin")
if not os.path.exists(os.path.join(FFMPEG_DIR, "ffmpeg.exe")):
    alt_ffmpeg = os.path.join(os.path.dirname(sys.executable), "_internal", "bin")
    if os.path.exists(os.path.join(alt_ffmpeg, "ffmpeg.exe")):
        FFMPEG_DIR = alt_ffmpeg
    else:
        FFMPEG_DIR = ""

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
    """Parse yt-dlp JSON output.

    With ``-j`` yt-dlp prints one JSON object per line. Some extractors
    emit multiple videos even with ``--no-playlist``, so stdout contains
    several objects and a plain ``json.loads`` raises "Extra data".
    Return the first valid object.
    """
    for line in stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        return json.loads(line)
    raise ValueError("yt-dlp returned no data")

NODE_BIN = os.path.join(base_path, "bin", "node.exe") if os.name == "nt" else "node"
if not os.path.exists(NODE_BIN):
    NODE_BIN = "node"

# DEBUG: Log the resolved Node path
with open(os.path.join(get_download_dir(), "reclip_debug.log"), "a") as f:
    f.write(f"Resolved NODE_BIN: {NODE_BIN} (exists: {os.path.exists(NODE_BIN)})\n")

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
    return url


def run_download(job_id, url, format_choice, format_id, cookies_browser="", audio_lang=""):
    job = jobs[job_id]
    job["percent"] = 0.0
    job["progress_str"] = "Downloading..."
    job["filesize"] = ""
    url = normalize_url(url)
    dl_dir = get_download_dir()
    os.makedirs(dl_dir, exist_ok=True)
    out_template = os.path.join(dl_dir, f"{job_id}___%(title).140B.%(ext)s")

    cmd = [
        YTDLP_BIN,
        "-4",
        "--newline",
        "--no-playlist",
        "--geo-bypass",
        "--no-check-certificates",
        "--socket-timeout", "15",
        "--js-runtimes", f"node:{NODE_BIN}",
        "--add-header", f"X-Forwarded-For: {get_random_ip()}",
        "-o", out_template
    ]

    if FFMPEG_DIR and os.path.exists(FFMPEG_DIR):
        cmd += ["--ffmpeg-location", FFMPEG_DIR]

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
            cmd += ["-f", f"bestaudio[language^={audio_lang}]/bestaudio", "-x", "--audio-format", "mp3"]
        elif format_id and str(format_id).isdigit():
            h = int(format_id)
            cmd += ["-f", f"bestvideo[height<={h}]+bestaudio[language^={audio_lang}]/bestvideo[height<={h}]+bestaudio/best[height<={h}]/best", "--merge-output-format", "mp4"]
        else:
            cmd += ["-f", f"bestvideo[height<=1080]+bestaudio[language^={audio_lang}]/bestvideo[height<=1080]+bestaudio/best[height<=1080]/best", "--merge-output-format", "mp4"]
    else:
        if format_choice == "audio":
            cmd += ["-x", "--audio-format", "mp3"]
        elif format_id:
            if str(format_id).isdigit():
                h = int(format_id)
                cmd += ["-f", f"bestvideo[height<={h}]+bestaudio/best[height<={h}]/best", "--merge-output-format", "mp4"]
            else:
                cmd += ["-f", f"{format_id}+bestaudio/best", "--merge-output-format", "mp4"]
        else:
            cmd += ["-f", "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best", "--merge-output-format", "mp4"]

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
            # Find the most descriptive error message
            error_msg = "Download failed"
            for l in reversed(output_lines):
                if "ERROR:" in l or "Errno" in l or "Permission denied" in l:
                    error_msg = l.replace("ERROR:", "").strip()
                    break
            else:
                if output_lines:
                    error_msg = output_lines[-1]

            if "Could not copy Chrome cookie database" in error_msg or "Failed to decrypt with DPAPI" in error_msg:
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

    cmd = [
        YTDLP_BIN,
        "-4",
        "--no-playlist",
        "--geo-bypass",
        "--no-check-certificates",
        "--socket-timeout", "15",
        "--js-runtimes", f"node:{NODE_BIN}",
        "--add-header", f"X-Forwarded-For: {get_random_ip()}",
        "-j"
    ]
    cookies_txt_path = os.path.join(get_download_dir(), "cookies.txt")
    if os.path.exists(cookies_txt_path):
        cmd += ["--cookies", cookies_txt_path]
    elif cookies:
        cmd += ["--cookies-from-browser", cookies]
    cmd.append(url)
    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=25,
            creationflags=CREATION_FLAGS,
        )
        if result.returncode != 0:
            return jsonify({"error": result.stderr.strip().split("\n")[-1]}), 400

        info = parse_ytdlp_json(result.stdout)

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
        filesize_str = format_size(approx_size) if approx_size else ""
        
        if formats and not formats[0]["filesize"] and approx_size:
            formats[0]["filesize"] = filesize_str

        return jsonify({
            "title": info.get("title", ""),
            "thumbnail": info.get("thumbnail", ""),
            "duration": info.get("duration"),
            "uploader": info.get("uploader", ""),
            "filesize": filesize_str,
            "audio_filesize": format_size(best_audio_size) if best_audio_size else "",
            "formats": formats,
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

    cmd = ["yt-dlp", "--flat-playlist", "-J", url]
    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=60,
            creationflags=CREATION_FLAGS,
        )
        if result.returncode != 0:
            return jsonify({"error": result.stderr.strip().split("\n")[-1]}), 400

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


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 8899))
    host = os.environ.get("HOST", "127.0.0.1")
    app.run(host=host, port=port)
