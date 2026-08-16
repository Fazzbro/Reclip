# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-08-16

### Added
- **Chrome Extension Integration**: Built a fully functional Chrome extension (`chrome_downloader/`) that injects download buttons directly onto YouTube and X (Twitter) thumbnails and articles.
- **CORS Support**: Implemented CORS headers in the backend to allow cross-origin requests from the browser extension.

### Changed
- **Port Stabilization**: Hardcoded the local backend API server to run reliably on port `8899` instead of picking a random free port, making it reachable by the Chrome extension.
- **Deployment Process**: Bundled the latest `yt-dlp` executable during the build process to ensure maximum compatibility.

### Fixed
- Fixed an issue where the extension download buttons were rendering improperly (`??`) due to emoji encoding, and resolved a CSS collision issue by moving the button to the top-left of the thumbnails.
