# Contributing / 贡献指南

Thanks for helping! This is a learning and research project; small, focused changes are the
easiest to review.

感谢参与！这是一个学习和研究项目，改动越小、越聚焦，越容易审核。

## Set up / 环境准备

1. JDK 17 and the Android SDK (platform 35). / JDK 17 和 Android SDK（platform 35）。
2. `scripts/fetch-voice.sh` once, for the voice library and models. / 运行一次 `scripts/fetch-voice.sh` 下载语音库和模型。
3. `git config core.hooksPath .githooks` to turn on the commit checks. / 运行 `git config core.hooksPath .githooks` 启用提交检查。
4. `scripts/dev-install.sh [serial]` to build and install on a device or emulator. / 运行 `scripts/dev-install.sh [serial]` 编译并安装到设备或模拟器。

## Making a change / 提交改动

- Open an issue first for anything larger than a bug fix. / 修 bug 以外的较大改动，请先开 Issue 讨论。
- Match the surrounding code: Java, platform APIs for the agent core, comments only where the code does not say it. / 和周围代码保持一致：Java，智能体核心只用平台 API，注释只写代码本身表达不了的内容。
- Every user-facing string goes into both `values/strings.xml` (Chinese) and `values-en/strings.xml` (English). / 所有界面文字都要同时写进 `values/strings.xml`（中文）和 `values-en/strings.xml`（英文）。
- Never log or display API keys, base URLs, task text or typed text. / 不要在日志或界面上输出 API Key、Base URL、任务内容或输入的文字。
- Add a line under "Unreleased" in `CHANGELOG.md` for user-visible changes. / 用户能感知的改动，在 `CHANGELOG.md` 的 Unreleased 下加一行。
- Commit messages: a short summary line, then what changed and why. No co-author or "generated with" lines; the commit hook rejects them. / 提交说明：一行简短摘要，再写改了什么、为什么。不要加协作者或「Generated with」署名，提交检查会拒绝。

## Releasing (maintainers) / 发布（维护者）

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, and run `python3 scripts/update-prices.py` for current model prices. / 修改 `app/build.gradle.kts` 里的 `versionCode` 和 `versionName`，并运行 `python3 scripts/update-prices.py` 更新模型价格。
2. Move the "Unreleased" entries in `CHANGELOG.md` under a new version heading. / 把 `CHANGELOG.md` 里 Unreleased 的内容移到新版本标题下。
3. Commit, then `git tag vX.Y.Z && git push origin vX.Y.Z`. The release workflow builds, signs and publishes the APK. / 提交后执行 `git tag vX.Y.Z && git push origin vX.Y.Z`，release 工作流会自动编译、签名并发布 APK。

By contributing you agree that your contributions are licensed under the [Apache License 2.0](LICENSE).
提交贡献即表示你同意以 [Apache License 2.0](LICENSE) 授权你的贡献。
