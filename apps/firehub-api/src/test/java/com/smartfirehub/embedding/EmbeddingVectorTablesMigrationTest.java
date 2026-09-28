package com.smartfirehub.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.PostgresTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V131: V130 상태의 1024 벡터(모델 NULL 포함)가 차원 테이블로 그대로 옮겨지고 옛 컬럼·인덱스·플랫폼 임베딩 행이
 * 사라지는지 본다. 플랫폼 설정 권한 2건(platform:settings:read|write)과 그 역할 매핑이 지워지는지도 본다.
 *
 * <p><b>왜 새 DB 인가.</b> 공유 테스트 DB 는 부팅 때 V131 까지 이미 적용돼 옛 컬럼이 없다 — "마이그레이션 이전
 * 상태"를 재현할 수 없다. 같은 컨테이너에 일회용 DB 를 만들어 Flyway 를 target=130 까지 돌리고, 소유자 롤(app,
 * RLS 우회)로 픽스처를 심은 뒤 V131 을 적용한다. V125 의 CREATE INDEX CONCURRENTLY 때문에 트랜잭션 잠금을 끈다
 * ({@code application.yml} 의 같은 설정).
 */
class EmbeddingVectorTablesMigrationTest {

  /** 플랫폼 설정 권한 매핑 수(테넌트 평면 + 플랫폼 평면). */
  private static final String SETTINGS_PERMISSION_MAPPINGS =
      "SELECT (SELECT count(*) FROM role_permission rp JOIN permission p ON p.id = rp.permission_id"
          + "        WHERE p.code IN ('platform:settings:read','platform:settings:write'))"
          + "     + (SELECT count(*) FROM platform_role_permission prp JOIN permission p ON p.id = prp.permission_id"
          + "        WHERE p.code IN ('platform:settings:read','platform:settings:write'))";

  private String dbName;
  private String url;

  @BeforeEach
  void createDatabase() throws Exception {
    dbName = "v131_mig_" + System.nanoTime();
    try (Connection c = admin(); Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE " + dbName);
    }
    url = PostgresTestContainer.INSTANCE.getJdbcUrl().replace("/smartfirehub_test", "/" + dbName);
  }

  @AfterEach
  void dropDatabase() throws Exception {
    try (Connection c = admin(); Statement s = c.createStatement()) {
      s.execute("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
    }
  }

  private static Connection admin() throws Exception {
    return DriverManager.getConnection(PostgresTestContainer.INSTANCE.getJdbcUrl(), "app", "app");
  }

  private Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(url, "app", "app")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .target(target)
        .load();
  }

  private static String vec(int hot) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < 1024; i++) sb.append(i == 0 ? "" : ",").append(i == hot ? "1" : "0");
    return sb.append(']').toString();
  }

  @Test
  void movesVectorsToDimensionTablesAndDropsOldColumns() throws Exception {
    flyway("130").migrate();
    try (Connection c = DriverManager.getConnection(url, "app", "app"); Statement s = c.createStatement()) {
      // 기본 테넌트(V81 시드 id=1) 소유 픽스처. 소유자 롤이라 RLS 를 우회해 tenant_id 를 명시한다.
      s.execute(
          "INSERT INTO \"user\"(username, password, name, email) VALUES ('v131u','x','v131','v131@e.com')");
      s.execute(
          "INSERT INTO dataset(name, table_name, storage_type, origin_type, created_by, tenant_id)"
              + " SELECT 'v131-set','data.v131_set','DOCUMENT','SOURCE', id, 1 FROM \"user\" WHERE username='v131u'");
      s.execute(
          "INSERT INTO document_file(dataset_id, original_name, mime_type, file_size, storage_path, status, uploaded_by, tenant_id)"
              + " SELECT d.id,'a.txt','text/plain',1,'/tmp/a','COMPLETED', d.created_by, 1 FROM dataset d WHERE d.name='v131-set'");
      s.execute(
          "INSERT INTO document_chunk(document_file_id, dataset_id, chunk_index, content, embedding, embedding_model, tenant_id)"
              + " SELECT f.id, f.dataset_id, 0, 'with-model', '" + vec(0) + "'::vector, 'bge-m3', 1 FROM document_file f WHERE f.original_name='a.txt'");
      s.execute(
          "INSERT INTO document_chunk(document_file_id, dataset_id, chunk_index, content, embedding, embedding_model, tenant_id)"
              + " SELECT f.id, f.dataset_id, 1, 'null-model', '" + vec(1) + "'::vector, NULL, 1 FROM document_file f WHERE f.original_name='a.txt'");
      s.execute(
          "INSERT INTO document_chunk(document_file_id, dataset_id, chunk_index, content, tenant_id)"
              + " SELECT f.id, f.dataset_id, 2, 'no-vector', 1 FROM document_file f WHERE f.original_name='a.txt'");
      s.execute(
          "INSERT INTO dataset_embedding(dataset_id, source_text, embedding, embedding_model, tenant_id)"
              + " SELECT id, 'src', '" + vec(2) + "'::vector, 'bge-m3', 1 FROM dataset WHERE name='v131-set'");

      // 사전 상태 — V130 에는 권한 2건과 매핑이 실제로 있다. 없으면 아래 "0" 단언은 아무것도 증명하지 않는다.
      assertThat(
              scalar(s, "SELECT count(*) FROM permission WHERE code IN ('platform:settings:read','platform:settings:write')"))
          .isEqualTo(2L);
      assertThat(scalar(s, SETTINGS_PERMISSION_MAPPINGS)).isPositive();
    }

    flyway("131").migrate();

    try (Connection c = DriverManager.getConnection(url, "app", "app"); Statement s = c.createStatement()) {
      assertThat(scalar(s, "SELECT count(*) FROM document_chunk_vec_1024")).isEqualTo(2L);
      assertThat(scalar(s, "SELECT count(*) FROM document_chunk_vec_1536")).isZero();
      assertThat(scalar(s, "SELECT count(*) FROM dataset_embedding_vec_1024")).isEqualTo(1L);
      // 모델 NULL 벡터는 '<unknown>' 으로 옮겨진다 — 현재 모델과 절대 일치하지 않아 검색에서 빠지고 재임베딩 대상이 된다.
      assertThat(
              text(s, "SELECT v.embedding_model FROM document_chunk_vec_1024 v JOIN document_chunk c ON c.id=v.chunk_id WHERE c.content='null-model'"))
          .isEqualTo("<unknown>");
      // 값이 그대로 옮겨졌다(축 0 벡터).
      assertThat(
              text(s, "SELECT v.embedding::text FROM document_chunk_vec_1024 v JOIN document_chunk c ON c.id=v.chunk_id WHERE c.content='with-model'"))
          .isEqualTo(vec(0));
      // tenant_id·dataset_id 가 부모에서 채워졌다.
      assertThat(scalar(s, "SELECT count(*) FROM document_chunk_vec_1024 v JOIN document_chunk c ON c.id=v.chunk_id WHERE v.tenant_id=c.tenant_id AND v.dataset_id=c.dataset_id")).isEqualTo(2L);
      // 옛 컬럼·인덱스 소멸
      assertThat(scalar(s, "SELECT count(*) FROM information_schema.columns WHERE table_name IN ('document_chunk','dataset_embedding') AND column_name IN ('embedding','embedding_model')")).isZero();
      assertThat(scalar(s, "SELECT count(*) FROM pg_indexes WHERE indexname IN ('idx_document_chunk_embedding','idx_dataset_embedding_vector')")).isZero();
      // 플랫폼 임베딩 행 소멸(V63 시드)
      assertThat(scalar(s, "SELECT count(*) FROM system_settings WHERE key LIKE 'embedding.%'")).isZero();
      // RLS: 5개 테이블 모두 ENABLE(FORCE 아님) + 정책 1개씩
      assertThat(
              scalar(s, "SELECT count(*) FROM pg_class WHERE relname IN ('document_chunk_vec_1024','document_chunk_vec_1536','dataset_embedding_vec_1024','dataset_embedding_vec_1536','embedding_reembed_state') AND relrowsecurity AND NOT relforcerowsecurity"))
          .isEqualTo(5L);
      assertThat(
              scalar(s, "SELECT count(*) FROM pg_policies WHERE tablename IN ('document_chunk_vec_1024','document_chunk_vec_1536','dataset_embedding_vec_1024','dataset_embedding_vec_1536','embedding_reembed_state')"))
          .isEqualTo(5L);

      // 플랫폼 설정 권한 2건과 그 매핑 소멸(게이팅하던 PlatformSettingsController 는 Task 2 에서 삭제됐다).
      assertThat(
              scalar(s, "SELECT count(*) FROM permission WHERE code IN ('platform:settings:read','platform:settings:write')"))
          .isZero();
      assertThat(scalar(s, SETTINGS_PERMISSION_MAPPINGS)).isZero();
      // 양성 대조군 — 같은 카테고리의 다른 플랫폼 권한과 SUPER_ADMIN 매핑은 살아 있다(코드 완전 일치 삭제).
      assertThat(scalar(s, "SELECT count(*) FROM permission WHERE code = 'platform:tenant:create'")).isEqualTo(1L);
      assertThat(
              scalar(s, "SELECT count(*) FROM platform_role_permission prp JOIN permission p ON p.id = prp.permission_id"
                  + " JOIN platform_role pr ON pr.id = prp.platform_role_id"
                  + " WHERE pr.name = 'SUPER_ADMIN' AND p.code = 'platform:tenant:create'"))
          .isEqualTo(1L);
    }
  }

  private static long scalar(Statement s, String sql) throws Exception {
    try (ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static String text(Statement s, String sql) throws Exception {
    try (ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }
}
