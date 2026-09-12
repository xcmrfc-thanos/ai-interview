package com.aiinterview.server.svc;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnswerStreamParserTest {

    @Test
    void parsesNdjsonLineWithPrefixText() {
        JSONObject event = AnswerStreamParser.parseLine("prefix {\"type\":\"answer_delta\",\"text\":\"你好\"} suffix");
        assertNotNull(event);
        assertEquals("answer_delta", event.getString("type"));
        assertEquals("你好", event.getString("text"));
    }

    @Test
    void returnsNullForInvalidLine() {
        assertNull(AnswerStreamParser.parseLine(""));
        assertNull(AnswerStreamParser.parseLine("not json"));
    }
}
