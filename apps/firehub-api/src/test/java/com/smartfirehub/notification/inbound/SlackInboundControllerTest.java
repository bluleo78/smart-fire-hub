package com.smartfirehub.notification.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SlackInboundControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockitoBean private SlackInboundService slackInboundService;

  private static final String INTERNAL_TOKEN = "test-internal-token";

  @Test
  void inbound_유효한_Internal_토큰_dispatch_호출() throws Exception {
    var body =
        Map.of(
            "teamId",
            "T123",
            "event",
            Map.of(
                "type", "message", "channel", "C123", "user", "U123", "text", "hi", "ts", "1.0"));

    mockMvc
        .perform(
            post("/api/v1/channels/slack/inbound")
                .header("Authorization", "Internal " + INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isOk());

    verify(slackInboundService).dispatch(anyString(), any());
  }

  @Test
  void inbound_Internal_토큰_없음_401() throws Exception {
    var body = Map.of("teamId", "T123", "event", Map.of("type", "message"));

    mockMvc
        .perform(
            post("/api/v1/channels/slack/inbound")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isUnauthorized());
  }
}
