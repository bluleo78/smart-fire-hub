package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.dto.UpdateDatasetRequest;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * DatasetRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-707). 플랫폼 메타 두
 * 메서드(findSchemaMetas·findTableLevels)는 {@link DatasetRepositoryPlatformMetaContractTest} 가, 나머지 공개
 * 메서드 26개는 여기서 본다.
 *
 * <p>리뷰 포인트: 목록 검색의 대소문자 무시·{@code %}·{@code _} 이스케이프·한글·앞뒤 공백(Review Focus 5), 다중 조인 중복 제거 뒤
 * 페이지(selectDistinct), 가시성(등급·제한 없음·역할 없음), 다른 테넌트 차단(Review Focus 4), 잠금 메서드가 행을 돌려주고 같은 트랜잭션에서
 * 갱신이 되는지(스펙 D2 — 잠금 모드 자체는 동시성 시험 몫), 빈 문자열(emptyStringIsNull). 테넌트마다 새로 만들므로 목록 결과는 이 시험의 행뿐이다.
 */
public abstract class DatasetRepositoryContractTest extends TenantPairContractBase {

  protected abstract DatasetRepository repository();

  private long userA;

  @BeforeEach
  void setUpExtra() {
    userA = fx.createUser(prefix + "-u", null);
  }

  /** 등급 rank 2 자격(테넌트 기본 등급까지 보인다). */
  private DatasetVisibility rank2() {
    return new DatasetVisibility(new Clearance(userA, tenantA, 2, Set.of(), false, Set.of()), null);
  }

  /** 검색어 하나로 목록을 읽어 id 만(제한 없는 가시성, 필터 없음). */
  private List<Long> searchIds(String search) {
    return inA(
        () ->
            repository()
                .findAll(
                    null,
                    null,
                    null,
                    search,
                    0,
                    100,
                    null,
                    null,
                    false,
                    null,
                    DatasetVisibility.UNRESTRICTED)
                .stream()
                .map(DatasetResponse::id)
                .toList());
  }

  /** 검색어로 센 건수. */
  private long searchCount(String search) {
    return inA(
        () ->
            repository()
                .count(
                    null,
                    null,
                    null,
                    search,
                    null,
                    null,
                    false,
                    null,
                    DatasetVisibility.UNRESTRICTED));
  }

  private String tableNameOf(long datasetId) {
    return inA(() -> repository().findTableNameById(datasetId)).orElseThrow();
  }

  @Test
  @DisplayName("save — 저장한 행을 돌려준다(카테고리만 채우고 등급·태그·즐겨찾기는 비움), 출처 스텝·만든 이·테이블 이름 조회")
  void save_roundTrip() {
    long category = fx.createCategory(tenantA, prefix + "-cat");
    // 출처 스텝 FK 용 파이프라인·스텝 하나(적재 전략은 열 기본값과 같은 REPLACE).
    long step =
        fx.createStep(
            fx.createPipeline(tenantA, prefix + "-p", true),
            "fx-step",
            "SQL",
            null,
            null,
            "REPLACE");
    String tableName = "t_" + prefix + "_s";

    DatasetResponse saved =
        inA(
            () ->
                repository()
                    .save(
                        new CreateDatasetRequest(
                            "저장 이름", tableName, "설명", category, "TABLE", "DERIVED", null, step),
                        userA));

    assertThat(saved.id()).isNotNull();
    assertThat(saved.name()).isEqualTo("저장 이름");
    assertThat(saved.tableName()).isEqualTo(tableName);
    assertThat(saved.description()).isEqualTo("설명");
    assertThat(saved.category()).isNotNull();
    assertThat(saved.category().id()).isEqualTo(category);
    assertThat(saved.category().name()).isEqualTo(prefix + "-cat");
    assertThat(saved.storageType()).isEqualTo("TABLE");
    assertThat(saved.originType()).isEqualTo("DERIVED");
    assertThat(saved.createdAt()).isNotNull();
    assertThat(saved.isFavorite()).isFalse();
    assertThat(saved.tags()).isEmpty();
    assertThat(saved.status()).isEqualTo("NONE");
    assertThat(saved.statusNote()).isNull();
    assertThat(saved.statusUpdatedBy()).isNull();
    assertThat(saved.statusUpdatedAt()).isNull();
    assertThat(saved.sourcePipelineStepId()).isEqualTo(step);
    // jOOQ 판과 같이 저장 직후 응답에는 등급을 싣지 않는다.
    assertThat(saved.securityLevel()).isNull();
    assertThat(saved.securityLevelAutoRaisedAt()).isNull();

    assertThat(inA(() -> repository().findBySourcePipelineStepId(step))).contains(saved.id());
    assertThat(inA(() -> repository().findBySourcePipelineStepId(-1L))).isEmpty();
    assertThat(inA(() -> repository().findCreatedByById(saved.id()))).contains(userA);
    assertThat(inA(() -> repository().findTableNameById(saved.id()))).contains(tableName);
    assertThat(inA(() -> repository().findTableNameById(-1L))).isEmpty();
    assertThat(inA(() -> repository().findCreatedByById(-1L))).isEmpty();

    // 카테고리·출처 스텝 없이 — 둘 다 null.
    DatasetResponse plain =
        inA(
            () ->
                repository()
                    .save(
                        new CreateDatasetRequest(
                            "기본", "t_" + prefix + "_p", null, null, null, null, null, null),
                        userA));
    assertThat(plain.category()).isNull();
    assertThat(plain.sourcePipelineStepId()).isNull();
    assertThat(plain.description()).isNull();
    assertThat(plain.originType()).isEqualTo("SOURCE");
    // 다른 테넌트에서는 보이지 않는다.
    assertThat(fx.inTenant(tenantB, () -> repository().findTableNameById(saved.id()))).isEmpty();
  }

  @Test
  @DisplayName("findById — 태그(이름순)·즐겨찾기·상태 변경자 이름·등급 요약·카테고리를 채우고, 없거나 다른 테넌트면 빈 값")
  void findById_fillsRelations() {
    long ds = fx.createDataset(tenantA, prefix + " 상세");
    long category = fx.createCategory(tenantA, prefix + "-c");
    fx.assignCategory(ds, category);
    fx.addTag(ds, "btag");
    fx.addTag(ds, "atag");
    fx.addFavorite(userA, ds);
    runA(() -> repository().updateStatus(ds, "CERTIFIED", "인증 메모", userA));

    DatasetResponse r = inA(() -> repository().findById(ds, userA)).orElseThrow();
    assertThat(r.name()).isEqualTo(prefix + " 상세");
    assertThat(r.isFavorite()).isTrue();
    assertThat(r.tags()).containsExactly("atag", "btag");
    assertThat(r.status()).isEqualTo("CERTIFIED");
    assertThat(r.statusNote()).isEqualTo("인증 메모");
    assertThat(r.statusUpdatedBy()).isEqualTo("n");
    assertThat(r.statusUpdatedAt()).isNotNull();
    assertThat(r.category().id()).isEqualTo(category);
    assertThat(r.securityLevel()).isNotNull();
    assertThat(r.securityLevel().id()).isEqualTo(fx.datasetSecurityLevel(ds));
    assertThat(r.storageType()).isEqualTo("TABLE");
    assertThat(r.originType()).isEqualTo("SOURCE");

    // 사용자 없이 읽으면 즐겨찾기는 거짓.
    assertThat(inA(() -> repository().findById(ds)).orElseThrow().isFavorite()).isFalse();
    // 같은 사용자라도 즐겨찾기하지 않은 다른 데이터셋은 거짓 — 즐겨찾기 여부는 그 데이터셋 하나로 판정한다.
    long notFavorite = fx.createDataset(tenantA, prefix + " 즐겨찾기 아님");
    assertThat(inA(() -> repository().findById(notFavorite, userA)).orElseThrow().isFavorite())
        .isFalse();
    assertThat(inA(() -> repository().findById(-1L))).isEmpty();
    assertThat(inA(() -> repository().findById(-1L, userA))).isEmpty();
    assertThat(fx.inTenant(tenantB, () -> repository().findById(ds, userA))).isEmpty();
  }

  @Test
  @DisplayName("update·updateStatus — 갱신 값과 수정자·수정 시각, 빈 문자열은 DB 별(emptyStringIsNull)")
  void update_andEmptyStrings() {
    long ds = fx.createDataset(tenantA, prefix + " 옛");
    long category = fx.createCategory(tenantA, prefix + "-c");
    assertThat(inA(() -> repository().findUpdatedByById(ds))).isEmpty();

    runA(
        () ->
            repository()
                .update(ds, new UpdateDatasetRequest(prefix + " 새", "새 설명", category), userA));
    DatasetResponse r = inA(() -> repository().findById(ds)).orElseThrow();
    assertThat(r.name()).isEqualTo(prefix + " 새");
    assertThat(r.description()).isEqualTo("새 설명");
    assertThat(r.category().id()).isEqualTo(category);
    assertThat(inA(() -> repository().findUpdatedByById(ds))).contains(userA);
    assertThat(inA(() -> repository().findUpdatedAtById(ds))).isPresent();

    // 빈 설명·빈 상태 메모 — PG 는 '' 그대로, Tibero 는 NULL.
    runA(() -> repository().update(ds, new UpdateDatasetRequest(prefix + " 새", "", null), userA));
    runA(() -> repository().updateStatus(ds, "DEPRECATED", "", null));
    DatasetResponse empty = inA(() -> repository().findById(ds)).orElseThrow();
    assertThat(empty.description()).isEqualTo(emptyStringIsNull() ? null : "");
    assertThat(empty.statusNote()).isEqualTo(emptyStringIsNull() ? null : "");
    assertThat(empty.category()).isNull();
    assertThat(empty.status()).isEqualTo("DEPRECATED");
    assertThat(empty.statusUpdatedBy()).isNull();

    // 다른 테넌트에서의 갱신은 행을 건드리지 않는다.
    fx.inTenant(
        tenantB,
        () -> {
          repository().update(ds, new UpdateDatasetRequest("침범", null, null), userA);
          return null;
        });
    assertThat(inA(() -> repository().findById(ds)).orElseThrow().name()).isEqualTo(prefix + " 새");
  }

  @Test
  @DisplayName("lockUpdatedAtById — 행이 없으면 빈 값, 수정 시각이 NULL 이면 updatedAt=null 인 값")
  void lockUpdatedAtById_distinguishesMissingRowFromNullTime() {
    long ds = fx.createDataset(tenantA, prefix + " 잠금");

    Optional<LockedRowVersion> locked = inA(() -> repository().lockUpdatedAtById(ds));
    assertThat(locked).isPresent();
    assertThat(locked.get().updatedAt()).isNotNull();

    fx.setDatasetField(ds, "updated_at", null);
    Optional<LockedRowVersion> nullTime = inA(() -> repository().lockUpdatedAtById(ds));
    assertThat(nullTime).isPresent();
    assertThat(nullTime.get().updatedAt()).isNull();
    assertThat(inA(() -> repository().findUpdatedAtById(ds))).isEmpty();

    assertThat(inA(() -> repository().lockUpdatedAtById(-1L))).isEmpty();
    assertThat(fx.inTenant(tenantB, () -> repository().lockUpdatedAtById(ds))).isEmpty();
    // 잠근 트랜잭션 안에서 갱신이 된다.
    runA(
        () -> {
          repository().lockUpdatedAtById(ds);
          repository().update(ds, new UpdateDatasetRequest(prefix + " 갱신", null, null), userA);
        });
    assertThat(inA(() -> repository().findUpdatedAtById(ds))).isPresent();
  }

  @Test
  @DisplayName("쓰기 대상 잠금 4종 — 행을 돌려주고 같은 트랜잭션에서 갱신이 된다, 없거나 다른 테넌트면 빈 값")
  void writeTargetLocks() {
    long ds = fx.createDataset(tenantA, prefix + " 쓰기");
    String table = tableNameOf(ds);
    DatasetWriteTarget expected = new DatasetWriteTarget(ds, prefix + " 쓰기", table, "TABLE");

    assertThat(inA(() -> repository().lockForDataWrite(ds))).contains(expected);
    assertThat(inA(() -> repository().lockForDataWriteByTableName(table))).contains(expected);
    assertThat(inA(() -> repository().lockForStructureWrite(ds))).contains(expected);
    assertThat(inA(() -> repository().lockForColumnReorder(ds))).contains(expected);

    assertThat(inA(() -> repository().lockForDataWrite(-1L))).isEmpty();
    assertThat(inA(() -> repository().lockForDataWriteByTableName("t_none_" + prefix))).isEmpty();
    assertThat(inA(() -> repository().lockForStructureWrite(-1L))).isEmpty();
    assertThat(inA(() -> repository().lockForColumnReorder(-1L))).isEmpty();

    assertThat(fx.inTenant(tenantB, () -> repository().lockForDataWrite(ds))).isEmpty();
    assertThat(fx.inTenant(tenantB, () -> repository().lockForDataWriteByTableName(table)))
        .isEmpty();
    assertThat(fx.inTenant(tenantB, () -> repository().lockForStructureWrite(ds))).isEmpty();
    assertThat(fx.inTenant(tenantB, () -> repository().lockForColumnReorder(ds))).isEmpty();

    // 잠금 뒤 같은 트랜잭션에서 갱신(Tibero 는 모두 FOR UPDATE — 스펙 D2).
    runA(
        () -> {
          repository().lockForDataWrite(ds);
          repository().lockForColumnReorder(ds);
          repository().lockForStructureWrite(ds);
          repository().updateStatus(ds, "CERTIFIED", "잠금 뒤", userA);
        });
    assertThat(inA(() -> repository().findById(ds)).orElseThrow().statusNote()).isEqualTo("잠금 뒤");
  }

  @Test
  @DisplayName("행 잠금 3종(필드 변경·재연결·공유)과 삭제 잠금 — 같은 트랜잭션에서 갱신·삭제, 없는 id 는 예외 없음·false")
  void rowLocks_andDelete() {
    long ds = fx.createDataset(tenantA, prefix + " 행잠금");

    runA(
        () -> {
          repository().lockForColumnChange(ds);
          repository().lockForOntologyRebind(ds);
          repository().lockForShare(ds);
          repository().update(ds, new UpdateDatasetRequest(prefix + " 잠금 갱신", null, null), userA);
        });
    assertThat(inA(() -> repository().findById(ds)).orElseThrow().name())
        .isEqualTo(prefix + " 잠금 갱신");

    // 없는 id — 예외 없이 지나간다.
    runA(
        () -> {
          repository().lockForColumnChange(-1L);
          repository().lockForOntologyRebind(-1L);
          repository().lockForShare(-1L);
        });
    assertThat(inA(() -> repository().lockForDelete(-1L))).isFalse();
    assertThat(fx.inTenant(tenantB, () -> repository().lockForDelete(ds))).isFalse();
    assertThat(fx.inTenant(tenantB, () -> repository().deleteById(ds))).isZero();

    String table = tableNameOf(ds);
    assertThat(inA(() -> repository().existsByTableName(table))).isTrue();
    assertThat(
            inA(
                () -> {
                  assertThat(repository().lockForDelete(ds)).isTrue();
                  return repository().deleteById(ds);
                }))
        .isEqualTo(1);
    assertThat(inA(() -> repository().deleteById(ds))).isZero();
    assertThat(inA(() -> repository().findById(ds))).isEmpty();
    assertThat(inA(() -> repository().existsByTableName(table))).isFalse();
  }

  @Test
  @DisplayName("existsVisibleByName — 정확 일치, 자기 자신 제외, 볼 수 없는 등급·다른 테넌트는 겹치지 않는다")
  void existsVisibleByName() {
    long secret = fx.createSecurityLevel(tenantA, 5, prefix + "-secret");
    long open = fx.createDataset(tenantA, prefix + " 이름");
    long hidden = fx.createDataset(tenantA, prefix + " 비밀");
    fx.setDatasetField(hidden, "security_level_id", secret);
    long other = fx.createDataset(tenantB, prefix + " 남의");

    assertThat(inA(() -> repository().existsVisibleByName(prefix + " 이름", rank2(), null))).isTrue();
    assertThat(inA(() -> repository().existsVisibleByName(prefix + " 이름", rank2(), open)))
        .isFalse();
    // 대소문자까지 같은 이름만(= 비교).
    assertThat(
            inA(
                () ->
                    repository().existsVisibleByName(prefix.toUpperCase() + " 이름", rank2(), null)))
        .isFalse();
    assertThat(inA(() -> repository().existsVisibleByName(prefix + " 비밀", rank2(), null)))
        .isFalse();
    assertThat(
            inA(
                () ->
                    repository()
                        .existsVisibleByName(prefix + " 비밀", DatasetVisibility.UNRESTRICTED, null)))
        .isTrue();
    DatasetVisibility none = new DatasetVisibility(Clearance.none(userA, tenantA), null);
    assertThat(inA(() -> repository().existsVisibleByName(prefix + " 이름", none, null))).isFalse();
    assertThat(
            inA(
                () ->
                    repository()
                        .existsVisibleByName(prefix + " 남의", DatasetVisibility.UNRESTRICTED, null)))
        .isFalse();
    assertThat(other).isPositive();
  }

  @Test
  @DisplayName("existsByTableName — 있는 이름만 참, 다른 테넌트의 같은 이름은 보이지 않는다")
  void existsByTableName() {
    long ds = fx.createDataset(tenantA, prefix + " 테이블");
    String table = tableNameOf(ds);
    long other = fx.createDataset(tenantB, prefix + " 남");
    String otherTable = fx.inTenant(tenantB, () -> repository().findTableNameById(other)).get();

    assertThat(inA(() -> repository().existsByTableName(table))).isTrue();
    assertThat(inA(() -> repository().existsByTableName("t_none_" + prefix))).isFalse();
    assertThat(inA(() -> repository().existsByTableName(otherTable))).isFalse();
  }

  @Test
  @DisplayName("이름·테이블명 잠금 — 트랜잭션 안에서는 잡히고, 트랜잭션 밖 호출은 예외(MANDATORY)")
  void nameLocks_requireTransaction() {
    runA(
        () -> {
          repository().acquireNameLock(tenantA, prefix + " 이름");
          repository().acquireTableNameLock(tenantA, "t_" + prefix);
        });
    assertThatThrownBy(
            () ->
                fx.noTenant(
                    () -> {
                      repository().acquireNameLock(tenantA, prefix + " 이름");
                      return null;
                    }))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(
            () ->
                fx.noTenant(
                    () -> {
                      repository().acquireTableNameLock(tenantA, "t_" + prefix);
                      return null;
                    }))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  @DisplayName("목록 검색 — 이름·설명·테이블명·컬럼(이름·표시 이름·설명)·태그·카테고리, 대소문자·한글·%·_ 이스케이프·앞뒤 공백")
  void findAll_search() {
    long dName = fx.createDataset(tenantA, "Fire_Alpha 화재");
    long dX = fx.createDataset(tenantA, "FireXAlpha");
    long dDesc = fx.createDataset(tenantA, prefix + " desc");
    fx.setDatasetField(dDesc, "description", "100% 정확한 값");
    long dCol = fx.createDataset(tenantA, prefix + " col");
    long col = fx.createColumn(dCol, "col_xyz", 0);
    fx.setColumnText(col, "ColDisplay", "열설명");
    long dTag = fx.createDataset(tenantA, prefix + " tag");
    fx.addTag(dTag, "tagq");
    long dCat = fx.createDataset(tenantA, prefix + " cat");
    fx.assignCategory(dCat, fx.createCategory(tenantA, "카테고리Q"));
    long dTable = fx.createDataset(tenantA, prefix + " table");
    String table = tableNameOf(dTable);
    long dPlain = fx.createDataset(tenantA, prefix + " plain");

    // 이름 — 대소문자 무시, '_' 는 글자 그대로(FireXAlpha 와 맞지 않는다).
    assertThat(searchIds("fire_alpha")).containsExactly(dName);
    assertThat(searchIds("FIRE_ALPHA")).containsExactly(dName);
    assertThat(searchIds("  fire_alpha  ")).containsExactly(dName);
    assertThat(searchIds("firexalpha")).containsExactly(dX);
    assertThat(searchIds("화재")).containsExactly(dName);
    // '%' 도 글자 그대로 — "1%정" 은 "100% 정확" 과 맞지 않는다.
    assertThat(searchIds("100%")).containsExactly(dDesc);
    assertThat(searchIds("1%정")).isEmpty();
    assertThat(searchIds("FIRE%")).isEmpty();
    // 컬럼 이름·표시 이름·설명.
    assertThat(searchIds("COL_XYZ")).containsExactly(dCol);
    assertThat(searchIds("coldisplay")).containsExactly(dCol);
    assertThat(searchIds("열설명")).containsExactly(dCol);
    // 태그·카테고리·테이블 이름.
    assertThat(searchIds("TAGQ")).containsExactly(dTag);
    assertThat(searchIds("카테고리q")).containsExactly(dCat);
    assertThat(searchIds(table.toUpperCase())).containsExactly(dTable);
    assertThat(searchCount("fire_alpha")).isEqualTo(1);
    assertThat(searchCount("1%정")).isZero();

    // 빈·공백 검색어는 거르지 않는다.
    List<Long> all = List.of(dName, dX, dDesc, dCol, dTag, dCat, dTable, dPlain);
    assertThat(searchIds("   ")).containsExactlyElementsOf(all.stream().sorted().toList());
    assertThat(searchIds(null)).hasSize(all.size());
    assertThat(searchCount(null)).isEqualTo(all.size());
  }

  @Test
  @DisplayName("목록 페이지 — 컬럼·태그 조인으로 중복된 행을 걷고 id 순으로 자른다(2쪽), count 는 중복 없는 건수")
  void findAll_distinctPaging() {
    long d1 = fx.createDataset(tenantA, prefix + " p1");
    long d2 = fx.createDataset(tenantA, prefix + " p2");
    long d3 = fx.createDataset(tenantA, prefix + " p3");
    for (long d : List.of(d1, d2, d3)) {
      fx.addTag(d, "pga");
      fx.addTag(d, "pgb");
      fx.createColumn(d, "pgc1", 0);
      fx.createColumn(d, "pgc2", 1);
    }
    assertThat(page("pg", 0, 2)).containsExactly(d1, d2);
    assertThat(page("pg", 1, 2)).containsExactly(d3);
    assertThat(page("pg", 2, 2)).isEmpty();
    assertThat(searchCount("pg")).isEqualTo(3);
  }

  private List<Long> page(String search, int page, int size) {
    return inA(
        () ->
            repository()
                .findAll(
                    null,
                    null,
                    null,
                    search,
                    page,
                    size,
                    null,
                    null,
                    false,
                    null,
                    DatasetVisibility.UNRESTRICTED)
                .stream()
                .map(DatasetResponse::id)
                .toList());
  }

  @Test
  @DisplayName("목록 필터 — 카테고리·저장 방식·출처·상태·보안 등급·즐겨찾기, 빈 문자열 필터는 무시, count 도 같은 필터")
  void findAll_filters() {
    long category = fx.createCategory(tenantA, prefix + "-f");
    long secret = fx.createSecurityLevel(tenantA, 5, prefix + "-secret");
    long dCat = fx.createDataset(tenantA, prefix + " cat");
    fx.assignCategory(dCat, category);
    long dDoc = fx.createDataset(tenantA, prefix + " doc");
    fx.setDatasetField(dDoc, "storage_type", "DOCUMENT");
    long dDerived = fx.createDataset(tenantA, prefix + " derived");
    fx.setDatasetField(dDerived, "origin_type", "DERIVED");
    long dCert = fx.createDataset(tenantA, prefix + " cert");
    fx.setDatasetField(dCert, "status", "CERTIFIED");
    long dSecret = fx.createDataset(tenantA, prefix + " secret");
    fx.setDatasetField(dSecret, "security_level_id", secret);
    long dFav = fx.createDataset(tenantA, prefix + " fav");
    fx.addFavorite(userA, dFav);

    assertThat(filter(category, null, null, null, false, null)).containsExactly(dCat);
    assertThat(filter(null, "DOCUMENT", null, null, false, null)).containsExactly(dDoc);
    assertThat(filter(null, null, "DERIVED", null, false, null)).containsExactly(dDerived);
    assertThat(filter(null, null, null, "CERTIFIED", false, null)).containsExactly(dCert);
    assertThat(filter(null, null, null, null, false, secret)).containsExactly(dSecret);
    assertThat(filter(null, null, null, null, true, null)).containsExactly(dFav);
    // 빈 문자열·공백 필터는 걸지 않는다.
    assertThat(filter(null, " ", "", " ", false, null)).hasSize(6);

    long countFav =
        inA(
            () ->
                repository()
                    .count(
                        null,
                        null,
                        null,
                        null,
                        userA,
                        null,
                        true,
                        null,
                        DatasetVisibility.UNRESTRICTED));
    assertThat(countFav).isEqualTo(1);
    long countDoc =
        inA(
            () ->
                repository()
                    .count(
                        null,
                        "DOCUMENT",
                        null,
                        null,
                        userA,
                        null,
                        false,
                        null,
                        DatasetVisibility.UNRESTRICTED));
    assertThat(countDoc).isEqualTo(1);
    long countCat =
        inA(
            () ->
                repository()
                    .count(
                        category,
                        null,
                        null,
                        null,
                        null,
                        "NONE",
                        false,
                        null,
                        DatasetVisibility.UNRESTRICTED));
    assertThat(countCat).isEqualTo(1);
    // 즐겨찾기만 보기여도 사용자가 없으면 거르지 않는다.
    long favNoUser =
        inA(
            () ->
                repository()
                    .count(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        null,
                        DatasetVisibility.UNRESTRICTED));
    assertThat(favNoUser).isEqualTo(6);
  }

  private List<Long> filter(
      Long categoryId,
      String storageType,
      String originType,
      String status,
      boolean favoriteOnly,
      Long securityLevelId) {
    return inA(
        () ->
            repository()
                .findAll(
                    categoryId,
                    storageType,
                    originType,
                    null,
                    0,
                    100,
                    userA,
                    status,
                    favoriteOnly,
                    securityLevelId,
                    DatasetVisibility.UNRESTRICTED)
                .stream()
                .map(DatasetResponse::id)
                .toList());
  }

  @Test
  @DisplayName("목록 가시성·다른 테넌트 — 볼 수 없는 등급은 목록·건수 모두에서 빠지고 행 내용(태그·즐겨찾기·등급)이 채워진다")
  void findAll_visibilityAndRowContent() {
    long secret = fx.createSecurityLevel(tenantA, 5, prefix + "-secret");
    long open = fx.createDataset(tenantA, prefix + " 공개");
    long hidden = fx.createDataset(tenantA, prefix + " 비밀");
    fx.setDatasetField(hidden, "security_level_id", secret);
    fx.createDataset(tenantB, prefix + " 남의");
    fx.addTag(open, "zt");
    fx.addTag(open, "at");
    fx.addFavorite(userA, open);
    runA(() -> repository().updateStatus(open, "CERTIFIED", null, userA));

    List<DatasetResponse> visible =
        inA(
            () ->
                repository()
                    .findAll(null, null, null, null, 0, 10, userA, null, false, null, rank2()));
    assertThat(visible).extracting(DatasetResponse::id).containsExactly(open);
    DatasetResponse row = visible.get(0);
    assertThat(row.tags()).containsExactly("at", "zt");
    assertThat(row.isFavorite()).isTrue();
    assertThat(row.statusUpdatedBy()).isEqualTo("n");
    assertThat(row.securityLevel().id()).isEqualTo(fx.datasetSecurityLevel(open));
    assertThat(
            inA(
                () ->
                    repository().count(null, null, null, null, userA, null, false, null, rank2())))
        .isEqualTo(1);

    List<DatasetResponse> unrestricted =
        inA(
            () ->
                repository()
                    .findAll(
                        null,
                        null,
                        null,
                        null,
                        0,
                        10,
                        null,
                        null,
                        false,
                        null,
                        DatasetVisibility.UNRESTRICTED));
    assertThat(unrestricted).extracting(DatasetResponse::id).containsExactly(open, hidden);
    DatasetResponse secretRow = unrestricted.get(1);
    assertThat(secretRow.securityLevel().id()).isEqualTo(secret);
    assertThat(secretRow.securityLevel().rank()).isEqualTo(5);
    assertThat(secretRow.securityLevel().name()).isEqualTo(prefix + "-secret");
    assertThat(secretRow.securityLevel().allowlistRequired()).isFalse();
    assertThat(unrestricted.get(0).isFavorite()).isFalse();

    DatasetVisibility none = new DatasetVisibility(Clearance.none(userA, tenantA), null);
    assertThat(
            inA(
                () ->
                    repository()
                        .findAll(null, null, null, null, 0, 10, null, null, false, null, none)))
        .isEmpty();
    assertThat(inA(() -> repository().count(null, null, null, null, null, null, false, null, none)))
        .isZero();
  }
}
