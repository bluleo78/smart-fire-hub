package com.smartfirehub.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** document_chunk 벡터 저장/코사인 검색이 동작하는지 검증한다(pgvector 가용성 포함). */
@Transactional
class DocumentSchemaIntegrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  /** [1,0,0,...,0] 형태의 1024차원 축1 단위 벡터 리터럴 (vector(1024) 컬럼과 차원 일치 필수) */
  private static String vec1024() {
    StringBuilder sb = new StringBuilder("[1");
    for (int i = 1; i < 1024; i++) sb.append(",0");
    return sb.append("]").toString();
  }

  /** [0,1,0,...,0] 형태의 1024차원 축2 단위 벡터 리터럴 (축1 벡터와 직교 → 코사인 거리 최대) */
  private static String vec1024Axis2() {
    StringBuilder sb = new StringBuilder("[0,1");
    for (int i = 2; i < 1024; i++) sb.append(",0");
    return sb.append("]").toString();
  }

  @Test
  void vectorRoundTripAndCosineSearch() {
    // 시드 사용자가 없으므로 테스트용 사용자를 직접 생성 (created_by/uploaded_by FK 충족)
    Long userId =
        dsl.fetchOne(
                "INSERT INTO \"user\"(username, password, name, email) "
                    + "VALUES ('docschema_test','pw','Doc Schema Test','docschema@example.com') "
                    + "RETURNING id")
            .get(0, Long.class);

    // DOCUMENT 타입 데이터셋 생성 (V61 제약에 DOCUMENT가 추가되어야 통과)
    Long datasetId =
        dsl.fetchOne(
                "INSERT INTO dataset(name, table_name, dataset_type, created_by) "
                    + "VALUES ('docset-test','data.docset_test','DOCUMENT', ?) RETURNING id",
                userId)
            .get(0, Long.class);

    // 문서 원본 메타 생성
    Long fileId =
        dsl.fetchOne(
                "INSERT INTO document_file(dataset_id, original_name, mime_type, file_size,"
                    + " storage_path, status, uploaded_by) VALUES (?, 'a.txt','text/plain', 3,"
                    + " '/tmp/a.txt','COMPLETED', ?) RETURNING id",
                datasetId, userId)
            .get(0, Long.class);

    // 청크 2건 적재: 'hello'(축1) / 'world'(축2, 프로브와 직교)
    // → 코사인 랭킹이 실제로 동작하는지 검증하기 위해 서로 다른 방향의 임베딩을 사용한다.
    dsl.execute(
        "INSERT INTO document_chunk(document_file_id, dataset_id, chunk_index, content,"
            + " embedding, embedding_model) VALUES (?, ?, 0, 'hello', ?::vector,'bge-m3')",
        fileId, datasetId, vec1024());
    dsl.execute(
        "INSERT INTO document_chunk(document_file_id, dataset_id, chunk_index, content,"
            + " embedding, embedding_model) VALUES (?, ?, 1, 'world', ?::vector,'bge-m3')",
        fileId, datasetId, vec1024Axis2());

    // 축1 벡터로 프로브 → 동일 방향인 'hello'가 직교하는 'world'보다 가까워 1순위여야 한다.
    String probe = vec1024();
    String content =
        dsl.fetchOne(
                "SELECT content FROM document_chunk WHERE dataset_id = ? "
                    + "ORDER BY embedding <=> ?::vector LIMIT 1",
                datasetId, probe)
            .get(0, String.class);

    assertThat(content).isEqualTo("hello");
  }
}
