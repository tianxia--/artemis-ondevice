# MCP 服务

[English](mcp.md) | **简体中文**

Artemis On-Device 可以在手机上运行一个 [Model Context Protocol](https://modelcontextprotocol.io) 服务，
让电脑上的 AI 工具（Claude Code、Cursor 等 MCP 客户端）给手机派任务，或者直接看屏幕、操作手机。

传输方式是 Streamable HTTP，地址为 `http://<主机>:8765/mcp`，每次请求都必须带应用里显示的访问令牌。

## 开启

设置 > **MCP 服务** > **开启 MCP 服务**。运行时会有常驻通知，点通知上的 **停止** 即可关闭。

- **只监听本机**（默认）：电脑通过 adb 连接（USB 或无线调试都可以）：

  ```bash
  adb forward tcp:8765 tcp:8765
  ```

  然后地址是 `http://127.0.0.1:8765/mcp`。
- **允许局域网访问**：同一网络里的设备可以直接连 `http://<手机IP>:8765/mcp`。走的是明文 HTTP，
  令牌可能被网络里的其他人截获，只在你信任的网络里打开。

## 接入客户端

在设置里点 **复制 Claude Code 接入命令**，会复制一条带好地址和令牌的命令。手动输入：

```bash
claude mcp add --transport http artemis-phone http://127.0.0.1:8765/mcp --header "Authorization: Bearer <令牌>"
```

其他客户端（Cursor 的 `mcp.json` 等）：

```json
{
  "mcpServers": {
    "artemis-phone": {
      "url": "http://127.0.0.1:8765/mcp",
      "headers": { "Authorization": "Bearer <令牌>" }
    }
  }
}
```

## 工具

让手机上的智能体去做（使用手机上配置的模型，有匹配的技能时直接回放）：

| 工具 | 作用 |
|---|---|
| `run_task(task, wait_seconds?)` | 开始一个任务并返回 ID；给了 `wait_seconds`（最多 120）就等任务结束直接返回结果 |
| `get_run(run_id, include_steps?)` | 状态、结果、耗时、tokens 和逐步记录 |
| `stop_run()` | 停止当前任务 |
| `list_runs(limit?)` | 最近的任务 |
| `get_step_screenshot(run_id, step)` | 某一步智能体看到的截图 |
| `list_skills()` | 已保存的技能 |

自己操作手机（和手机智能体用的是同一套动作）：

| 工具 | 作用 |
|---|---|
| `observe(screenshot?)` | 前台应用、带编号的界面元素（0-1000 坐标）和截图 |
| `tap`、`long_press`、`type_text`、`swipe`、`back`、`home`、`open_app`、`wait` | 操作屏幕。`index` 指最近一次 `observe` 的结果，操作后要重新 observe |

## 安全

- 默认关闭；每次请求都要带令牌；来自网页的请求（`Origin` 不是 localhost）会被拒绝。
- 在设置里点 **重新生成**，旧令牌立即失效。
- 以下情况会拒绝执行动作：手机智能体正在执行任务；Artemis 自己的界面在前台（这时只允许 `home`、`back`、`open_app`、`wait`）；
  微信、QQ、支付宝在前台。
- 执行任务时手机上会显示悬浮球，随时可以停止。

## 限制

- 没有进度推送，需要用 `get_run` 轮询。
- 同一时间只能运行一个任务。
- 不支持 HTTPS。
