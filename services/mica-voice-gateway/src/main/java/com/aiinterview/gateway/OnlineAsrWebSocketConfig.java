package com.aiinterview.gateway;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class OnlineAsrWebSocketConfig implements WebSocketConfigurer {

    private final OnlineAsrWebSocketHandler handler;

    public OnlineAsrWebSocketConfig(OnlineAsrWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/mica/voice/ws/online-asr").setAllowedOrigins("*");
    }
}
