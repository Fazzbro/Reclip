$WshShell = New-Object -ComObject WScript.Shell
$DesktopPath = [System.Environment]::GetFolderPath([System.Environment+SpecialFolder]::Desktop)
$TargetDir = $PSScriptRoot

# Check if PyInstaller build executable exists, fallback to VBS script launcher
$ExePath = Join-Path $TargetDir "dist\ReClip\ReClip.exe"
if (Test-Path $ExePath) {
    $TargetPath = $ExePath
    $Arguments = ""
} else {
    $TargetPath = "wscript.exe"
    $VbsPath = Join-Path $TargetDir "ReClip.vbs"
    $Arguments = "`"$VbsPath`""
}

$ShortcutPath = Join-Path $DesktopPath "ReClip Media Downloader.lnk"
$Shortcut = $WshShell.CreateShortcut($ShortcutPath)
$Shortcut.TargetPath = $TargetPath
$Shortcut.Arguments = $Arguments
$Shortcut.WorkingDirectory = $TargetDir
$Shortcut.Description = "ReClip - Native Windows Media Downloader"
$Shortcut.Save()

Write-Host "Created Desktop Shortcut: $ShortcutPath"
