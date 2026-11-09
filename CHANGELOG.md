# Changelog

All notable changes to this project are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.0] - 2026-11-09

Skills (record and replay), an MCP server for desktop AI tools, and CI.

### Added
- MCP server (Settings > MCP server, off by default): AI tools on the computer, such as Claude Code or Cursor, can run tasks on the phone (`run_task`, `get_run`, `stop_run`, ...) or observe and operate it directly (`observe`, `tap`, `type_text`, ...). Streamable HTTP on port 8765, localhost only unless LAN access is turned on, bearer token required. See docs/mcp.md.
- Skills (record and replay): save a completed run as a skill from the run details menu. The same task then replays the recorded steps without model calls and ends with one model call that checks the screen and answers; if a recorded target is missing, the model takes over from there. Skills are listed in Settings (rename, delete, turn off) and shown first among the home suggestions.
- GitHub Actions: build on every push and pull request; build, sign and publish the APK when a `v*` tag is pushed (skipped when the signing secrets are not set or the release already exists).
- Issue and pull request templates, contributing guide and this changelog.

### Changed
- The phone actions moved from `FlashLoop` into `Actions`, shared by the model loop, skill replay and the MCP server.

### Fixed
- A run could wait forever when it started while another app was already open behind ours: the screen hold now also ends when our last screen pauses.
- Model timing metrics ignore replayed steps.

## [0.1.0] - 2026-11-09

First public release.

### Added
- Phone agent that runs on the phone itself through an accessibility service: screenshot + indexed UI element list, one model call with tools, then `tap` / `long_press` / `type_text` / `swipe` / `back` / `home` / `open_app` / `wait` / `finish`.
- Model services: built-in presets or a custom OpenAI-compatible / Anthropic endpoint, model list fetch and connection test.
- Floating bubble with live progress, run history with per-step screenshots, run details with timing and token metrics.
- Chinese / English UI, switchable in Settings > Language.
- Optional offline voice wake-up (Mandarin only).

### Security
- API keys encrypted with an Android Keystore AES-GCM key.
- Base URL masked on screen by default and removed from error messages.
- WeChat, QQ and Alipay are never operated.

[Unreleased]: https://github.com/tianxia--/artemis-ondevice/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/tianxia--/artemis-ondevice/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/tianxia--/artemis-ondevice/releases/tag/v0.1.0
