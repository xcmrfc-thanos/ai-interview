"""真实 ASR + LLM 的 Copilot 全链路验收（契约 docs/api-contract.md §5–§6）。

对任一后端（Python Flask 裸 WS / Java interview-server）驱动完整链路：
注册→登录→上传简历→创建计划→创建 Copilot 会话→WS ready→copilot_start→
v2 音频帧（真实语音 PCM，按真实时长发送）→transcript→utterance_completed→
answer_*（真实 LLM）→copilot_end→ending→ended。

WS 收发全部在主线程单循环内完成（simple_websocket 非线程安全）。

用法：
  py -3.12 scripts/verify_copilot_ws_flow.py --base-url http://127.0.0.1:5000 \
      --pcm temp/e2e-copilot-question.wav
"""

import argparse
import json
import secrets
import struct
import subprocess
import sys
import time
from pathlib import Path

import requests

try:
    from simple_websocket import Client as WsClient
    from simple_websocket import ConnectionClosed as WsClosed
except ImportError:  # 兜底：websocket-client
    WsClient = None

    class WsClosed(Exception):
        pass

PROJECT_ROOT = Path(__file__).resolve().parents[1]

SOURCE_CODES = {"mixed": 0, "microphone": 1, "system": 2}
FRAME_BYTES = 3200  # 100ms @ 16kHz s16le mono


class WsTimeout(Exception):
    pass


class WsTransport:
    """simple_websocket.Client 优先（与 flask-sock 服务端同源）；否则退回 websocket-client。"""

    def __init__(self, url: str, cookie: str):
        self.using_simple = WsClient is not None
        if self.using_simple:
            self.ws = WsClient(url, headers={"Cookie": cookie})
        else:
            from websocket import create_connection

            self.ws = create_connection(url, header={"Cookie": cookie}, timeout=10)

    def send_text(self, text: str):
        self.ws.send(text)

    def send_binary(self, data: bytes):
        self.ws.send(data)

    def recv(self, timeout: float):
        """单线程收帧；超时抛 WsTimeout，对端关闭抛 WsClosed。"""
        if self.using_simple:
            try:
                return self.ws.receive(timeout=timeout)
            except TimeoutError:
                raise WsTimeout() from None
        self.ws.settimeout(timeout)
        try:
            return self.ws.recv()
        except Exception as exc:
            name = type(exc).__name__
            if "Timeout" in name or "timed out" in str(exc):
                raise WsTimeout() from None
            raise WsClosed(str(exc)) from None

    def close(self):
        try:
            self.ws.close()
        except Exception:
            pass


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:5000")
    parser.add_argument("--pcm", type=Path, default=PROJECT_ROOT / "temp/e2e-copilot-question.wav")
    parser.add_argument("--email", default="")
    parser.add_argument("--password", default="")
    parser.add_argument("--chunk-ms", type=int, default=100)
    parser.add_argument("--utterance-timeout", type=float, default=45)
    parser.add_argument("--answer-timeout", type=float, default=120)
    parser.add_argument("--end-timeout", type=float, default=20)
    return parser.parse_args()


def resample_to_16k_pcm(src: Path) -> Path:
    out = Path("temp") / f"e2e-ws-{secrets.token_hex(4)}.pcm"
    out.parent.mkdir(exist_ok=True)
    subprocess.run(
        ["ffmpeg", "-y", "-v", "error", "-i", str(src),
         "-ar", "16000", "-ac", "1", "-f", "s16le", str(out)],
        check=True,
    )
    return out


def main():
    args = parse_args()
    base_url = args.base_url.rstrip("/")
    run_tag = secrets.token_hex(4)
    email = args.email or f"e2e-ws-{run_tag}@example.com"
    password = args.password or f"Pw-{secrets.token_hex(8)}"

    pcm_path = resample_to_16k_pcm(args.pcm)
    pcm = pcm_path.read_bytes()
    pcm_path.unlink(missing_ok=True)
    # 尾部追加 2s 静音：触发流式 ASR 的端点检测产出 final
    pcm = pcm + b"\x00" * (3200 * 20)
    total_frames = (len(pcm) + FRAME_BYTES - 1) // FRAME_BYTES
    print(f"[e2e] 音频: {len(pcm)} 字节 ≈ {len(pcm) / 32000:.1f}s（含尾静音）, {total_frames} 帧", flush=True)

    http = requests.Session()
    resp = http.post(
        f"{base_url}/api/register",
        json={"email": email, "password": password, "confirm_password": password,
              "role": "applicant", "full_name": "E2E 验收"},
        timeout=15,
    )
    if resp.status_code not in (200, 201, 409):
        raise RuntimeError(f"注册失败: {resp.status_code} {resp.text[:200]}")
    resp = http.post(f"{base_url}/api/login", json={"email": email, "password": password}, timeout=15)
    resp.raise_for_status()
    print(f"[e2e] 登录: {resp.json()}", flush=True)

    resume_txt = (
        "E2E 验收简历\n"
        "姓名：E2E 验收\n"
        "技能：Python、Flask、MySQL、Redis。\n"
        "经历：2020-2024 在某互联网公司担任后端工程师，负责订单系统与支付网关，"
        "主导过服务化改造，QPS 从 2000 提升到 12000。\n"
    )
    resp = http.post(
        f"{base_url}/api/resumes",
        files={"file": (f"e2e-{run_tag}.txt", resume_txt.encode("utf-8"), "text/plain")},
        data={"storage_name": f"e2e-{run_tag}.txt"},
        timeout=30,
    )
    if resp.status_code >= 400:
        raise RuntimeError(f"简历上传失败: {resp.status_code} {resp.text[:200]}")
    resume_id = resp.json()["resume_id"]
    print(f"[e2e] 简历: resume_id={resume_id}", flush=True)

    resp = http.post(
        f"{base_url}/api/interview-plans",
        json={
            "company_name": "E2E 验收公司",
            "position_name": "后端工程师",
            "job_description": "负责高并发后端服务设计与实现，熟悉 Python/MySQL/Redis，有支付系统经验者优先。",
            "resume_id": resume_id,
        },
        timeout=30,
    )
    resp.raise_for_status()
    plan_id = resp.json()["plan"]["plan_id"]
    print(f"[e2e] 计划: plan_id={plan_id}", flush=True)

    resp = http.post(f"{base_url}/api/copilot/sessions", json={"plan_id": plan_id}, timeout=15)
    if resp.status_code >= 400:
        raise RuntimeError(f"创建会话失败: {resp.status_code} {resp.text[:200]}")
    session_id = resp.json()["session"]["session_id"]
    print(f"[e2e] 会话: session_id={session_id}", flush=True)

    cookies = "; ".join(f"{k}={v}" for k, v in http.cookies.get_dict().items())
    ws_url = base_url.replace("http://", "ws://").replace("https://", "wss://") + "/ws/copilot"
    ws = WsTransport(ws_url, cookies)

    events = []

    def drain(seconds: float):
        """在 seconds 内收帧并入队事件（receive 超时返回 None）。"""
        deadline = time.monotonic() + seconds
        while True:
            remain = deadline - time.monotonic()
            if remain <= 0:
                return
            try:
                raw = ws.recv(timeout=remain)
            except WsTimeout:
                return
            if raw is None:
                return
            if isinstance(raw, bytes):
                continue
            try:
                events.append(json.loads(raw))
            except ValueError:
                continue

    def find_event(name):
        return next((e for e in events if e.get("event") == name), None)

    def wait_for(name, timeout):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            found = find_event(name)
            if found is not None:
                return found
            drain(0.1)
        raise RuntimeError(
            f"等待 {name} 超时；已收事件: {[e.get('event') for e in events][-25:]}"
        )

    ready = wait_for("ready", 10)
    print("[e2e] WS ready", flush=True)

    ws.send_text(json.dumps({
        "event": "copilot_start",
        "session_id": session_id,
        "mode": "auto",
        "speaker": None,
        "sources": ["system"],
    }))
    started = wait_for_started = None
    deadline = time.monotonic() + 15
    while started is None and time.monotonic() < deadline:
        drain(0.1)
        started = find_event("started") or find_event("reconnected")
    if started is None:
        raise RuntimeError("等待 started/reconnected 超时")
    assert started.get("audio_protocol") == 2, started
    print(f"[e2e] {started['event']}（audio_protocol={started.get('audio_protocol')}）", flush=True)

    # 按真实时长交错发送 v2 帧（源码 2 = system，sequence 从 0 递增）并排空事件
    sent = 0
    next_send = time.monotonic()
    interval = args.chunk_ms / 1000
    while sent < total_frames:
        now = time.monotonic()
        if now >= next_send:
            chunk = pcm[sent * FRAME_BYTES:(sent + 1) * FRAME_BYTES]
            chunk = chunk.ljust(FRAME_BYTES, b"\x00")
            header = b"AI" + bytes([2, SOURCE_CODES["system"]]) + struct.pack("<I", sent)
            ws.send_binary(header + chunk)
            sent += 1
            next_send = now + interval
        drain(min(0.05, max(0.0, next_send - time.monotonic())))
    print(f"[e2e] 已发送 {sent} 帧 system 音频", flush=True)

    # 等待话轮完成 → 真实 LLM 回答完成
    answer_deadline = time.monotonic() + args.answer_timeout
    while time.monotonic() < answer_deadline:
        drain(0.2)
        if find_event("answer_completed") is not None:
            break
    utterance = find_event("utterance_completed")
    answer = find_event("answer_completed")
    if answer is None:
        partials = [e.get("text", "") for e in events if e.get("event") == "transcript_partial"]
        raise RuntimeError(
            f"等待 answer_completed 超时；事件序列: {[e.get('event') for e in events][-25:]}; "
            f"partial 文本: {partials[-5:]}"
        )
    if utterance is None:
        print("[e2e] 警告：未见显式 utterance_completed（部分实现由 final 直接触发回答）")
    else:
        print(
            f"[e2e] 话轮完成: {str(utterance.get('text', ''))[:60]} "
            f"(speaker={utterance.get('speaker')}, reason={utterance.get('reason')})",
            flush=True,
        )

    transcript_finals = [e.get("text", "") for e in events if e.get("event") == "transcript_final"]
    print(f"[e2e] ASR final 片段: {transcript_finals}", flush=True)
    deltas = sum(1 for e in events if e.get("event") == "answer_delta")
    queued_ev = find_event("answer_queued")
    started_ev = find_event("answer_started")
    print(
        f"[e2e] 回答完成: turn_id={answer.get('turn_id')}, 要点 {len(answer.get('answer_points') or [])} 条, "
        f"delta {deltas} 次, error_code={answer.get('error_code')}, "
        f"answer_queued={'有' if queued_ev else '无'}, answer_started={'有' if started_ev else '无'}",
        flush=True,
    )
    assert answer.get("answer_points"), "answer_completed 缺少回答要点"
    assert answer.get("reference_answer"), "answer_completed 缺少参考回答"

    ws.send_text(json.dumps({"event": "copilot_end", "session_id": session_id}))
    end_deadline = time.monotonic() + args.end_timeout
    while time.monotonic() < end_deadline:
        drain(0.2)
        if find_event("ended") is not None:
            break
    if find_event("ended") is None:
        raise RuntimeError("等待 ended 超时")
    print("[e2e] 会话已结束（ending→ended 排水完成）", flush=True)

    ws.close()
    summary = {
        "backend": base_url,
        "session_id": session_id,
        "plan_id": plan_id,
        "audio_frames": sent,
        "transcript_finals": transcript_finals,
        "utterance": {k: utterance.get(k) for k in ("text", "speaker", "reason")} if utterance else None,
        "answer": {
            "turn_id": answer.get("turn_id"),
            "points": len(answer.get("answer_points") or []),
            "has_reference": bool(answer.get("reference_answer")),
            "error_code": answer.get("error_code"),
            "deltas": deltas,
            "answer_queued": bool(queued_ev),
            "answer_started": bool(started_ev),
        },
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)
    print("[e2e] PASS", flush=True)


if __name__ == "__main__":
    code = 0
    try:
        main()
    except WsClosed as exc:
        print(f"[e2e] FAIL: 连接被对端关闭: {exc}", file=sys.stderr)
        code = 1
    except Exception as exc:
        print(f"[e2e] FAIL: {exc}", file=sys.stderr)
        code = 1
    finally:
        sys.stdout.flush()
        sys.stderr.flush()
        # simple_websocket 的读线程是非 daemon，可能在服务端保持连接时阻塞退出；
        # 输出已 flush，直接结束进程
        import os

        os._exit(code)
