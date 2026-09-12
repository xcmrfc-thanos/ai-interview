package com.aiinterview.server.web;

import com.aiinterview.server.AppConfig;
import com.aiinterview.server.asr.AsrProvider;
import com.aiinterview.server.asr.AsrSession;
import com.aiinterview.server.svc.Llm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.smartboot.feat.ai.chat.ChatModel;
import tech.smartboot.feat.core.server.WebSocketRequest;
import tech.smartboot.feat.core.server.WebSocketResponse;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CopilotWsControlTest {

    private final WebSocketRequest request = mock(WebSocketRequest.class);
    private final WebSocketResponse response = mock(WebSocketResponse.class);
    private final AsrProvider asrProvider = mock(AsrProvider.class);
    private final AppConfig config = AppConfig.load();
    private final Llm llm = new Llm(mock(ChatModel.class), 5);
    private CopilotWs copilotWs;

    @BeforeEach
    void setUp() {
        CopilotWs.init(config);
        when(asrProvider.openSession(any())).thenReturn(mock(AsrSession.class));
        copilotWs = new CopilotWs(asrProvider, config, llm, 1L);
    }

    @Test
    void sendsReadyOnHandshake() {
        copilotWs.onHandShake(request, response);
        verify(response).sendTextMessage(contains("\"event\":\"ready\""));
    }

    @Test
    void invalidControlJsonEmitsError() {
        copilotWs.handleTextMessage(request, response, "not-json");
        verify(response).sendTextMessage(contains("\"event\":\"error\""));
    }

    @Test
    void unknownEventEmitsError() {
        copilotWs.handleTextMessage(request, response, "{\"event\":\"bogus\"}");
        verify(response).sendTextMessage(contains("未知事件"));
    }

    @Test
    void setSpeakerRejectsMismatchedSession() throws Exception {
        setSessionId(100L);
        copilotWs.handleTextMessage(request, response,
            "{\"event\":\"copilot_set_speaker\",\"session_id\":200,\"speaker\":\"interviewer\"}");
        verify(response).sendTextMessage(contains("会话不匹配"));
    }

    /** 通过反射注入 sessionId，用于无 DB 的控制帧契约测试。 */
    private void setSessionId(long sessionId) throws Exception {
        Field field = CopilotWs.class.getDeclaredField("sessionId");
        field.setAccessible(true);
        field.set(copilotWs, sessionId);
    }
}
