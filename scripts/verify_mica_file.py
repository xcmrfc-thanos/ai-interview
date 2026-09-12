"""Stream a PCM16LE file to mica-voice and print timing evidence."""

import argparse
import json
import sys
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from services.mica_voice_client import MicaVoiceClient


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("pcm_file", type=Path)
    parser.add_argument(
        "--url",
        default="ws://127.0.0.1:18080/mica/voice/ws/online-asr",
    )
    parser.add_argument("--chunk-ms", type=int, default=100)
    parser.add_argument("--final-timeout", type=float, default=15)
    return parser.parse_args()


def main():
    args = parse_args()
    frame_bytes = 16000 * 2 * args.chunk_ms // 1000
    started_at = time.perf_counter()
    events = []

    def record(result):
        elapsed_ms = round((time.perf_counter() - started_at) * 1000)
        events.append(
            {
                "type": "final" if result.is_final else "partial",
                "elapsed_ms": elapsed_ms,
                "text": result.text,
            }
        )

    client = MicaVoiceClient(
        args.url,
        final_timeout=args.final_timeout,
        connect_timeout=10,
    )
    client.set_result_callback(record)
    try:
        client.start()
        with args.pcm_file.open("rb") as pcm_file:
            while chunk := pcm_file.read(frame_bytes):
                client.send_audio(chunk)
                time.sleep(args.chunk_ms / 1000)
        client.finish()
    finally:
        client.close()

    non_empty_partials = [event for event in events if event["type"] == "partial" and event["text"]]
    final_events = [event for event in events if event["type"] == "final"]
    report = {
        "pcm_file": str(args.pcm_file.resolve()),
        "chunk_ms": args.chunk_ms,
        "partial_count": len(non_empty_partials),
        "first_partial_ms": non_empty_partials[0]["elapsed_ms"] if non_empty_partials else None,
        "final_ms": final_events[-1]["elapsed_ms"] if final_events else None,
        "final_text": final_events[-1]["text"] if final_events else "",
        "events": events,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
