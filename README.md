# Artemis On-Device

**English** | [简体中文](README.zh-CN.md)

A phone agent that runs entirely on an Android phone (Android 11+). You describe a task in plain
language ("open Settings and tell me the battery level"), and the agent reads the screen and taps,
swipes and types until the task is done. Only the model call goes online, to an OpenAI-compatible
or Anthropic endpoint you configure.

This project is an independent Android port developed with reference to
[ARTEMIS](https://github.com/google/artemis) by Google (Apache-2.0). It is for learning and
research only and is not affiliated with Google; see [Disclaimer](#disclaimer).

## Demo

Adding a model service, running a task, the run details and switching the app language
(also in the repo as [docs/demo.mp4](docs/demo.mp4)):

https://github.com/user-attachments/assets/de1a104c-70e2-4eec-8132-3c98319e0f43

## Download

Get the APK from [Releases](https://github.com/tianxia--/artemis-ondevice/releases) and install it on an
Android 11+ phone. On Android 13 and later, the accessibility switch may be greyed out ("Restricted
setting") for an app installed outside an app store: open Settings > Apps > Artemis On-Device, tap
the ⋮ menu, choose **Allow restricted settings**, then turn the service on.

## How it works

Each step: screenshot + indexed UI element list (accessibility service) -> one model call with
tools -> the tool calls run on the phone (`tap` / `long_press` / `type_text` / `swipe` / `back` /
`home` / `open_app` / `wait` / `finish`). The element index is used first, with 0-1000 normalized
coordinates as the fallback. There is no step cap by default; the last few steps are kept as text
history and only the latest screenshot is sent.

## Features

- **Floating bubble** (accessibility overlay, no extra permission): live step and phase. Tap it to
  pause the run, stop it, open details, start a new task or run the last one again.
- **History**: every run is stored with per-step screenshots, actions, reasoning, timings and
  tokens (`files/runs/<id>/`, newest 100 kept).
- **Run details**: result, total and per-step time, model median / P90 / max, model share of time,
  screen-read and action averages, tokens in / out, failed actions, a per-step timing chart and
  the step-by-step record.
- **Skills (record and replay)**: save a completed run as a skill; the same task then replays the
  recorded steps without model calls, with one final model call to check the screen and answer.
  If a recorded target is missing, the model takes over from there.
- **Confirmation**: before sending, calling, paying, buying, deleting, posting, inviting or changing a
  setting, the agent asks you at the bottom of the screen (Allow / Allow for this task / Don't allow).
  It can also ask you to choose when the task leaves something open.
- **Usage and limits**: token use per day, model and purpose, with an estimated cost from a built-in
  price list (LiteLLM, about 1,800 models) or a price you enter; a per-task and a daily token limit keep a run from burning tokens.
- **Experience**: after each task the model reviews which steps were wasted and distills lessons
  (principles, patterns for kinds of screens, facts about one app). A lesson reaches later tasks once
  two tasks drew or confirmed it, or you approve it in Settings > Experience; lessons whose tasks keep
  failing drop back. Lessons stay on the phone and leave out personal data.
- **Model services**: built-in presets or a custom OpenAI-compatible / Anthropic endpoint. The
  model list can be fetched from the service, and the connection can be tested.
- **Privacy**: API keys are encrypted with an Android Keystore AES-GCM key and never logged. The
  base URL is masked on screen by default (tap the eye icon to show it) and removed from error
  messages. No network port is opened unless you turn on the MCP server. Logcat (`ArtemisAgent`) only carries timings and action names.
- **MCP server** (optional, off by default): AI tools on your computer, such as Claude Code or
  Cursor, can give the phone tasks or operate it directly over MCP; see [docs/mcp.md](docs/mcp.md).
- **Language**: Settings > Language switches the app between Simplified Chinese and English, or
  follows the system.
- **Voice wake-up** (optional, Mandarin only): say "你好阿特", then say the task. Wake word,
  recognition and speech run offline on the phone; see [docs/voice-design.md](docs/voice-design.md).
- **Money is off limits**: banking, payment, wallet, securities and crypto apps (including WeChat, QQ
  and Alipay) are never operated, tasks about transfers, payments or purchases are refused, and
  buttons that pay, transfer or buy are never tapped. This avoids account bans and misuse for fraud,
  and cannot be turned off.

## Requirements

- JDK 17 (Android Studio's bundled JDK works)
- Android SDK with platform 35 (`sdk.dir` in `local.properties`)
- A phone or emulator on Android 11 (API 30) or later

## Build and install

```bash
scripts/fetch-voice.sh            # once: voice library and offline models (git-ignored)
scripts/dev-install.sh            # build, install on emulator-5554, re-bind accessibility
scripts/dev-install.sh <serial>   # another device
```

The APK alone: `./gradlew assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`.

Reinstalling unbinds the accessibility service; the script removes and re-adds it. On a phone you
can also turn it on in Settings > Accessibility > Artemis On-Device.

## Getting started

1. Turn on the accessibility service when the home screen asks for it.
2. Tap **Add model**, pick a preset or enter your own base URL, API type and API key, then
   **Fetch from service** or type a model ID, and **Save**.
3. Type a task on the home screen and tap **Start**. Follow it in the floating bubble; the run
   details open when it ends.

A model service can also be added over adb (only adb shell / system can send this broadcast):

```bash
adb shell am broadcast -a com.artemis.ondevice.IMPORT_PROVIDER -n com.artemis.ondevice/.ProviderImportReceiver \
  --es id my-llm --es name "My LLM" --es base_url https://example.com/v1 --es api openai-completions \
  --es models "model-a,model-b" --es active_model model-a --es api_key <key>
```

## Emulator tips

- If the emulator can reach IP addresses but not domain names, start it with explicit DNS servers:
  `emulator -avd <name> -dns-server 8.8.8.8,1.1.1.1`.
- `adb shell am force-stop com.artemis.ondevice` turns the accessibility service off; run
  `scripts/dev-install.sh` again to turn it back on.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) and [CHANGELOG.md](CHANGELOG.md).

## Commit checks

`.githooks/` rejects commit messages with a co-author or "generated with" attribution line, and
commits whose author or added lines match a pattern in `.git/info/blocked-patterns` (local, never
pushed; one extended regex per line). Turn the hooks on once per clone:

```bash
git config core.hooksPath .githooks
```

## Known limits

- Screens with FLAG_SECURE (payments, passwords) give no screenshot; the model then only gets the
  element list.
- `takeScreenshot` is rate-limited by Android (~3/s); one retry is built in.
- Some apps hide controls from accessibility services; the model locates those from the
  screenshot.
- Aggressive OEM battery savers may stop the run in the background; allow the app to run
  unrestricted there.
- On Android 12 and below, the floating bubble and notifications follow the system language
  rather than the in-app language choice.

## Acknowledgements

- [google/artemis](https://github.com/google/artemis) (Apache-2.0): the design this project follows,
  including the Flash observe-and-act loop, the tool set, element-index-first targeting with
  normalized-coordinate fallback, and the model provider registry. This project re-implements
  them in Java to run on the phone itself through an accessibility service, rather than on a
  host computer over adb.
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0): offline wake word, speech
  recognition and speech synthesis. The voice models remain under their authors' licenses.
- AndroidX AppCompat and Material Components for Android (Apache-2.0).

## Disclaimer

- **Learning and research only.** This is an experimental project for studying mobile GUI
  agents. It is not a product, is not production-ready, and must not be used for commercial
  purposes, automated abuse, bypassing app or platform rules, or anything unlawful.
- **Not affiliated with Google.** "ARTEMIS" refers to Google's open-source project, which this
  work references. This project is not developed, endorsed or supported by Google or the
  ARTEMIS authors.
- **The agent can make mistakes.** A language model decides every tap, swipe and text entry,
  and it can act on the wrong element or misunderstand the task. Use a test device or test
  accounts, watch the run, and keep it away from payments, transfers, messages to real
  people, deletions and account or system settings. You are responsible for any action it
  takes on your device.
- **Your data goes to your model provider.** Screenshots and the on-screen element list are
  sent to the model service you configure, and may include personal information shown on the
  screen. Only use providers you trust, and follow their terms. Nothing is sent to the
  authors of this project.
- **Respect third-party terms.** Automating other apps may break their terms of service and
  get accounts restricted. WeChat, QQ and Alipay are blocked for this reason; check the rules
  of any other app before automating it.
- **No warranty.** The software is provided "as is", without warranties or conditions of any
  kind, as stated in the Apache License 2.0. The authors are not liable for any damage, data
  loss or account restrictions that result from using it.

## License

[Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attribution and third-party components.
