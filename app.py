import os
import re
import uuid
import glob
import json
import subprocess
import threading
from flask import Flask, request, jsonify, send_file, render_template

app = Flask(__name__)
DOWNLOAD_DIR = os.path.join(os.path.dirname(__file__), "downloads")
os.makedirs(DOWNLOAD_DIR, exist_ok=True)

import sys

if getattr(sys, 'frozen', False):
    base_path = sys._MEIPASS
else:
    base_path = os.path.dirname(os.path.abspath(__file__))

YTDLP_BIN = os.path.join(base_path, "bin", "yt-dlp.exe") if os.name == "nt" else "yt-dlp"
if not os.path.exists(YTDLP_BIN):
    # Fallback for debugging, try finding it relative to the executable
    alt_path = os.path.join(os.path.dirname(sys.executable), "_internal", "bin", "yt-dlp.exe")
    if os.path.exists(alt_path):
        YTDLP_BIN = alt_path
    else:
        YTDLP_BIN = "yt-dlp"
        
# DEBUG: Log the resolved path so we know exactly what is executing
with open(os.path.join(DOWNLOAD_DIR, "reclip_debug.log"), "a") as f:
    f.write(f"Resolved YTDLP_BIN: {YTDLP_BIN} (exists: {os.path.exists(YTDLP_BIN)})\n")

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
with open(os.path.join(DOWNLOAD_DIR, "reclip_debug.log"), "a") as f:
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


def run_download(job_id, url, format_choice, format_id, cookies_browser=""):
    job = jobs[job_id]
    job["percent"] = 0.0
    job["progress_str"] = "Downloading..."
    job["filesize"] = ""
    url = normalize_url(url)
    out_template = os.path.join(DOWNLOAD_DIR, f"{job_id}.%(ext)s")

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

    cookies_txt_path = os.path.join(DOWNLOAD_DIR, "cookies.txt")
    if os.path.exists(cookies_txt_path):
        cmd += ["--cookies", cookies_txt_path]
    elif cookies_browser:
        cmd += ["--cookies-from-browser", cookies_browser]

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
            error_msg = output_lines[-1] if output_lines else "Download failed"
            if "Could not copy Chrome cookie database" in error_msg or "Failed to decrypt with DPAPI" in error_msg:
                job["error"] = "Chrome security prevents cookie extraction. Drop a 'cookies.txt' file in the downloads folder, or try Edge/Firefox."
            else:
                job["error"] = error_msg
            return

        files = glob.glob(os.path.join(DOWNLOAD_DIR, f"{job_id}.*"))
        if not files:
            job["status"] = "error"
            job["error"] = "Download completed but no file was found"
            return

        if format_choice == "audio":
            target = [f for f in files if f.endswith(".mp3")]
            chosen = target[0] if target else files[0]
        else:
            target = [f for f in files if f.endswith(".mp4")]
            chosen = target[0] if target else files[0]

        for f in files:
            if f != chosen:
                try:
                    os.remove(f)
                except OSError:
                    pass

        actual_size = os.path.getsize(chosen)
        job["status"] = "done"
        job["percent"] = 100.0
        job["filesize"] = format_size(actual_size)
        job["progress_str"] = "Completed"
        job["file"] = chosen
        ext = os.path.splitext(chosen)[1]
        title = job.get("title", "").strip()
        # Sanitize title for filename
        if title:
            safe_title = "".join(c for c in title if c not in r'\/:*?"<>|').strip()[:100].strip()
            job["filename"] = f"{safe_title}{ext}" if safe_title else os.path.basename(chosen)
        else:
            job["filename"] = os.path.basename(chosen)
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
    cookies_txt_path = os.path.join(DOWNLOAD_DIR, "cookies.txt")
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
                if s > best_audio_size:
                    best_audio_size = s

        formats = []
        for height in sorted(best_by_height.keys(), reverse=True):
            f = best_by_height[height]
            v_size = f.get("filesize_approx") or f.get("filesize") or 0
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


@app.route("/api/download", methods=["POST"])
def start_download():
    data = request.json
    url = data.get("url", "").strip()
    format_choice = data.get("format", "video")
    format_id = data.get("format_id")
    title = data.get("title", "")
    cookies = data.get("cookies", "")

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
    }

    thread = threading.Thread(target=run_download, args=(job_id, url, format_choice, format_id, cookies))
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


@app.route("/api/open-downloads", methods=["POST"])
def open_downloads():
    try:
        import sys
        if os.name == "nt":
            os.startfile(DOWNLOAD_DIR)
        elif sys.platform == "darwin":
            subprocess.run(["open", DOWNLOAD_DIR], creationflags=CREATION_FLAGS)
        else:
            subprocess.run(["xdg-open", DOWNLOAD_DIR], creationflags=CREATION_FLAGS)
        return jsonify({"success": True})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 8899))
    host = os.environ.get("HOST", "127.0.0.1")
    app.run(host=host, port=port)
