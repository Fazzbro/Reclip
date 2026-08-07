import os
import sys
import subprocess
import shutil

def build_app():
    print("Building ReClip Windows Desktop App with PyInstaller...")
    base_dir = os.path.dirname(os.path.abspath(__file__))
    
    # Path separators for Windows PyInstaller --add-data (source;destination)
    data_sep = ";" if os.name == "nt" else ":"

    icon_ico = os.path.join(base_dir, "app_icon.ico")
    cmd = [
        "pyinstaller",
        "-y",
        "--name=ReClip",
        "--noconsole",
        "--onedir",
        "--clean",
        f"--icon={icon_ico}",
        f"--add-data={os.path.join(base_dir, 'templates')}{data_sep}templates",
        f"--add-data={os.path.join(base_dir, 'static')}{data_sep}static",
        f"--add-data={os.path.join(base_dir, 'bin')}{data_sep}bin",
        os.path.join(base_dir, "desktop_app.py")
    ]



    print("Running PyInstaller command:")
    print(" ".join(cmd))

    result = subprocess.run(cmd, cwd=base_dir)
    if result.returncode == 0:
        dist_exe = os.path.join(base_dir, "dist", "ReClip", "ReClip.exe")
        print("\n" + "="*50)
        print("BUILD SUCCESSFUL!")
        print(f"Windows Executable created at:\n{dist_exe}")
        print("="*50 + "\n")
    else:
        print("\nBUILD FAILED! Check output above.")

if __name__ == "__main__":
    build_app()
