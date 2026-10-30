package com.smartfirehub.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 비동기 응답(StreamingResponseBody 등)을 MockMvc 로 끝까지 받는 테스트 도우미(WD-443).
 *
 * <p>첫 perform 만 하면 본문은 executor 스레드에서 계속 쓰여, 테스트가 끝난 뒤에도 응답을 고치거나 지운 임시 파일을 읽는다. asyncStarted 를
 * 확인하고 asyncDispatch 로 전송 완료까지 기다린 결과를 돌려준다.
 */
public final class AsyncMvc {

  private AsyncMvc() {}

  /** 요청을 보내 비동기 시작을 확인하고, 전송이 끝난 응답에 대한 ResultActions 를 돌려준다. */
  public static ResultActions completeAsync(MockMvc mockMvc, RequestBuilder request)
      throws Exception {
    var started = mockMvc.perform(request).andExpect(request().asyncStarted()).andReturn();
    return mockMvc.perform(asyncDispatch(started));
  }
}
