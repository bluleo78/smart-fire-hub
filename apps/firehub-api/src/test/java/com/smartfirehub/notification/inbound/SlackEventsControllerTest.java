package com.smartfirehub.notification.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfirehub.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SlackEventsController 통합 테스트.
 *
 * <p>SlackSignatureVerifier, SlackInboundService는 Mock 처리하여
 * 컨트롤러 라우팅/필터링 로직만 검증한다.
 */
@AutoConfigureMockMvc
class SlackEventsControllerTest extends IntegrationTestBase {

    private static final String EVENTS_URL = "/api/v1/channels/slack/events";

    /** 헤더 기본값 (테스트용 더미 값) */
    private static final String SIG = "v0=dummy";
    private static final String TS = "1713400000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SlackSignatureVerifier verifier;

    @MockitoBean
    private SlackInboundService inboundService;

    // -------------------------------------------------------------------
    // url_verification
    // -------------------------------------------------------------------

    /**
     * url_verification 요청 시 challenge 값을 200으로 반환해야 한다.
     * Slack 앱 URL 등록 시 서명 검증 없이 즉시 반환 필수.
     */
    @Test
    void urlVerification_returnsChallenge() throws Exception {
        String body = """
                {"type":"url_verification","challenge":"test-challenge-xyz"}
                """;

        mockMvc.perform(post(EVENTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Slack-Signature", SIG)
                        .header("X-Slack-Request-Timestamp", TS)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("test-challenge-xyz"));

        // url_verification은 서명 검증 및 dispatch 없이 바로 응답
        verify(verifier, never()).verify(any(), any(), any(), any());
        verify(inboundService, never()).dispatch(any(), any());
    }

    // -------------------------------------------------------------------
    // 서명 검증 실패
    // -------------------------------------------------------------------

    /**
     * 서명 검증 실패 시 401을 반환하고 dispatch를 호출하지 않는다.
     */
    @Test
    void signatureRejected_returns401() throws Exception {
        when(verifier.verify(any(), any(), any(), any())).thenReturn(false);

        String body = """
                {"type":"event_callback","team_id":"T123","event":{"type":"message","text":"hi"}}
                """;

        mockMvc.perform(post(EVENTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Slack-Signature", SIG)
                        .header("X-Slack-Request-Timestamp", TS)
                        .content(body))
                .andExpect(status().isUnauthorized());

        verify(inboundService, never()).dispatch(any(), any());
    }

    // -------------------------------------------------------------------
    // message event (DM)
    // -------------------------------------------------------------------

    /**
     * event_callback + event.type=message (subtype 없음) 시 dispatch 호출 후 200 반환.
     */
    @Test
    void messageImEvent_dispatchedAndReturns200() throws Exception {
        when(verifier.verify(eq("T123"), any(), any(), any())).thenReturn(true);

        String body = """
                {
                  "type": "event_callback",
                  "team_id": "T123",
                  "event": {
                    "type": "message",
                    "text": "hello",
                    "user": "U456"
                  }
                }
                """;

        mockMvc.perform(post(EVENTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Slack-Signature", SIG)
                        .header("X-Slack-Request-Timestamp", TS)
                        .content(body))
                .andExpect(status().isOk());

        verify(inboundService).dispatch(eq("T123"), any(JsonNode.class));
    }

    // -------------------------------------------------------------------
    // app_mention event
    // -------------------------------------------------------------------

    /**
     * event.type=app_mention 시 dispatch 호출.
     */
    @Test
    void appMentionEvent_dispatched() throws Exception {
        when(verifier.verify(eq("T999"), any(), any(), any())).thenReturn(true);

        String body = """
                {
                  "type": "event_callback",
                  "team_id": "T999",
                  "event": {
                    "type": "app_mention",
                    "text": "<@BOT> hello",
                    "user": "U111"
                  }
                }
                """;

        mockMvc.perform(post(EVENTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Slack-Signature", SIG)
                        .header("X-Slack-Request-Timestamp", TS)
                        .content(body))
                .andExpect(status().isOk());

        verify(inboundService).dispatch(eq("T999"), any(JsonNode.class));
    }

    // -------------------------------------------------------------------
    // bot_message subtype 무시
    // -------------------------------------------------------------------

    /**
     * event.subtype 존재 시 (bot_message 등) dispatch를 호출하지 않는다.
     * bot 메시지 루프 방지.
     */
    @Test
    void botMessageSubtype_ignored() throws Exception {
        when(verifier.verify(eq("T123"), any(), any(), any())).thenReturn(true);

        String body = """
                {
                  "type": "event_callback",
                  "team_id": "T123",
                  "event": {
                    "type": "message",
                    "subtype": "bot_message",
                    "text": "I am a bot",
                    "bot_id": "B123"
                  }
                }
                """;

        mockMvc.perform(post(EVENTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Slack-Signature", SIG)
                        .header("X-Slack-Request-Timestamp", TS)
                        .content(body))
                .andExpect(status().isOk());

        verify(inboundService, never()).dispatch(any(), any());
    }

    // -------------------------------------------------------------------
    // 필수 헤더 누락
    // -------------------------------------------------------------------

    /**
     * X-Slack-Signature 헤더 누락 시 500 반환.
     * GlobalExceptionHandler가 MissingRequestHeaderException을 처리하지 않으므로
     * Spring 기본 동작으로 500이 반환된다.
     */
    @Test
    void missingSignatureHeader_returnsError() throws Exception {
        String body = """
                {"type":"event_callback","team_id":"T123","event":{"type":"message","text":"hi"}}
                """;

        // X-Slack-Signature 누락 시 GlobalExceptionHandler가 MissingRequestHeaderException을
        // 처리하지 않으므로 Spring이 500을 반환한다. 어떤 에러 코드든 성공 응답이 아님을 검증.
        mockMvc.perform(post(EVENTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Slack-Request-Timestamp", TS)
                        .content(body))
                .andExpect(status().isInternalServerError());
    }
}
