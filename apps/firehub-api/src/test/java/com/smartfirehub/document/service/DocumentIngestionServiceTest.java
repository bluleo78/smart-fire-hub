package com.smartfirehub.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.smartfirehub.document.repository.DocumentFileRepository;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** 인제스션 잡이 텍스트→청크→임베딩→document_chunk 저장 + 상태 전이를 수행하는지 종단 검증. */
@Transactional
class DocumentIngestionServiceTest extends IntegrationTestBase {

  @Autowired private DocumentIngestionService ingestionService;
  @Autowired private DocumentFileRepository fileRepository;
  @Autowired private DSLContext dsl;
  @MockitoBean private EmbeddingProviderFactory embeddingProviderFactory;

  // 업로드 디렉터리를 임시 경로로 돌려 테스트가 ./uploads 에 파일을 남기지 않도록 한다.
  @org.junit.jupiter.api.io.TempDir static java.nio.file.Path tempDir;

  @org.springframework.test.context.DynamicPropertySource
  static void props(org.springframework.test.context.DynamicPropertyRegistry r) {
    r.add("firehub.file.upload-dir", () -> tempDir.toString());
  }

  @Test
  void processIngestionStoresChunksAndCompletes() {
    // 임베딩 호출을 차원만 맞춘 가짜 벡터로 대체해 외부 Ollama 의존 없이 종단 흐름을 검증한다.
    EmbeddingProvider fake =
        new EmbeddingProvider() {
          public List<float[]> embed(List<String> texts) {
            return texts.stream().map(t -> new float[1024]).toList();
          }

          public String modelId() {
            return "fake";
          }

          public int dimension() {
            return 1024;
          }
        };
    when(embeddingProviderFactory.current()).thenReturn(fake);

    Long userId =
        dsl.fetchOne(
                "INSERT INTO \"user\"(username, password, name, email) VALUES"
                    + " ('docing','x','Doc Ing','docing@example.com') RETURNING id")
            .get(0, Long.class);
    Long datasetId =
        dsl.fetchOne(
                "INSERT INTO dataset(name, table_name, storage_type, origin_type, created_by) VALUES"
                    + " ('docing-set','data.docing_set','DOCUMENT', 'SOURCE', ?) RETURNING id",
                userId)
            .get(0, Long.class);
    byte[] data = "소방 점검 보고서. 화재 예방 점검 결과.".repeat(50).getBytes();
    Long fileId =
        ingestionService.upload(datasetId, data, "report.txt", "text/plain", userId).id();

    ingestionService.processIngestion(fileId);

    var file = fileRepository.findById(fileId).orElseThrow();
    assertThat(file.status()).isEqualTo("COMPLETED");
    assertThat(file.chunkCount()).isPositive();
    int chunks =
        dsl.fetchCount(dsl.selectFrom("document_chunk").where("document_file_id = ?", fileId));
    assertThat(chunks).isEqualTo(file.chunkCount());
  }
}
