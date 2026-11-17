# Changelog

All notable changes to this project are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Voice calls with a realtime voice model (Qwen-Audio realtime protocol): pick one as the voice call model in Settings and the wake word starts a call. What you say is recognized on the phone and sent as text; the reply streams back as speech. The model hands phone work to the agent, reads out confirmations and questions (your spoken answer is matched by the app, never decided by the model) and tells you the result. An on-screen panel shows a voice-reactive orb, live captions and a hang-up button.
- During a call: several things in one sentence run as one task in order; adding to a task right after asking merges it, a correction shortly after it starts restarts it, and later requests queue. "停下" stops the task and "结束对话" hangs up, handled on the phone without waiting for the model.
- Answer first, then use the phone: the agent and voice calls see the phone's state (time, battery, network, Wi-Fi/Bluetooth/airplane, ringer and do not disturb, volume, brightness, screen timeout, storage, device), so "几点了" or "还有多少电" is answered at once without opening an app. Apps are used when the task needs one or names one ("打开时钟看看几点").
- Voice calls can optionally search the web for live information such as weather (Settings, off by default: with it on, the service sometimes took 17-30 s to answer).
- Voice calls go to a connected Bluetooth hands-free (car) or headset, then a wired or USB headset, before the phone's loudspeaker; during a task the call shrinks to a small pill that moves with your voice and stays out of the agent's screenshots.
- Custom wake word (Settings > Run): 3 to 6 Chinese characters, converted with a bundled hanzi-to-pinyin table (`scripts/make-pinyin-table.py`, from pypinyin).
- Voiceprint (Settings > Run): read three sentences, and voice calls ignore other people talking nearby. Stored only on the phone (3D-Speaker CAM++ model, fetched by `scripts/fetch-voice.sh`).
- Model services can hold realtime voice models: each model is a task model or a realtime voice model, and a service has an optional realtime voice URL. "Test connection" checks the realtime connection when a service has only voice models.
- `scripts/regression.py`: one-command regression run on a connected phone or emulator (typed tasks: direct answers, a settings lookup, a named app, a money refusal; voice calls: chat, time, a task read back, a merged addition, stop, goodbye), with a pass/fail table of time, steps and tokens.
- `scripts/realtime-probe.py` tries a realtime voice endpoint from a Mac; `VoiceTestReceiver` drives voice calls over adb for testing without a microphone.

- Every model call logs where its time went (connect, send, waiting for the service, read), the sizes, and cached and reasoning tokens when the service reports them ("n/a" when it does not, which is not the same as 0). `VoiceTestReceiver cachetest` measures whether the task model's service reuses a repeated prompt prefix.
- Wake test (Settings, under voice wake-up): every burst of speech counts as an attempt; a wake-up only beeps, and stopping shows the wake rate and each attempt's outcome (by the spotter, the recognizer, both, missed by one syllable, or missed). Each attempt is also logged, never the words.
- Logs for troubleshooting: every flow writes to a daily file kept 7 days (keys, addresses, e-mail and phone numbers masked; never what you said, task text or screen content), and Settings > Data > Export logs shares a zip with the logs, app and device info, settings and a summary of recent tasks. See docs/logging.md.

- Voice wake-up checks itself every 30 s: when Android silences the microphone (another app records, or the service was restarted from the background) the notification says so instead of failing quietly, and the log records a heartbeat every 10 minutes. It also restarts with the accessibility service after the app was killed.
- Voice calls recognize each finished sentence again with a bigger offline model (Paraformer small, Chinese, 78 MB). It is not in the APK: the app downloads it from this project's GitHub release `voice-models-1` when voice calls are set up (by itself on Wi-Fi; on mobile data from Settings, which shows progress and can delete it), checks its SHA-256 and resumes interrupted downloads: the small streaming model still decides when you stop and whether you talk over the answer, and the bigger one, 60-200 ms per sentence on a phone, gives the text that is sent and shown. Without it the streaming text is used.
- Voice call captions are corrected: the sentence goes to the voice model at once, and in parallel the task model fixes homophones and near-sounding words with the installed apps' names as context ("高的地图" -> 高德地图); the caption changes about 0.4-0.7 s later. The voice model is also told its input is speech recognition and to read it by sound. Only whether a caption changed is logged.
- Settings: higher wake sensitivity (wakes more easily, and more often by mistake), and a "keep wake-up running" row that shows whether battery optimization is on and opens the battery and Huawei/Honor app launch settings.

### Changed
- Settings is grouped: a start page of six groups (models, running tasks, voice, skills and experience, connecting other tools, general), each row showing the group's current state, and one page per group. Voice separates wake-up from voice calls. The step limit moved next to the token limits on the usage screen, which also shows the tasks and time per step of the chosen period. Clearing task records is only in General now, and the Home model chip opens the models page.
- Home shows today in one card (finished tasks, success rate, tokens, cost; tapping opens usage) instead of five tiles, and the latest five tasks with "Show all".
- The default wake word is 小特小特 (was 你好阿特, whose soft 阿 is often swallowed and whose 你好 is said ní hǎo); a wake test went from 67% to 78%. The assistant calls itself 小特. A wake word you set yourself is kept.
- Less output per step: no memory update in the turn that finishes, and optional arguments (a target description next to an index, `commits`, `summary`) left out unless needed. Final steps output 117-133 tokens instead of 195-203. Each step's log now shows how many characters the reasoning and each tool's arguments took.
- Faster tasks: the first turn plans and acts together instead of only planning, each turn's reasoning is one or two sentences, and the start pause is skipped when the run did not start from our own screen. On three test tasks: 24.2 s instead of 27.5 s, 23.8 s instead of 44.8 s, 12.5 s instead of 14.2 s.
- When fetching the model list fails, the message says that model names can be added by hand (some subscription plans have no model list).

### Fixed
- Voice wake-up has a second path: while someone speaks, up to 4 s are run through the offline speech recognizer and compared with the wake word by sound (toneless pinyin, one dropped, extra or rhyming syllable allowed for 4+ characters, as long as the last syllable or a rhyme of it is there: 你好特, 你好啊特 and 你好阿德 count, 你好啊 does not). The small spotter alone missed the wake word often. The log says which path woke it; what was said is not logged.
- The wake word was often missed: it is now also registered as spoken (third-tone sandhi: 你好 is said ní hǎo, not nǐ hǎo), for custom wake words too, and normal sensitivity is a bit easier (boost 1.5, threshold 0.20 instead of the stock 1.0 / 0.25; high is 2.0 / 0.12).
- Voice wake-up listens within a second of starting: the wake word model loads first and the others in the background (all at once took 10-25 s, once 85 s on a throttled phone, and wake words in that time were missed). The notification says it is getting ready until then.
- Tasks started by voice stay on the screen where they ended instead of opening the app's result page; the result is spoken.
- Voice calls connect while the audio devices open, not after (about 0.7 s sooner).
- Voice calls: background voices nearby were sent as the user's sentences; one-character turns are ignored (except "停" and answers to a card on screen). The voiceprint is recorded through the same audio path as calls, its threshold is 0.30 (the user scored 0.52-0.64 on a phone, people nearby 0.03-0.23), it is re-checked once per second of speech instead of every 100 ms, and a rejected sentence says so on the panel.
- Voice calls reconnect when the service does not confirm the session within 6 s (one setup took 80 s on 4G), keeping what was said.
- The floating bubble, voice call panel, confirmation cards and notifications follow the language picked in the app instead of the system language.
- Logs mask bare host names and IP addresses too (connection errors carried the service address).
- A voice call could hang up while a task was about to start (between leaving the start buffer and running) and kill it; the first sentence could reach the service before the call was set up; talking over an answer that had not started yet left the next answer waiting 30 s.
- A voice call stuck on "thinking" for 50 s: talking right after it connected cancelled the greeting before it was even sent, the service ignored the cancel and finished it first. An answer not sent yet is now withdrawn instead. The log says how much audio of a cancelled answer still arrived.
- Voice wake-up went deaf after an offline exchange whose task never started (it waited for the task's end for good); voice calls waited the same way. Both give up after 10 s and say the task did not start.
- Short model calls (caption fixing) ask Qwen-style services not to think first (`enable_thinking: false`; dropped if a service rejects it): thinking had used all 400 tokens with no answer on 6 of 18 sentences.
- Questions phone status answers were checked again in Settings when a learned lesson pointed there.
- Confirmation summaries, questions and the final answer are written in the language of the task, so an English task no longer gets a Chinese confirmation or answer.

## [0.3.0] - 2026-11-12

Steadier runs (task memory, stale-screen guards, the screen's language), learning from finished runs, confirmation of sensitive actions, usage limits, and no money apps or money actions.

### Upgrading from 0.2.0
- Taps that send, call, delete, post, invite or change a setting now ask you first (Settings > Run > Confirm sensitive actions).
- Banking, payment, wallet, securities and crypto apps, WeChat, QQ and Alipay, and tasks about payments, transfers or purchases are refused. This cannot be turned off.
- A task stops after 50 steps or 300k tokens, and no new task starts after 2M tokens in a day. All three are adjustable.
- Each finished task gets one extra model call to review it (Settings > Experience > Review each finished task turns it off).

### Added
- Money is off limits, with no setting to turn it off: banking, payment, wallet, securities and crypto apps (a list of known apps plus app and package name patterns such as bank, 银行, pay, 支付, wallet, 证券) are never opened or operated, tasks about transfers, payments, purchases or such apps are refused before they start, and buttons that pay, transfer or buy are never tapped, also over MCP and in skill replays. This replaces the WeChat / QQ / Alipay block.
- Built-in model prices: about 1,800 chat models from LiteLLM's price list (MIT), bundled as `assets/prices.json` and refreshed with `scripts/update-prices.py`, so official APIs show a cost without entering prices. Model ids match approximately when needed: quantization tags (-fp8, -awq, ...) and trailing parts are dropped one by one (Qwen3.8-Flash-Next-FP8 is priced as qwen3.8-flash, shown with "≈"). A price entered for a service overrides it; the model service editor lists which models matched (with the price's source, e.g. a reseller for open-weights models) and which need a price.
- The home screen shows today's tokens (against the daily limit) and cost; tapping them opens the usage screen.
- Model usage and limits (Settings > Model usage and limits): every model call (tasks, reviews, voice correction, connection tests) is counted per day, model and purpose, with an estimate when the API returns no token counts. Optional prices per million tokens in the model service editor give an estimated cost, also in run details. A per-task token limit (default 300k) stops a run, and a daily limit (default 2M) refuses new runs; reviews stop at 80% of the daily limit. Limits are checked before each call.
- After a declined confirmation the agent is told to finish (not to look for another way to the same change), and turns that only update the memory get a nudge to act or finish. A decline is recorded as a not-done part, never as a dead end, and earlier runs' notes about declines are not carried into a new run: a phone test had routed around a decline from the previous run through the control center.
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

[Unreleased]: https://github.com/tianxia--/artemis-ondevice/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/tianxia--/artemis-ondevice/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/tianxia--/artemis-ondevice/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/tianxia--/artemis-ondevice/releases/tag/v0.1.0
