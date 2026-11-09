# Artemis On-Device

[English](README.md) | **简体中文**

一个完全运行在 Android 手机上的手机智能体（Android 11 及以上）。用一句话描述任务（例如「打开设置，告诉我电量」），
智能体会读取屏幕，自己点击、滑动、输入，直到完成任务。只有调用模型时需要联网，访问你自己配置的
OpenAI 兼容或 Anthropic 接口。

本项目是参考 Google 的 [ARTEMIS](https://github.com/google/artemis)（Apache-2.0）独立开发的 Android 移植版本，
**仅供学习和研究使用**，与 Google 没有任何关联，详见[免责声明](#免责声明)。

## 演示视频

添加模型服务、执行任务、查看任务详情、切换应用语言（仓库里也有一份：[docs/demo.mp4](docs/demo.mp4)）：

https://github.com/user-attachments/assets/de1a104c-70e2-4eec-8132-3c98319e0f43

## 下载

到 [Releases](https://github.com/tianxia--/artemis-ondevice/releases) 下载 APK，安装到 Android 11 及以上的手机。
Android 13 及以上，不是从应用商店安装的应用，无障碍开关可能是灰的（提示「受限设置」）：
打开 设置 > 应用 > Artemis On-Device，点右上角 ⋮，选 **允许受限设置**，再去开启无障碍服务。

## 工作原理

每一步：截图 + 带编号的界面元素列表（无障碍服务）-> 调用一次模型（带工具）-> 在手机上执行工具调用
（`tap` / `long_press` / `type_text` / `swipe` / `back` / `home` / `open_app` / `wait` / `finish`）。
优先按元素编号定位，找不到时用 0-1000 归一化坐标。默认不限步数；最近几步以文字形式保留为历史，
只发送最新一张截图。

## 功能

- **悬浮球**（无障碍悬浮层，不需要额外权限）：实时显示当前步骤和阶段。点它可以暂停、停止、查看详情、
  发起新任务或再次运行上一个任务。
- **任务记录**：每次运行都会保存每一步的截图、动作、推理、耗时和 tokens（`files/runs/<id>/`，保留最近 100 条）。
- **任务详情**：结果、总耗时和每步耗时、模型耗时中位数 / P90 / 最大值、模型耗时占比、读屏和动作平均耗时、
  输入 / 输出 tokens、失败动作、每步耗时图和逐步记录。
- **技能（录制与回放）**：任务成功后可以保存为技能，下次执行同样的任务时直接回放记录的步骤，不调用模型，
  最后只调用一次模型确认结果并回答；如果找不到记录的目标，就由模型从当前界面接着完成。
- **模型服务**：内置预设，也可以自定义 OpenAI 兼容或 Anthropic 接口；可以从服务获取模型列表，也可以测试连接。
- **隐私**：API Key 用 Android Keystore 中的 AES-GCM 密钥加密，不写日志。Base URL 在界面上默认隐藏
  （点眼睛图标才显示），错误信息里也会去掉地址。Logcat（`ArtemisAgent`）只记录耗时和动作名称。
- **语言**：设置 > 语言，可以在简体中文和英文之间切换，或跟随系统。
- **语音唤醒**（可选，仅支持普通话）：说「你好阿特」唤醒，然后说出任务。唤醒、识别和播报都在手机上离线完成，
  详见 [docs/voice-design.md](docs/voice-design.md)。
- 为避免账号被封，不操作微信、QQ 和支付宝。

## 环境要求

- JDK 17（可以用 Android Studio 自带的 JDK）
- Android SDK，包含 platform 35（在 `local.properties` 里配置 `sdk.dir`）
- Android 11（API 30）及以上的手机或模拟器

## 编译和安装

```bash
scripts/fetch-voice.sh            # 只需一次：下载语音库和离线模型（不提交到 git）
scripts/dev-install.sh            # 编译、安装到 emulator-5554，并重新绑定无障碍服务
scripts/dev-install.sh <serial>   # 安装到其他设备
```

只编译 APK：`./gradlew assembleDebug`，输出在 `app/build/outputs/apk/debug/app-debug.apk`。

重新安装会解除无障碍服务的绑定，脚本会把它移除再重新加上。在手机上也可以到 设置 > 无障碍 > Artemis On-Device 手动开启。

## 快速上手

1. 首页提示时，开启无障碍服务。
2. 点 **添加模型**，选一个预设，或者填写自己的 Base URL、接口类型和 API Key，然后点 **从服务获取**
   或手动输入模型 ID，最后 **保存**。
3. 在首页输入任务，点 **开始执行**。执行过程可以在悬浮球里查看，结束后会自动打开任务详情。

也可以通过 adb 添加模型服务（只有 adb shell 或系统可以发送这个广播）：

```bash
adb shell am broadcast -a com.artemis.ondevice.IMPORT_PROVIDER -n com.artemis.ondevice/.ProviderImportReceiver \
  --es id my-llm --es name "My LLM" --es base_url https://example.com/v1 --es api openai-completions \
  --es models "model-a,model-b" --es active_model model-a --es api_key <key>
```

## 模拟器提示

- 如果模拟器能访问 IP 但解析不了域名，用指定的 DNS 启动：
  `emulator -avd <name> -dns-server 8.8.8.8,1.1.1.1`。
- `adb shell am force-stop com.artemis.ondevice` 会关闭无障碍服务，重新运行 `scripts/dev-install.sh` 即可恢复。

## 参与贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md) 和 [CHANGELOG.md](CHANGELOG.md)。

## 提交检查

`.githooks/` 会拒绝带协作者署名或「Generated with」署名的提交说明；作者信息或新增内容匹配
`.git/info/blocked-patterns`（只在本地，不会推送；每行一个扩展正则）里的关键词时，也会拒绝提交。
每次克隆后启用一次：

```bash
git config core.hooksPath .githooks
```

## 已知限制

- 设置了 FLAG_SECURE 的界面（支付、密码）无法截图，这时模型只能拿到元素列表。
- Android 限制 `takeScreenshot` 的频率（约每秒 3 次），代码里内置了一次重试。
- 有些应用会对无障碍服务隐藏控件，模型会根据截图定位这些控件。
- 部分厂商的省电策略可能在后台停止任务，需要允许本应用不受限制地在后台运行。
- Android 12 及以下，悬浮球和通知跟随系统语言，不跟随应用内的语言设置。

## 致谢与引用

- [google/artemis](https://github.com/google/artemis)（Apache-2.0）：本项目参考的设计，包括 Flash
  「观察-行动」循环、工具集、优先按元素编号定位并以归一化坐标兜底，以及模型服务注册表。
  本项目用 Java 重新实现了这些设计，通过无障碍服务直接在手机上运行，而不是由电脑通过 adb 控制手机。
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0）：离线语音唤醒、识别和合成。
  语音模型的许可证归各自的作者所有。
- AndroidX AppCompat 和 Material Components for Android（Apache-2.0）。

## 免责声明

- **仅供学习和研究。** 本项目是研究手机 GUI 智能体的实验性项目，不是产品，也不适合生产环境；
  不得用于商业用途、自动化滥用、绕过应用或平台规则，或任何违法用途。
- **与 Google 无关。** 「ARTEMIS」指本项目参考的 Google 开源项目。本项目不是由 Google 或 ARTEMIS
  作者开发、认可或支持的。
- **智能体可能出错。** 每一次点击、滑动和输入都由大模型决定，可能点错元素或理解错任务。
  请使用测试设备或测试账号，执行时留意屏幕，不要让它处理付款、转账、给真人发消息、删除数据、
  修改账号或系统设置等操作。它在你的设备上做的任何操作都由你自己负责。
- **数据会发送给你配置的模型服务。** 截图和屏幕上的元素列表会发送给你配置的模型服务，
  其中可能包含屏幕上显示的个人信息。请只使用你信任的服务，并遵守它们的条款。
  本项目的作者不会收到任何数据。
- **遵守第三方条款。** 自动操作其他应用可能违反它们的服务条款，导致账号受限。
  因此本项目不操作微信、QQ 和支付宝；自动操作其他应用前请先确认其规则。
- **不提供任何担保。** 本软件按「原样」提供，不附带任何形式的担保或条件（见 Apache License 2.0）。
  作者不对使用本软件造成的任何损失、数据丢失或账号受限承担责任。

## 许可证

[Apache License 2.0](LICENSE)。引用说明和第三方组件见 [NOTICE](NOTICE)。
