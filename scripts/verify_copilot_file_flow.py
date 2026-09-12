"""Verify the live Copilot flow with a PCM16LE file and real services."""

import argparse
import json
import sys
import threading
import time
from pathlib import Path

import requests
import socketio

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("pcm_file", type=Path)
    parser.add_argument("--base-url", default="http://127.0.0.1:5000")
    parser.add_argument("--plan-id", type=int, default=1)
    parser.add_argument("--email", default="e2e.applicant@example.com")
    parser.add_argument("--password", default="E2e-Interview-2026")
    parser.add_argument("--chunk-ms", type=int, default=100)
    parser.add_argument("--answer-timeout", type=float, default=30)
    return parser.parse_args()


def wait_for(event, timeout, label, errors):
    if not event.wait(timeout):
        detail = f": {errors[-1]}" if errors else ""
        raise RuntimeError(f"等待 {label} 超时{detail}")


def main():
    args = parse_args()
    http = requests.Session()
    login = http.post(
        f"{args.base_url}/api/login",
        data={"email": args.email, "password": args.password},
        timeout=15,
    )
    login.raise_for_status()
    created = http.post(
        f"{args.base_url}/api/copilot/sessions",
        json={"plan_id": args.plan_id},
        timeout=15,
    )
    created.raise_for_status()
    business_session = created.json()["session"]
    session_id = business_session["session_id"]
    next_sequence = business_session["last_client_sequence"] + 1

    client = socketio.Client(http_session=http, reconnection=False, logger=False)
    running = threading.Event()
    final_received = threading.Event()
    answer_completed = threading.Event()
    acked = set()
    ack_condition = threading.Condition()
    events = []
    errors = []

    def record(name, payload):
        events.append({
            "name": name,
            "elapsed_ms": round((time.perf_counter() - started_at) * 1000),
            "payload": payload,
        })

    @client.on("session_state")
    def on_session_state(payload):
        record("session_state", payload)
        if payload.get("state") == "running":
            running.set()

    @client.on("audio_ack")
    def on_audio_ack(payload):
        with ack_condition:
            acked.add(payload["client_sequence"])
            ack_condition.notify_all()

    @client.on("transcript_partial")
    def on_partial(payload):
        if payload.get("text"):
            record("transcript_partial", payload)

    @client.on("transcript_final")
    def on_final(payload):
        record("transcript_final", payload)
        final_received.set()

    @client.on("answer_started")
    def on_answer_started(payload):
        record("answer_started", payload)

    @client.on("answer_delta")
    def on_answer_delta(payload):
        record("answer_delta", payload)

    @client.on("answer_completed")
    def on_answer_completed(payload):
        record("answer_completed", payload)
        answer_completed.set()

    @client.on("error")
    def on_error(payload):
        errors.append(payload.get("message") or str(payload))
        record("error", payload)

    started_at = time.perf_counter()
    try:
        client.connect(args.base_url, transports=["polling", "websocket"], wait_timeout=15)
        client.emit("copilot_start", {"session_id": session_id})
        wait_for(running, 15, "Copilot 启动", errors)

        frame_bytes = 16000 * 2 * args.chunk_ms // 1000
        sent_sequences = []
        with args.pcm_file.open("rb") as pcm_file:
            while chunk := pcm_file.read(frame_bytes):
                sequence = next_sequence + len(sent_sequences)
                sent_sequences.append(sequence)
                client.emit("copilot_audio_frame", {
                    "session_id": session_id,
                    "client_sequence": sequence,
                    "pcm": chunk,
                })
                time.sleep(args.chunk_ms / 1000)
        with ack_condition:
            ack_condition.wait_for(
                lambda: all(sequence in acked for sequence in sent_sequences),
                timeout=15,
            )
        if not all(sequence in acked for sequence in sent_sequences):
            raise RuntimeError("部分音频帧未收到 ACK")

        client.emit("copilot_end", {"session_id": session_id})
        wait_for(final_received, 20, "ASR final", errors)
        wait_for(answer_completed, args.answer_timeout, "LLM 完成", errors)
    finally:
        if client.connected:
            client.disconnect()

    restored = http.get(
        f"{args.base_url}/api/copilot/sessions/{session_id}", timeout=15
    )
    restored.raise_for_status()
    restored_session = restored.json()["session"]
    turns = restored_session.get("turns") or []
    if not turns or turns[-1]["status"] not in {"completed", "partial"}:
        raise RuntimeError("回答完成事件之后未找到已持久化 turn")
    review_response = http.post(
        f"{args.base_url}/api/reviews/generate",
        json={"source_type": "copilot", "source_id": session_id},
        timeout=15,
    )
    review_response.raise_for_status()

    event_names = [event["name"] for event in events]
    required_order = ["transcript_final", "answer_started", "answer_delta", "answer_completed"]
    positions = [event_names.index(name) for name in required_order]
    if positions != sorted(positions):
        raise RuntimeError(f"事件顺序错误: {event_names}")
    report = {
        "session_id": session_id,
        "sent_frames": len(sent_sequences),
        "acked_frames": len(acked),
        "event_names": event_names,
        "first_partial_ms": next(
            (item["elapsed_ms"] for item in events if item["name"] == "transcript_partial"),
            None,
        ),
        "final_ms": next(item["elapsed_ms"] for item in events if item["name"] == "transcript_final"),
        "answer_started_ms": next(item["elapsed_ms"] for item in events if item["name"] == "answer_started"),
        "answer_completed_ms": next(item["elapsed_ms"] for item in events if item["name"] == "answer_completed"),
        "transcript": turns[-1]["transcript"],
        "turn_status": turns[-1]["status"],
        "answer_points": turns[-1]["answer_points"],
        "reference_answer": turns[-1]["reference_answer"],
        "review_id": review_response.json()["review"]["review_id"],
        "restored_status": restored_session["status"],
        "last_client_sequence": restored_session["last_client_sequence"],
        "errors": errors,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
