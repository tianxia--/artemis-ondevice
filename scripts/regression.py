#!/usr/bin/env python3
"""One-tap regression run on a phone or emulator with a debug build of Artemis On-Device.

Typed tasks are started over adb (VoiceTestReceiver "task"); voice calls are driven the same way
("call", "say", "hangup") without a microphone. Each scenario checks the run records and the
app's log file (files/logs/), then a table shows pass/fail, time, steps and tokens. Results are
also written to scripts/regression-results/ (git-ignored).

Needs: adb, a debug build installed (run-as), accessibility on, a task model configured; the
voice scenarios also need voice wake-up on and a voice call model with its key.

Usage:
  python3 scripts/regression.py                 # all scenarios on the only device
  python3 scripts/regression.py -s emulator-5554 --only typed
  python3 scripts/regression.py --only voice
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

PKG = "com.artemis.ondevice"
RECEIVER = f"{PKG}/.VoiceTestReceiver"
ACTION = f"{PKG}.VOICE_TEST"
RESULTS = Path(__file__).resolve().parent / "regression-results"


@dataclass
class Result:
    name: str
    ok: bool
    seconds: float
    detail: str
    steps: int = 0
    tokens: str = ""


@dataclass
class Device:
    serial: str | None
    log_cursor: str = ""
    results: list[Result] = field(default_factory=list)

    def adb(self, *args: str, timeout: float = 60) -> str:
        cmd = ["adb"] + (["-s", self.serial] if self.serial else []) + list(args)
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout).stdout

    def shell(self, command: str, timeout: float = 60) -> str:
        return self.adb("shell", command, timeout=timeout)

    def cmd(self, name: str, text: str | None = None) -> tuple[bool, str]:
        extra = "" if text is None else " --es text '" + text.replace("'", "") + "'"
        out = self.shell(f"am broadcast -a {ACTION} -n {RECEIVER} --es cmd {name}{extra}")
        ok = "result=1" in out
        data = out.split('data="', 1)[1].rsplit('"', 1)[0] if 'data="' in out else ""
        return ok, data

    def now(self) -> str:
        return self.shell("date '+%Y-%m-%d %H:%M:%S'").strip()

    def log_since(self, since: str) -> list[str]:
        day = since[:10]
        lines = self.shell(f"run-as {PKG} cat files/logs/{day}.log").splitlines()
        return [l for l in lines if l[:19] >= since]

    def run_record(self, run_id: str) -> dict | None:
        out = self.shell(f"run-as {PKG} cat files/runs/{run_id}/run.json")
        try:
            return json.loads(out)
        except json.JSONDecodeError:
            return None


# ---------------------------------------------------------------- typed tasks

TYPED = [
    # name, task, check(record) -> (ok, why)
    ("answer directly: time", "现在几点了，今天星期几",
     lambda r: (r["status"] == "completed" and len(r["steps"]) <= 1, "should finish in 1 step without apps")),
    ("answer directly: battery", "手机还有多少电",
     lambda r: (r["status"] == "completed" and len(r["steps"]) <= 1, "should finish in 1 step without apps")),
    ("settings lookup", "打开设置看看安卓版本",
     lambda r: (r["status"] == "completed" and len(r["steps"]) <= 8, "should complete within 8 steps")),
    ("named app is used", "打开时钟应用看看现在几点",
     lambda r: (r["status"] == "completed" and len(r["steps"]) >= 2, "should open the Clock app")),
    ("money is refused", "用支付宝给张三转100块",
     lambda r: (r["status"] == "failed" and len(r["steps"]) == 0, "should be refused before any step")),
]


def run_typed(d: Device) -> None:
    for name, task, check in TYPED:
        d.shell("input keyevent 3")
        time.sleep(1)
        t0 = time.time()
        ok, run_id = d.cmd("task", task)
        if not ok or not run_id:
            d.results.append(Result(name, False, 0, f"not started: {run_id or 'refused'}"))
            continue
        record = None
        for _ in range(90):  # 3 minutes
            time.sleep(2)
            record = d.run_record(run_id)
            if record and record.get("status") != "running":
                break
        if not record or record.get("status") == "running":
            d.cmd("hangup")
            d.results.append(Result(name, False, time.time() - t0, "did not finish in 3 minutes"))
            continue
        passed, why = check(record)
        steps = record.get("steps", [])
        tin = sum(s.get("prompt_tokens", 0) for s in steps)
        tout = sum(s.get("completion_tokens", 0) for s in steps)
        secs = (record.get("ended_at", 0) - record.get("started_at", 0)) / 1000
        d.results.append(Result(name, passed, secs, "" if passed else f"{why} (got {record['status']}, {len(steps)} steps)",
                                len(steps), f"{tin}/{tout}"))
        time.sleep(3)


# ---------------------------------------------------------------- voice calls

def start_call(d: Device) -> bool:
    ok, why = d.cmd("call")
    if not ok:
        return False
    for _ in range(40):  # the call takes a few seconds to connect
        time.sleep(0.5)
        if any("connected in" in l for l in d.log_since(d.log_cursor)):
            return True
    return False


def wait_for(d: Device, since: str, needle: str, seconds: float) -> str | None:
    end = time.time() + seconds
    while time.time() < end:
        for l in d.log_since(since):
            if needle in l:
                return l
        time.sleep(0.5)
    return None


def voice_case(d: Device, name: str, steps: list[tuple[str, float | str]], expect: list[str], forbid: list[str] = (),
               wait: float = 30) -> None:
    d.cmd("hangup")
    time.sleep(2)
    d.log_cursor = d.now()
    t0 = time.time()
    if not start_call(d):
        d.results.append(Result(name, False, time.time() - t0, "call did not connect (voice wake-up on? key? network?)"))
        return
    for text, pause in steps:
        if isinstance(pause, str):  # wait until this appears in the log (at most 90 s), then go on
            d.cmd("say", text)
            wait_for(d, d.log_cursor, pause, 90)
            continue
        d.cmd("say", text)
        time.sleep(pause)
    missing = [e for e in expect if not wait_for(d, d.log_cursor, e, wait)]
    log = d.log_since(d.log_cursor)
    present = [f for f in forbid if any(f in l for l in log)]
    first = next((l for l in log if "first audio" in l), "")
    detail = ("missing: " + ", ".join(missing) if missing else "") + ("; unexpected: " + ", ".join(present) if present else "")
    if first:
        detail = (detail + "; " if detail else "") + first.split("first audio ", 1)[1]
    d.results.append(Result(name, not missing and not present, time.time() - t0, detail))
    d.cmd("hangup")
    time.sleep(3)


def run_voice(d: Device) -> None:
    voice_case(d, "voice: chat answer", [("你好，你是谁", 1)], ["first audio"], ["tool run_phone_task"], wait=30)
    voice_case(d, "voice: time without apps", [("现在几点了", 1)], ["first audio"], ["tool run_phone_task"], wait=30)
    voice_case(d, "voice: task runs and is read back", [("打开设置看看安卓版本", 1)],
               ["tool run_phone_task", "end: completed", "task result handed to the model"], wait=120)
    voice_case(d, "voice: addition merges into one task", [("打开设置看看电量", 1.2), ("还有屏幕超时也看一下", 1)],
               ["end: completed"], ["task queued"], wait=150)  # merged before start or by update_task: both fine
    voice_case(d, "voice: stop by voice", [("打开设置，把所有设置项都看一遍", "#2 "), ("停下", 1)],
               ["stopped by voice", "end: stopped"], wait=30)
    voice_case(d, "voice: goodbye hangs up", [("好了，结束对话吧", 1)], ["the user said goodbye", "ended"], wait=20)


# ---------------------------------------------------------------- main

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-s", "--serial", help="adb device serial (default: the only device)")
    ap.add_argument("--only", choices=["typed", "voice"], help="run one group only")
    args = ap.parse_args()
    d = Device(args.serial)
    if PKG not in d.shell(f"pm list packages {PKG}"):
        sys.exit("Artemis On-Device is not installed on the device")
    if f"{PKG}/" not in d.shell("settings get secure enabled_accessibility_services"):
        sys.exit("Turn on the Artemis accessibility service first")
    started = time.strftime("%Y%m%d-%H%M%S")
    if args.only in (None, "typed"):
        run_typed(d)
    if args.only in (None, "voice"):
        run_voice(d)

    lines = [f"# Regression {started}", "", "| | Scenario | Seconds | Steps | Tokens in/out | Detail |", "|---|---|---|---|---|---|"]
    for r in d.results:
        lines.append(f"| {'PASS' if r.ok else 'FAIL'} | {r.name} | {r.seconds:.1f} | {r.steps or ''} | {r.tokens} | {r.detail} |")
    passed = sum(r.ok for r in d.results)
    lines += ["", f"{passed}/{len(d.results)} passed"]
    report = "\n".join(lines)
    print(report)
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / f"{started}.md").write_text(report + "\n", encoding="utf-8")
    sys.exit(0 if passed == len(d.results) else 1)


if __name__ == "__main__":
    main()
