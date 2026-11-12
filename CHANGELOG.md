# Changelog

All notable changes to this project are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Model usage and limits (Settings > Model usage and limits): every model call (tasks, reviews, voice correction, connection tests) is counted per day, model and purpose, with an estimate when the API returns no token counts. Optional prices per million tokens in the model service editor give an estimated cost, also in run details. A per-task token limit (default 300k) stops a run, and a daily limit (default 2M) refuses new runs; reviews stop at 80% of the daily limit. Limits are checked before each call.
- Confirmation of sensitive actions (Settings > Run, on by default): before a tap that sends, calls, pays, buys, deletes, posts, invites or changes a setting, a card at the bottom of the screen asks Allow / Allow for this task / Don't allow; no answer in 3 minutes means not done. The model flags such taps with `commits` and a `summary`, and the button text is checked as a backstop. Declined actions are recorded and never retried; skill replays are checked too.
- `ask_user`: when the agent cannot tell what the user wants (two contacts with the same name, an open choice), it asks with 2-4 options and continues with the answer.
- Experience: after each completed or failed run, one extra model call reviews where steps were wasted and records lessons in three layers (principles for any task, patterns for kinds of screens, facts about one app). A lesson is used in later prompts once two runs drew it or confirmed it, or the user approved it; lessons whose runs keep failing drop back to candidates. Settings > Experience lists them (approve, turn off, delete) and can turn the review off. Lessons stay on the phone and leave out personal data. Each run records what its review did.
- Installed apps named in the task are listed for the model, which opens them with `open_app` instead of looking for icons on the home screen.
- Notes from earlier runs: a task that was run before starts with that run's facts and dead ends, so a second run skips the first one's detours (on a Huawei phone, the same two-part task went from 19 steps / 118 s to 6 steps / 32 s).
- Each turn reports which writing system dominates the screen text and the phone's system language; searches and label lookups use the screen's language first, then the system language, then the task's own language.
- A part that involves another person (a call, an invite, a message) counts as done once performed, even if they reject or do not answer; it is noted and never retried.
- Task memory: the model keeps a running record of completed parts, facts found and dead ends with a new `update_memory` tool. It is shown at the top of every turn for the whole run, so progress is no longer lost once older steps leave the 6-step history, and finishing no longer needs earlier parts to be visible again.

### Changed
- The step limit per task now defaults to 50 instead of none (still adjustable in Settings > Run; 0 = no limit).
- Every index-targeted action re-reads the screen and finds its element again by label, so a list that moved while the model was thinking no longer gets tapped in the wrong place; an action whose target is gone is skipped.
- Within one turn, the rest of the actions stop at the first failed or skipped action, and x,y actions after a tap, swipe, back, home or open_app are skipped (their point was chosen on the old screen).
- After a failed or skipped action the next turn is told to re-orient against the task memory instead of redoing finished parts, and the failed step's reasoning is marked as unreliable in the history.
- Anthropic requests use temperature 0.2, like OpenAI-compatible ones.
- `open_app` launches again when the first launch left the home screen in front (seen on Huawei, which drops the first launch from the background), and says so when a dialog keeps the app behind instead of reporting it ready.
- Model requests retry three times (after 2, 5 and 10 s) instead of once, and each attempt times out after 60 s instead of 90 s: a dropped connection no longer ends a run that was going well.

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
