# Changelog

All notable changes to this project are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Skills (record and replay): save a completed run as a skill from the run details menu. The same task then replays the recorded steps without model calls and ends with one model call that checks the screen and answers; if a recorded target is missing, the model takes over from there. Skills are listed in Settings (rename, delete, turn off) and shown first among the home suggestions.
- GitHub Actions: build on every push and pull request; build, sign and publish the APK when a `v*` tag is pushed.
- Issue and pull request templates, contributing guide and this changelog.

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

[Unreleased]: https://github.com/tianxia--/artemis-ondevice/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/tianxia--/artemis-ondevice/releases/tag/v0.1.0
