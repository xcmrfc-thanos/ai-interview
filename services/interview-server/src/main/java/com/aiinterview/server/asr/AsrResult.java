package com.aiinterview.server.asr;

/** ASR 识别结果（partial / final / error）。 */
public final class AsrResult {

    private final String type;
    private final String text;

    public AsrResult(String type, String text) {
        this.type = type;
        this.text = text;
    }

    public String type() {
        return type;
    }

    public String text() {
        return text;
    }
}
