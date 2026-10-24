package com.smartfirehub.dbexecutor.live;

import com.smartfirehub.dbexecutor.web.ExecuteController;
import com.smartfirehub.dbexecutor.web.dto.QueryRequest;
import com.smartfirehub.dbexecutor.web.dto.QueryResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 실서버 테스트가 컨트롤러의 {@code /execute/query} 분석 조회 갈래를 직접 부르는 도우미. 슬롯 스트리밍(WD-78)이 같은 핸들러에 들어오면서 핸들러가
 * 서블릿 요청·응답을 받게 되어, 분석 조회(JSON 응답)만 쓰는 테스트는 이 도우미로 {@link QueryResponse} 를 받는다.
 */
final class LiveQuery {

  private LiveQuery() {}

  /** 분석 조회(스크립트 토큰·스트리밍 없음)를 실행하고 JSON 응답 객체를 돌려준다. */
  static QueryResponse run(ExecuteController controller, QueryRequest request) {
    try {
      return (QueryResponse)
          controller.query(request, new MockHttpServletRequest(), new MockHttpServletResponse());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
