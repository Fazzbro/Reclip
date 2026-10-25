"""
Unit tests and validation for ReClip MCP Server
"""

import os
import sys
import unittest
import mcp_server

class TestReClipMCPServer(unittest.TestCase):
    def test_tools_registered(self):
        tools = mcp_server.mcp._tool_manager.list_tools()
        tool_names = [t.name for t in tools]
        expected = [
            "download_media",
            "get_media_info",
            "get_download_status",
            "list_downloads",
            "get_config",
            "set_reclip_download_dir",
            "open_downloads_folder",
            "list_supported_sites",
        ]
        for exp in expected:
            self.assertIn(exp, tool_names, f"Expected tool {exp} to be registered")

    def test_get_config(self):
        cfg = mcp_server.get_config()
        self.assertIn("download_directory", cfg)
        self.assertIn("ytdlp_version", cfg)
        self.assertIn("ffmpeg_version", cfg)
        self.assertTrue(os.path.exists(cfg["download_directory"]))

    def test_list_downloads(self):
        dl = mcp_server.list_downloads(limit=5)
        self.assertIsInstance(dl, list)
        if dl:
            item = dl[0]
            self.assertIn("filename", item)
            self.assertIn("file_path", item)
            self.assertIn("size", item)

    def test_list_supported_sites(self):
        res = mcp_server.list_supported_sites("youtube")
        self.assertIn("matches", res)
        self.assertTrue(any("youtube" in m.lower() for m in res["matches"]))

    def test_normalize_url(self):
        self.assertEqual(
            mcp_server.normalize_url("https://youtu.be/dQw4w9WgXcQ"),
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        )
        self.assertEqual(
            mcp_server.normalize_url("https://www.youtube.com/shorts/dQw4w9WgXcQ"),
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        )

    def test_sanitize_filename(self):
        bad = 'Test /:*?"<>| Video Title'
        sanitized = mcp_server.sanitize_filename(bad)
        for char in '/:*?"<>|':
            self.assertNotIn(char, sanitized)

if __name__ == "__main__":
    unittest.main()
