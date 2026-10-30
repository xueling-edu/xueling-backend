package com.hope.xueling.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SiliconFlowConfigTest {
    private static final String MODEL = "XingChenAGI/Xing4.0-29B";

    private void set(LLMConfig config, String fieldName, String value) throws Exception {
        var field = LLMConfig.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(config, value);
    }

    @Test
    void sendsChatCompletionToSiliconFlowCompatiblePath() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"id\":\"test\",\"object\":\"chat.completion\",\"created\":1," +
                    "\"model\":\"" + MODEL + "\",\"choices\":[{\"index\":0," +
                    "\"message\":{\"role\":\"assistant\",\"content\":\"Ready\"},\"finish_reason\":\"stop\"}]," +
                    "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            LLMConfig config = new LLMConfig();
            set(config, "siliconFlowApiKey", "test-secret");
            set(config, "siliconFlowUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            set(config, "siliconFlowModel", MODEL);
            var client = new AIConfig().siliconFlowChatClient(config.siliconFlowChatModel());

            assertEquals("Ready", client.prompt().user("Say ready").call().content());
            assertEquals("/v1/chat/completions", path.get());
            assertEquals("Bearer test-secret", authorization.get());
            JsonNode request = new ObjectMapper().readTree(body.get());
            assertEquals(MODEL, request.get("model").asText());
        } finally {
            server.stop(0);
        }
    }
}
