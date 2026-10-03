package com.smartfirehub.support;

import java.util.concurrent.atomic.AtomicLong;
import org.jooq.DSLContext;

/**
 * 벡터 저장 계층 테스트용 픽스처. <b>호출자가 테넌트 트랜잭션({@code inTenantFixture}) 안에서 부른다</b> — RLS 테이블이라 GUC 없이 쓰면
 * tenant_id DEFAULT 가 NULL 이 된다.
 */
public final class EmbeddingTestFixtures {

  private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

  private EmbeddingTestFixtures() {}

  /** DOCUMENT 데이터셋 + COMPLETED 문서 파일 하나. */
  public record DocFixture(long userId, long datasetId, long fileId) {}

  /** 실행마다 고유한 이름으로 DOCUMENT 데이터셋과 COMPLETED 파일을 만든다(table_name 은 전역 유니크). */
  public static DocFixture createDocumentDataset(DSLContext dsl, String prefix) {
    long n = SEQ.incrementAndGet();
    Long userId = TenantRlsTestSupport.insertUser(dsl, prefix + "u");
    long datasetId =
        dsl.fetchOne(
                "INSERT INTO dataset(name, table_name, storage_type, origin_type, created_by)"
                    + " VALUES (?, ?, 'DOCUMENT', 'SOURCE', ?) RETURNING id",
                prefix + "-set-" + n,
                "data." + prefix + "_set_" + n,
                userId)
            .get(0, Long.class);
    long fileId =
        dsl.fetchOne(
                "INSERT INTO document_file(dataset_id, original_name, mime_type, file_size,"
                    + " storage_path, status, uploaded_by)"
                    + " VALUES (?, 'f.txt', 'text/plain', 1, '/tmp/f', 'COMPLETED', ?) RETURNING id",
                datasetId,
                userId)
            .get(0, Long.class);
    return new DocFixture(userId, datasetId, fileId);
  }

  /** 한 칸만 1 인 벡터 — 칸이 같으면 코사인 거리 0, 다르면 1. */
  public static float[] axis(int dim, int hot) {
    float[] v = new float[dim];
    v[hot] = 1f;
    return v;
  }
}
