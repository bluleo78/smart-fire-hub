package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.document.service.DocumentIngestionService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.jobrunr.jobs.annotations.Job;

/**
 * JobRunr 잡이 테넌트를 페이로드로 받는지 시그니처 수준에서 고정한다.
 *
 * <p>왜 시그니처를 단언하나: 잡 본문의 동작은 잡 서버를 띄워야 검증되는데 테스트 프로파일은
 * background-job-server 를 끈다(application-test.yml). 반면 "테넌트 파라미터가 사라지는"
 * 회귀는 컴파일이 아니라 조용한 무동작으로 나타나므로, 계약을 테스트로 못박는다.
 */
class BackgroundTenantPropagationTest {

  @Test
  void jobMethodsAcceptTenantIdAsLastParameter() throws Exception {
    assertLastParamIsTenantId(DocumentIngestionService.class, "processIngestion");
    assertLastParamIsTenantId(
        com.smartfirehub.document.service.DocumentChunkReembedService.class, "reembedDataset");
    assertLastParamIsTenantId(
        com.smartfirehub.dataset.search.DatasetEmbeddingService.class, "reindexEmbedding");
    assertLastParamIsTenantId(
        com.smartfirehub.dataimport.service.DataImportService.class, "processImport");
  }

  @Test
  void jobMethodsAreNotTransactional() {
    // @Transactional 이 잡 메서드에 붙으면 본문 시작 전에 트랜잭션이 열려 GUC 주입 시점을 놓친다.
    for (Method m : DocumentIngestionService.class.getDeclaredMethods()) {
      if (m.isAnnotationPresent(Job.class)) {
        assertThat(m.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
            .as("@Job 메서드 %s 에 @Transactional 이 붙으면 테넌트 주입 시점을 놓친다", m.getName())
            .isFalse();
      }
    }
  }

  private void assertLastParamIsTenantId(Class<?> type, String methodName) {
    Method target =
        java.util.Arrays.stream(type.getDeclaredMethods())
            .filter(m -> m.getName().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(methodName + " 메서드를 찾을 수 없다"));
    Class<?>[] params = target.getParameterTypes();
    assertThat(params).as("%s.%s 파라미터", type.getSimpleName(), methodName).isNotEmpty();
    assertThat(params[params.length - 1])
        .as("%s.%s 의 마지막 파라미터는 tenantId(long) 여야 한다", type.getSimpleName(), methodName)
        .isIn(long.class, Long.class);
  }
}
