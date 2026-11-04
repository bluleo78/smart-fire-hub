package com.smartfirehub.dataset.repository;

import static com.smartfirehub.jooq.Tables.DATASET_CATEGORY;
import static com.smartfirehub.jooq.Tables.DATASET_COLUMN;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnSnapshot;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TestUsers;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 컬럼 메타 보관·복원({@code snapshotByDatasetId}/{@code restoreSnapshot})의 실 DB 검증(WD-485).
 *
 * <p>보관본 타입을 jOOQ {@code Record} 에서 {@link DatasetColumnSnapshot} 으로 바꿨다. 지켜야 할 것: (1) 보관본이 행의 모든
 * 열을 그대로 담는다, (2) 복원하면 id·검색 대상·테넌트 id 까지 같은 행으로 돌아온다, (3) 컬럼 메타 지문이 예전 Record 기반 계산과 같은 값이다 — 이미
 * 남은 재생성 보관본의 지문과 맞아야 하기 때문이다.
 *
 * <p>인터페이스 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-485).
 */
@Transactional
abstract class DatasetColumnSnapshotRepositoryContractTest extends IntegrationTestBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract DatasetColumnRepository repository();

  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  private Long datasetId;

  @BeforeEach
  void setUp() {
    Long userId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                encoder,
                "snap_testuser",
                "snap_test@example.com",
                "Password123",
                "Snap Test User",
                DEFAULT_TEST_TENANT_ID)
            .id();
    dsl.insertInto(DATASET_CATEGORY).set(DATASET_CATEGORY.NAME, "Snap Category").execute();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));

    DatasetDetailResponse ds =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "SnapDs",
                "snap_ds_wd485",
                null,
                null,
                "TABLE",
                "SOURCE",
                List.of(
                    new DatasetColumnRequest("code", "코드", "TEXT", null, false, true, "설명"),
                    new DatasetColumnRequest("amount", null, "VARCHAR", 50, true, false, null)),
                null),
            userId);
    datasetId = ds.id();
    // 응답 DTO 에 없는 열(검색 대상)을 켜 둔다 — 보관·복원이 이 값을 잃지 않는지 본다.
    dsl.update(DATASET_COLUMN)
        .set(DATASET_COLUMN.IS_SEARCHABLE, true)
        .where(DATASET_COLUMN.DATASET_ID.eq(datasetId))
        .and(DATASET_COLUMN.COLUMN_NAME.eq("code"))
        .execute();
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  /** 행의 모든 열을 담는다 — 실제 스키마의 열 이름 집합과 같고, 열마다 DB 값과 같아야 한다. */
  @Test
  void snapshot_capturesEveryColumnOfEachRow() {
    List<DatasetColumnSnapshot> snapshot = repository().snapshotByDatasetId(datasetId);

    List<Record> raw =
        dsl.select()
            .from(DATASET_COLUMN)
            .where(DATASET_COLUMN.DATASET_ID.eq(datasetId))
            .orderBy(DATASET_COLUMN.COLUMN_ORDER)
            .fetch();
    // 보관본이 실제 스키마의 열 이름 집합과 똑같은 열을 담는다 — 생성 코드(DATASET_COLUMN)가 아니라 information_schema 를 보므로,
    // 마이그레이션이 열을 더하거나 이름을 바꾸고 jOOQ 를 다시 생성하지 않아도 여기서 걸린다.
    Set<String> schemaColumns =
        Set.copyOf(
            dsl.fetch(
                    "select column_name from information_schema.columns"
                        + " where table_schema = 'public' and table_name = 'dataset_column'")
                .getValues(0, String.class));
    Set<String> snapshotColumns =
        Arrays.stream(DatasetColumnSnapshot.class.getRecordComponents())
            .map(c -> c.getName().replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    assertThat(snapshotColumns).isEqualTo(schemaColumns);
    assertThat(snapshot).hasSize(2);
    for (int i = 0; i < raw.size(); i++) {
      Record r = raw.get(i);
      DatasetColumnSnapshot s = snapshot.get(i);
      assertThat(s.id()).isEqualTo(r.get(DATASET_COLUMN.ID));
      assertThat(s.datasetId()).isEqualTo(r.get(DATASET_COLUMN.DATASET_ID));
      assertThat(s.columnName()).isEqualTo(r.get(DATASET_COLUMN.COLUMN_NAME));
      assertThat(s.displayName()).isEqualTo(r.get(DATASET_COLUMN.DISPLAY_NAME));
      assertThat(s.dataType()).isEqualTo(r.get(DATASET_COLUMN.DATA_TYPE));
      assertThat(s.isNullable()).isEqualTo(r.get(DATASET_COLUMN.IS_NULLABLE));
      assertThat(s.isIndexed()).isEqualTo(r.get(DATASET_COLUMN.IS_INDEXED));
      assertThat(s.description()).isEqualTo(r.get(DATASET_COLUMN.DESCRIPTION));
      assertThat(s.columnOrder()).isEqualTo(r.get(DATASET_COLUMN.COLUMN_ORDER));
      assertThat(s.maxLength()).isEqualTo(r.get(DATASET_COLUMN.MAX_LENGTH));
      assertThat(s.isPrimaryKey()).isEqualTo(r.get(DATASET_COLUMN.IS_PRIMARY_KEY));
      assertThat(s.tenantId()).isEqualTo(r.get(DATASET_COLUMN.TENANT_ID));
      assertThat(s.isSearchable()).isEqualTo(r.get(DATASET_COLUMN.IS_SEARCHABLE));
    }
    assertThat(snapshot.get(0).isSearchable()).isTrue();
    assertThat(snapshot.get(1).maxLength()).isEqualTo(50);
  }

  /** 복원하면 id·검색 대상까지 같은 행으로 돌아오고 지문도 같다. */
  @Test
  void restore_bringsBackIdenticalRows_andSameFingerprint() {
    List<DatasetColumnSnapshot> before = repository().snapshotByDatasetId(datasetId);
    String fingerprintBefore = DatasetService.columnMetaFingerprint(before);

    // 재생성처럼 행을 지우고 다른 id 로 새 행을 넣는다.
    repository().deleteByDatasetId(datasetId);
    dsl.insertInto(DATASET_COLUMN)
        .set(DATASET_COLUMN.DATASET_ID, datasetId)
        .set(DATASET_COLUMN.COLUMN_NAME, "other")
        .set(DATASET_COLUMN.DATA_TYPE, "TEXT")
        .set(DATASET_COLUMN.COLUMN_ORDER, 0)
        .execute();
    assertThat(DatasetService.columnMetaFingerprint(repository().snapshotByDatasetId(datasetId)))
        .isNotEqualTo(fingerprintBefore);

    repository().restoreSnapshot(datasetId, before);

    List<DatasetColumnSnapshot> after = repository().snapshotByDatasetId(datasetId);
    assertThat(after).isEqualTo(before);
    assertThat(DatasetService.columnMetaFingerprint(after)).isEqualTo(fingerprintBefore);
  }

  /**
   * 지문이 예전 구현(jOOQ Record 의 id·column_name·data_type 을 id 순으로 이어 SHA-256 앞 32자)과 같은 값이다 — 실제 DB 행으로
   * 예전 계산을 다시 해 비교한다.
   */
  @Test
  void fingerprint_equalsLegacyRecordBasedComputation() {
    List<Record> raw =
        dsl.select()
            .from(DSL.table(DSL.name("dataset_column")))
            .where(DATASET_COLUMN.DATASET_ID.eq(datasetId))
            .orderBy(DATASET_COLUMN.COLUMN_ORDER)
            .fetch();
    List<Record> sorted = new java.util.ArrayList<>(raw);
    sorted.sort(java.util.Comparator.comparingLong(r -> ((Number) r.get("id")).longValue()));
    StringBuilder text = new StringBuilder();
    for (Record r : sorted) {
      text.append(r.get("id"))
          .append('\u001F')
          .append(r.get("column_name"))
          .append('\u001F')
          .append(r.get("data_type"))
          .append('\u001E');
    }
    String legacy =
        com.smartfirehub.dataset.rowsearch.SearchConfig.sha256(text.toString()).substring(0, 32);

    assertThat(DatasetService.columnMetaFingerprint(repository().snapshotByDatasetId(datasetId)))
        .isEqualTo(legacy);
  }
}
