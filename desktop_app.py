import os
import sys
import socket
import threading
import time
import webview
from app import app, get_download_dir, set_download_dir

class Api:
    def get_current_dir(self):
        return get_download_dir()

    def choose_folder(self):
        try:
            window = webview.windows[0]
            result = window.create_file_dialog(webview.FOLDER_DIALOG, directory=get_download_dir())
            if result and len(result) > 0:
                set_download_dir(result[0])
                return result[0]
            return None
        except Exception as e:
            print("Dialog error:", e)
            return None

def find_free_port():
    """Find a free port on localhost."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(('127.0.0.1', 0))
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        return s.getsockname()[1]

def run_flask(port):
    """Run Flask server in thread."""
    # Suppress verbose Flask logging in production GUI mode
    import logging
    log = logging.getLogger('werkzeug')
    log.setLevel(logging.ERROR)
    app.run(host='127.0.0.1', port=port, debug=False, use_reloader=False)

def main():
    port = find_free_port()
    server_thread = threading.Thread(target=run_flask, args=(port,), daemon=True)
    server_thread.start()

    # Wait briefly for server to bind
    time.sleep(0.5)

    icon_path = os.path.join(os.path.dirname(__file__), "static", "logo.png")
    
    api = Api()
    # Create pywebview window
    window = webview.create_window(
        title="ReClip — Media Downloader",
        url=f"http://127.0.0.1:{port}",
        width=820,
        height=860,
        min_size=(640, 720),
        resizable=True,
        text_select=True,
        js_api=api
    )

    # Start native WebView window engine
    webview.start(private_mode=False)

if __name__ == "__main__":
    main()
