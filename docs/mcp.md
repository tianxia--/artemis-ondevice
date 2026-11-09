# MCP server

**English** | [简体中文](mcp.zh-CN.md)

Artemis On-Device can run a [Model Context Protocol](https://modelcontextprotocol.io) server on
the phone, so AI tools on your computer (Claude Code, Cursor and other MCP clients) can give the
phone tasks, or look at the screen and operate it themselves.

It uses the Streamable HTTP transport at `http://<host>:8765/mcp`, and every request needs the
access token shown in the app.

## Turn it on

Settings > **MCP server** > **Turn on the MCP server**. A notification shows while it runs; its
**Stop** button turns it off.

- **Localhost only** (default): the server listens on the phone itself. Connect over adb, by USB or
  wireless debugging:

  ```bash
  adb forward tcp:8765 tcp:8765
  ```

  The address is then `http://127.0.0.1:8765/mcp`.
- **Allow LAN access**: devices on the same network connect to `http://<phone-ip>:8765/mcp`
  directly. This is plain HTTP, so the token can be captured on the network; only use it on
  networks you trust.

## Connect a client

**Copy the Claude Code command** in Settings copies a ready-made command with your address and
token. By hand:

```bash
claude mcp add --transport http artemis-phone http://127.0.0.1:8765/mcp --header "Authorization: Bearer <token>"
```

Other clients (Cursor `mcp.json` and similar):

```json
{
  "mcpServers": {
    "artemis-phone": {
      "url": "http://127.0.0.1:8765/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

## Tools

Let the phone's own agent do the work (it uses the model configured on the phone and replays a
saved skill when one matches):

| Tool | What it does |
|---|---|
| `run_task(task, wait_seconds?)` | Starts a run and returns its id; with `wait_seconds` (up to 120) it waits for the result |
| `get_run(run_id, include_steps?)` | Status, answer, timing, tokens and the step-by-step record |
| `stop_run()` | Stops the run in progress |
| `list_runs(limit?)` | Recent runs |
| `get_step_screenshot(run_id, step)` | The screenshot the agent saw at a step |
| `list_skills()` | Saved skills |

Operate the phone yourself (the same actions the phone's agent uses):

| Tool | What it does |
|---|---|
| `observe(screenshot?)` | Foreground app, numbered UI elements (bounds on a 0-1000 scale) and a screenshot |
| `tap`, `long_press`, `type_text`, `swipe`, `back`, `home`, `open_app`, `wait` | Act on the screen. `index` refers to the latest `observe`; observe again after acting |

## Safety

- Off by default; every request needs the bearer token; requests from web pages (a non-localhost
  `Origin`) are refused.
- **Regenerate** in Settings makes the old token stop working at once.
- Action tools are refused while the phone's agent runs a task, while one of Artemis's own screens is
  in front (only `home`, `back`, `open_app` and `wait` are allowed there), and while WeChat, QQ or
  Alipay is in front.
- The phone shows the floating bubble during runs; you can stop at any time.

## Limits

- No progress notifications: poll `get_run`.
- One run at a time.
- No HTTPS.
