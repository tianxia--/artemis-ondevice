#!/usr/bin/env python3
"""Try a Qwen-Audio realtime voice endpoint from a Mac before building it into the app.

Modes:
  say   Speak a sentence with macOS `say`, stream it like a microphone, play the reply.
  mic   Talk through the Mac microphone. Without --headphones the microphone is muted
        while the reply plays, or the speakers would interrupt it.

The model gets the same tools the app will give it (run_phone_task, stop_task); calls are
answered with a simulated result, nothing touches a phone.

The API key comes from the environment variable ARTEMIS_REALTIME_KEY or is asked for
(hidden). It is never printed or logged. The log (--log) holds events without audio.

Needs: Python 3.9+, `pip3 install websockets`, ffmpeg (microphone and speaker access).

Examples:
  python3 scripts/realtime-probe.py say "打开设置看看电量"
  python3 scripts/realtime-probe.py mic
  python3 scripts/realtime-probe.py mic --turn server_vad --headphones
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import getpass
import json
import os
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

import websockets

DEFAULT_URL = "wss://token-plan.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime"
DEFAULT_MODEL = "qwen-audio-3.0-realtime-plus"
IN_RATE, OUT_RATE = 16_000, 24_000
CHUNK = IN_RATE * 2 // 10  # 100 ms of 16-bit mono

INSTRUCTIONS = (
    "你是手机助手「小特」，用语音和用户交流，回答简短、口语化，一般一两句话。"
    "用户要你在手机上做事时，先说「收到」并简单复述，然后调用 run_phone_task，task 写成一句清楚的指令。"
    "用户只是聊天或提问、不需要操作手机时，直接回答，不要调用工具。"
    "用户要停止正在做的事时，调用 stop_task。"
    "涉及转账、付款、购买、银行、支付类应用的事一律不做，直接告诉用户不支持。"
    "你不能替用户确认任何敏感操作。"
)

TOOLS = [
    {"type": "function", "function": {
        "name": "run_phone_task",
        "description": "让手机智能体在手机上执行一个任务（打开应用、查看信息、修改设置、发消息等）",
        "parameters": {"type": "object", "properties": {
            "task": {"type": "string", "description": "要执行的任务，一句清楚的指令"}},
            "required": ["task"]}}},
    {"type": "function", "function": {
        "name": "stop_task",
        "description": "停止手机上正在执行的任务",
        "parameters": {"type": "object", "properties": {}}}},
]


def now() -> float:
    return time.monotonic()


class Player:
    """Streams 24 kHz PCM to the speakers through ffmpeg; can be cut off for barge-in."""

    def __init__(self) -> None:
        self.proc: subprocess.Popen | None = None
        self.busy_until = 0.0  # estimated end of playback (monotonic seconds)
        self.seconds = 0.0

    def _start(self) -> None:
        self.proc = subprocess.Popen(
            ["ffmpeg", "-v", "error", "-f", "s16le", "-ar", str(OUT_RATE), "-ac", "1", "-i", "pipe:0",
             "-f", "audiotoolbox", "-"], stdin=subprocess.PIPE)

    def write(self, pcm: bytes) -> None:
        if self.proc is None or self.proc.poll() is not None:
            self._start()
        assert self.proc and self.proc.stdin
        self.proc.stdin.write(pcm)
        self.proc.stdin.flush()
        length = len(pcm) / (OUT_RATE * 2)
        self.seconds += length
        self.busy_until = max(self.busy_until, now()) + length

    def interrupt(self) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.kill()
        self.proc = None
        self.busy_until = 0.0

    def finish(self) -> None:
        if self.proc and self.proc.poll() is None:
            assert self.proc.stdin
            self.proc.stdin.close()
            self.proc.wait()
        self.proc = None


class Probe:
    def __init__(self, args: argparse.Namespace, key: str) -> None:
        self.args = args
        self.key = key
        self.player = Player()
        self.log = open(args.log, "a", encoding="utf-8")
        self.speech_stopped_at = 0.0
        self.audio_end_at = 0.0
        self.first_audio = True
        self.transcript = ""
        self.pending_tools = 0
        self.last_done = asyncio.Event()
        self.sent_seconds = 0.0
        self.audio_started_at = 0.0
        self.warned_slow = False
        self.usage: list[dict] = []
        self.counts: dict[str, int] = {}

    # ------------------------------------------------------------------ plumbing

    def record(self, direction: str, event: dict) -> None:
        e = dict(event)
        for field in ("audio", "delta"):
            if field in e and e.get("type", "").endswith(("append", "audio.delta")):
                e[field] = f"<{len(e[field])} base64 chars>"
        self.log.write(json.dumps({"t": round(time.time(), 3), "dir": direction, **e}, ensure_ascii=False) + "\n")
        self.log.flush()

    async def send(self, ws, event: dict) -> None:
        event = {"event_id": "evt_" + uuid.uuid4().hex[:16], **event}
        self.record("out", event)
        await ws.send(json.dumps(event, ensure_ascii=False))

    async def send_audio(self, ws, pcm: bytes) -> None:
        if not self.audio_started_at:
            self.audio_started_at = now()
        self.sent_seconds += len(pcm) / (IN_RATE * 2)
        await self.send(ws, {"type": "input_audio_buffer.append", "audio": base64.b64encode(pcm).decode()})
        # Audio is produced in real time; if sending lags far behind, the upload is too slow.
        lag = (now() - self.audio_started_at) - self.sent_seconds
        if lag > 1.5 and not self.warned_slow:
            self.warned_slow = True
            print(f"⚠ 上传跟不上：发出 {self.sent_seconds:.1f} 秒音频用了 {now() - self.audio_started_at:.1f} 秒。"
                  "实时语音约需 45 KB/s 上传带宽，换个网络再试")

    def session(self) -> dict:
        s: dict = {
            "modalities": ["audio", "text"],
            "instructions": INSTRUCTIONS,
            "input_audio_format": "pcm",
            "output_audio_format": "pcm",
            "tools": TOOLS,
        }
        if self.args.voice:
            s["voice"] = self.args.voice
        if self.args.turn == "manual":
            s["turn_detection"] = None
        elif self.args.turn == "server_vad":
            s["turn_detection"] = {"type": "server_vad", "silence_duration_ms": self.args.silence_ms}
        else:
            s["turn_detection"] = {"type": self.args.turn}
        return s

    # ------------------------------------------------------------------ events

    async def receive(self, ws) -> None:
        async for raw in ws:
            event = json.loads(raw)
            kind = event.get("type", "")
            self.counts[kind] = self.counts.get(kind, 0) + 1
            self.record("in", event)
            await self.handle(ws, kind, event)

    async def handle(self, ws, kind: str, e: dict) -> None:
        if kind == "session.created":
            print(f"· 已连接（session {e.get('session', {}).get('id', '?')}）")
        elif kind == "session.updated":
            s = e.get("session", {})
            print(f"· 会话已配置：voice={s.get('voice')} turn={json.dumps(s.get('turn_detection'), ensure_ascii=False)}")
        elif kind == "error":
            print(f"✗ 错误：{json.dumps(e.get('error', e), ensure_ascii=False)}")
        elif kind == "input_audio_buffer.speech_started":
            print("· 听到你在说话")
            self.player.interrupt()
        elif kind == "input_audio_buffer.speech_stopped":
            self.speech_stopped_at = now()
            print("· 你说完了")
        elif kind == "conversation.item.input_audio_transcription.completed":
            print(f"你：{e.get('transcript', '').strip()}")
        elif kind == "response.created":
            self.first_audio = True
            self.transcript = ""
            self.last_done.clear()
        elif kind == "response.audio.delta":
            if self.first_audio:
                self.first_audio = False
                ref = self.speech_stopped_at or self.audio_end_at
                if ref:
                    print(f"· 首个语音 {int((now() - ref) * 1000)} ms 后到达（从你说完算）")
            self.player.write(base64.b64decode(e.get("delta", "")))
        elif kind == "response.audio_transcript.delta":
            self.transcript += e.get("delta", "")
        elif kind == "response.audio_transcript.done":
            print(f"阿特：{e.get('transcript', self.transcript).strip()}")
        elif kind == "response.function_call_arguments.done":
            await self.tool_call(ws, e)
        elif kind == "response.done":
            r = e.get("response", {})
            usage = r.get("usage") or e.get("usage")
            if usage:
                self.usage.append(usage)
            print(f"· 回答结束：{r.get('status', '?')}" + (f"，用量 {json.dumps(usage, ensure_ascii=False)}" if usage else ""))
            if self.pending_tools == 0:
                self.last_done.set()

    async def tool_call(self, ws, e: dict) -> None:
        name, call_id = e.get("name", ""), e.get("call_id", "")
        try:
            arguments = json.loads(e.get("arguments") or "{}")
        except json.JSONDecodeError:
            arguments = {"raw": e.get("arguments")}
        print(f"⚙ 工具调用：{name} {json.dumps(arguments, ensure_ascii=False)}")
        if name == "run_phone_task":
            output = {"status": "completed", "result": f"（模拟）已完成：{arguments.get('task', '')}。手机电量 80%。"}
        elif name == "stop_task":
            output = {"status": "stopped"}
        else:
            output = {"error": f"unknown tool {name}"}
        self.pending_tools += 1
        await asyncio.sleep(self.args.tool_delay)  # a real task takes a while
        await self.send(ws, {"type": "conversation.item.create", "item": {
            "type": "function_call_output", "call_id": call_id,
            "output": json.dumps(output, ensure_ascii=False)}})
        await self.send(ws, {"type": "response.create"})
        self.pending_tools -= 1

    # ------------------------------------------------------------------ modes

    async def greet(self, ws) -> None:
        """What the app will do after the wake word: let the model ask what to do."""
        await self.send(ws, {"type": "conversation.item.create", "item": {
            "type": "message", "role": "user",
            "content": [{"type": "input_text", "text": "（用户刚喊了你的名字，还没说要做什么。用一句话问他要做什么。）"}]}})
        await self.send(ws, {"type": "response.create"})

    async def run_say(self, ws) -> None:
        pcm = synthesize(self.args.text)
        print(f"· 用 say 合成了 {len(pcm) / (IN_RATE * 2):.1f} 秒语音，开始发送：{self.args.text}")
        for i in range(0, len(pcm), CHUNK):
            await self.send_audio(ws, pcm[i:i + CHUNK])
            await asyncio.sleep(0.1)
        self.audio_end_at = now()
        if self.args.turn == "manual":
            await self.send(ws, {"type": "input_audio_buffer.commit"})
            await self.send(ws, {"type": "response.create"})
        # Trailing silence so server-side turn detection notices the end of speech.
        for _ in range(int(self.args.wait * 10)):
            if self.last_done.is_set() and self.player.busy_until < now():
                break
            await self.send_audio(ws, bytes(CHUNK))
            await asyncio.sleep(0.1)
        self.player.finish()

    async def run_mic(self, ws) -> None:
        if self.args.greet:
            await self.greet(ws)
        mic = subprocess.Popen(
            ["ffmpeg", "-v", "error", "-f", "avfoundation", "-i", f":{self.args.mic_device}",
             "-ac", "1", "-ar", str(IN_RATE), "-f", "s16le", "pipe:1"], stdout=subprocess.PIPE)
        print("· 麦克风已打开，直接说话；Ctrl-C 结束" + ("" if self.args.headphones else "（外放：它说话时麦克风静音）"))
        loop = asyncio.get_running_loop()
        try:
            while True:
                assert mic.stdout
                pcm = await loop.run_in_executor(None, mic.stdout.read, CHUNK)
                if not pcm:
                    raise RuntimeError("麦克风没有数据（检查终端的麦克风权限）")
                if not self.args.headphones and self.player.busy_until > now():
                    pcm = bytes(len(pcm))  # muted while the reply plays
                await self.send_audio(ws, pcm)
        finally:
            mic.kill()
            self.player.interrupt()

    async def run(self) -> None:
        url = self.args.url
        url += ("&" if "?" in url else "?") + "model=" + self.args.model
        print(f"· 连接 {url}")
        try:
            async with websockets.connect(
                    url, additional_headers={"Authorization": f"Bearer {self.key}"},
                    user_agent_header="artemis-ondevice-probe", max_size=None, open_timeout=15) as ws:
                receiver = asyncio.create_task(self.receive(ws))
                await self.send(ws, {"type": "session.update", "session": self.session()})
                await asyncio.sleep(0.5)
                try:
                    await (self.run_say(ws) if self.args.mode == "say" else self.run_mic(ws))
                finally:
                    receiver.cancel()
        except websockets.exceptions.InvalidStatus as e:
            body = e.response.body.decode("utf-8", "replace")[:500] if e.response.body else ""
            print(f"✗ 握手被拒绝：HTTP {e.response.status_code} {body}")
        except (OSError, asyncio.TimeoutError) as e:
            print(f"✗ 连不上：{type(e).__name__}: {e}")
        finally:
            self.summary()

    def summary(self) -> None:
        print("\n—— 小结 ——")
        print(f"发送音频 {self.sent_seconds:.1f} 秒，收到语音 {self.player.seconds:.1f} 秒")
        if self.usage:
            print("用量（每次回答）：")
            for u in self.usage:
                print("  " + json.dumps(u, ensure_ascii=False))
        print("事件统计：" + ", ".join(f"{k}×{v}" for k, v in sorted(self.counts.items())))
        print(f"事件日志（不含音频和 Key）：{self.args.log}")
        self.log.close()


def synthesize(text: str) -> bytes:
    with tempfile.TemporaryDirectory() as tmp:
        aiff = Path(tmp) / "say.aiff"
        subprocess.run(["say", "-v", "Tingting", "-o", str(aiff), text], check=True)
        out = subprocess.run(["ffmpeg", "-v", "error", "-i", str(aiff), "-ac", "1", "-ar", str(IN_RATE),
                              "-f", "s16le", "pipe:1"], check=True, capture_output=True)
        return out.stdout


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("mode", choices=["say", "mic"])
    p.add_argument("text", nargs="?", default="打开设置，看看手机电量还有多少", help="say 模式要说的话")
    p.add_argument("--url", default=DEFAULT_URL)
    p.add_argument("--model", default=DEFAULT_MODEL)
    p.add_argument("--voice", help="音色 ID；不填用服务默认")
    p.add_argument("--turn", default="smart_turn_v2", choices=["smart_turn_v2", "smart_turn", "server_vad", "manual"])
    p.add_argument("--silence-ms", type=int, default=600, help="server_vad 判定说完的静音时长")
    p.add_argument("--headphones", action="store_true", help="戴耳机：它说话时也听麦克风，可以打断")
    p.add_argument("--no-greet", dest="greet", action="store_false", help="mic 模式开场不让它先问")
    p.add_argument("--mic-device", default="0", help="ffmpeg avfoundation 音频设备序号")
    p.add_argument("--tool-delay", type=float, default=2.0, help="模拟任务执行的秒数")
    p.add_argument("--wait", type=float, default=30.0, help="say 模式最多等回答的秒数")
    p.add_argument("--log", default="realtime-probe.log.jsonl")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    key = os.environ.get("ARTEMIS_REALTIME_KEY") or getpass.getpass("API Key（输入时不显示）：")
    if not key.strip():
        sys.exit("没有 API Key")
    try:
        asyncio.run(Probe(args, key.strip()).run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
