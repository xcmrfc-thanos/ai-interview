# Copilot Capture and Interview UI Implementation Plan

> **状态：已完成**（采集 UI 已落地，实时链路由 React 版 web/ 承接）。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add explicit/automatic audio-source modes for Copilot capture and refresh the real-time assistant and mock interview interfaces into one calm, responsive workbench style.

**Architecture:** The browser reports a capture mode (`auto`, `local`, `remote`) and a source (`microphone`, `system`, or `mixed`) with every PCM frame. The backend preserves the existing single-stream behavior for compatibility, enriches ASR events with source/speaker metadata, and records source-specific sequence numbers for future dual-stream expansion. Desktop system-audio capture is attempted only in auto mode; if unavailable, the UI falls back to mixed microphone capture with a clear status message. Voice-profile enrollment/diarization is intentionally outside this first slice because it requires persistent user embeddings and a separate verification path.

**Tech Stack:** Flask + Flask-SocketIO, Python service layer, Spring Boot mica-voice gateway, server-rendered Jinja templates, vanilla JavaScript, CSS custom properties.

---

### Task 1: Add source-mode contract and compatibility tests

**Files:**
- Modify: `services/asr_service.py`
- Modify: `services/mica_voice_client.py`
- Modify: `services/copilot_stream.py`
- Test: `tests/services/test_asr_service.py`
- Test: `tests/services/test_mica_voice_client.py`
- Test: `tests/services/test_copilot_stream.py`

- [x] Add `source` and `speaker` fields to `AsrResult`, defaulting to `mixed` and `interviewer` so existing callers remain valid.
- [x] Parse optional `source`, `speaker`, and `confidence` fields from gateway JSON without changing partial/final handling.
- [x] Make `CopilotStream` accept an optional source on start/audio/stop while retaining the current default source for old clients.
- [x] Assert old `AsrResult("text", is_final=True)` calls still pass, and new metadata is emitted on transcript events.

### Task 2: Propagate source and mode through Socket.IO

**Files:**
- Modify: `utils/copilot_socketio.py`
- Modify: `services/utterance_service.py`
- Test: `tests/services/test_copilot_socketio.py`
- Test: `tests/services/test_utterance_service.py`

- [x] Accept `mode`, `source`, and `source_sequence` in `copilot_start` and `copilot_audio_frame`, defaulting to `auto`, `mixed`, and `client_sequence`.
- [x] Validate source names (`microphone`, `system`, `mixed`) and mode names (`auto`, `local`, `remote`); reject malformed values with the existing error event.
- [x] Keep the persisted session sequence monotonic while allowing source-local sequence validation for future two-track capture.
- [x] Pass result speaker metadata into `UtteranceService`; only interviewer results may trigger answer generation.
- [x] Emit `transcript_partial`/`transcript_final` payloads with `source`, `speaker`, and `confidence`.

### Task 3: Implement browser capture modes with safe system-audio fallback

**Files:**
- Modify: `static/js/copilot.js`
- Create: `static/js/pcm-stream-recorder.js`
- Modify: `templates/applicant/_copilot_panel.html`
- Test: `tests/static/test_copilot_ui.py`

- [x] Add accessible radio controls for `自动识别`, `本机`, and `对方`, with helper text describing whether the current capture is separate or mixed.
- [x] Add capability detection using `getDisplayMedia({audio:true})` only for desktop auto mode; never request screen video when the browser cannot provide audio-only capture.
- [x] Add a small PCM stream recorder that accepts an existing `MediaStream`, resamples to 16 kHz, emits 1600-sample int16 frames, and stops all owned tracks safely.
- [x] Use microphone-only capture for local mode, system-only capture for remote mode, and microphone capture with a mixed-mode warning when system audio is unavailable.
- [x] Include `mode`, `source`, and `source_sequence` in every Socket.IO frame; preserve pending-frame retry behavior per source.
- [x] Show capture state, fallback explanation, and selected mode in the status bar; support reduced motion and keyboard navigation.

### Task 4: Refresh both interview workspaces

**Files:**
- Modify: `templates/applicant/_copilot_panel.html`
- Modify: `templates/applicant/_mock_panel.html`
- Modify: `static/css/copilot.css`
- Modify: `static/css/pages/mock-interview.css`
- Modify: `static/css/pages/interview-workspace.css`

- [x] Replace repeated all-over gradients and heavy shadows with a shared quiet-workbench surface, clearer section labels, and one accent color for active states.
- [x] Make the capture mode block visually prominent without competing with transcript/answer content.
- [x] Improve empty, loading, error, and completed states with concise helper copy and stable layout heights.
- [x] Align mock interview question, answer editor, progress, and feedback into a consistent hierarchy; preserve server-rendered IDs and existing JS behavior.
- [x] Verify responsive layouts at desktop, tablet, and narrow mobile widths; preserve minimum 44px controls and visible focus rings.

### Task 5: Verification and delivery notes

**Files:**
- Modify: `docs/superpowers/plans/2026-08-22-copilot-capture-ui.md`

- [x] Run targeted Python tests for ASR, CopilotStream, Socket.IO, and utterance decisions.
- [x] Run static UI tests and any available JavaScript syntax checks.
- [x] Run a Maven gateway test after configuration changes if Java sources are touched.
- [x] Review the diff for source/mode compatibility, mobile fallback messaging, and no accidental raw-audio persistence.

