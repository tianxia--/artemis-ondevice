# Artemis On-Device

Runs a phone agent entirely on an Android phone (Android 11+). Only the
model call goes online, to an OpenAI-compatible or Anthropic endpoint you configure.

## How it works

Each step: screenshot + indexed UI element list (accessibility service) -> one model call
with tools -> the tool calls run on the phone (tap / long_press / type_text / swipe / back /
home / open_app / wait / finish). Element index first, 0-1000 normalized coordinates as the
fallback, no step cap by default; the last few steps are kept
as text history, only the latest screenshot is sent.

- **Floating bubble** (accessibility overlay, no extra permission): live step and phase;
  tap it to pause the run, stop it, open details, start a new task or run the last one again.
- **History**: every run is stored with per-step screenshots, actions, reasoning, timings
  and tokens (`files/runs/<id>/`, newest 100 kept).
- **Run details**: result, total / per-step time, model median / P90 / max, model share of
  time, screen-read and action averages, tokens in / out, failed actions, a per-step timing
  chart and the step-by-step record.
- **Model services**: presets, id / base-URL rules and the active model. API keys are encrypted with an Android Keystore AES-GCM key; they are
  never logged. Logcat (`ArtemisAgent`) only carries timings and action names.

## Build and install

```bash
scripts/dev-install.sh            # build, install on emulator-5554, re-bind accessibility
scripts/dev-install.sh <serial>   # another device
```

Reinstalling unbinds the accessibility service; the script removes and re-adds it. On a
phone you can also turn it on in Settings > Accessibility > Artemis On-Device.

## Known limits

- Screens with FLAG_SECURE (payments, passwords) give no screenshot; the model then only
  gets the element list.
- `takeScreenshot` is rate-limited by Android (~3/s); one retry is built in.
- Some apps mark buttons "not important for accessibility" in a way the service still does
  not see; the model locates those from the screenshot.
- Aggressive OEM battery savers may stop the run in the background; allow the app to run
  unrestricted there.
