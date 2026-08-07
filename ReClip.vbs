Set WshShell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

' Get current folder path
scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)

' Run desktop_app.py silently (0 = hidden window)
WshShell.Run "python """ & scriptDir & "\desktop_app.py""", 0, False
