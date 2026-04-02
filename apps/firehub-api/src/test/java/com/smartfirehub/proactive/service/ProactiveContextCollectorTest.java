package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class ProactiveContextCollectorTest extends IntegrationTestBase {

  @Autowired private ProactiveContextCollector contextCollector;
  @MockitoBean private ProactiveJobExecutionRepository executionRepository;

  @Test
  void collectContext_includes_previousExecutions_when_jobId_provided() {
    var execution =
        new ProactiveJobExecutionResponse(
            1L,
            10L,
            "COMPLETED",
            LocalDateTime.now().minusDays(1),
            LocalDateTime.now().minusDays(1),
            null,
            Map.of(
                "title",
                "Test",
                "sections",
                List.of(Map.of("key", "s1", "label", "요약", "content", "테스트 내용"))),
            List.of(),
            LocalDateTime.now().minusDays(1));
    when(executionRepository.findByJobId(anyLong(), anyInt(), anyInt()))
        .thenReturn(List.of(execution));

    String context = contextCollector.collectContext(Map.of(), 10L);

    assertThat(context).contains("previousExecutions");
    assertThat(context).contains("테스트 내용");
  }

  @Test
  void collectContext_works_without_jobId() {
    String context = contextCollector.collectContext(Map.of(), null);
    assertThat(context).doesNotContain("previousExecutions");
  }
}
