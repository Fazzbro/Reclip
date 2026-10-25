@echo off
setlocal
cd /d "%~dp0"
echo Starting ReClip MCP Server for Windows...
python mcp_server.py
pause
