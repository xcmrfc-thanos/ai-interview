package com.aiinterview.server.svc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/**
 * 从 LLM NDJSON 流式输出中解析单行事件。
 */
public final class AnswerStreamParser {

    private AnswerStreamParser() {
    }

    /**
     * 解析一行文本中的 JSON 事件；无效行返回 null。
     */
    public static JSONObject parseLine(String line) {
        if (line == null) {
            return null;
        }
        String content = line.trim();
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end < start) {
            return null;
        }
        try {
            JSONObject value = JSON.parseObject(content.substring(start, end + 1));
            if (value != null && value.getString("type") != null) {
                return value;
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }
}
