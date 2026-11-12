package com.smartfirehub.user.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

/**
 * 사용자·역할·권한·테넌트·멤버십·설정 계약 테스트의 DB 픽스처 seam(WD-706). 같은 시나리오를 PG(jOOQ 평문 SQL)와 Tibero(소유자 계정 JDBC)에서
 * 돌리려고 픽스처만 갈아 끼운다.
 *
 * <p>모든 구현은 만든 행을 기억했다가 {@link #cleanup()} 에서 지운다. 리포지토리 호출은 {@link #inTenant}·{@link #noTenant} 로
 * 감싼다 — PG 는 테넌트 트랜잭션, Tibero 는 {@code TenantContext}(리포지토리 프록시가 호출마다 트랜잭션을 연다).
 */
public interface AccessFixtures {

  /** 보안 등급 기본값이 있는 테스트 테넌트 하나. */
  long createTenant();

  /** 전역 사용자(이메일이 null 일 수 있다). */
  long createUser(String username, String email);

  /** 리포지토리로 만든 사용자도 정리 대상에 올린다. */
  void trackUser(long userId);

  /** 전역 사용자의 계정 활성 플래그를 직접 바꾼다. */
  void setUserActive(long userId, boolean active);

  /** 멤버십(status 는 ACTIVE·SUSPENDED). */
  void createMembership(long userId, long tenantId, String status);

  /** 멤버십 라벨을 바꾼다(OWNER·ADMIN·MEMBER) — 프로비저닝의 OWNER→ADMIN 배정 재현용. */
  void setMembershipRole(long userId, long tenantId, String role);

  /** 그 테넌트의 역할 하나(설명은 null 이 아니어야 한다). */
  long createRole(long tenantId, String name, String description);

  /** 사용자에게 역할을 배정한다(그 역할의 테넌트로). */
  void grantRole(long tenantId, long userId, long roleId);

  /** 역할에 권한을 붙인다. */
  void grantPermission(long tenantId, long roleId, long permissionId);

  /** 전역 권한 카탈로그 행 하나. */
  long createPermission(String code, String category, String description);

  /** 역할 행의 수정 시각을 비운다(수정 시각이 비어 있는 옛 행 재현). */
  void clearRoleUpdatedAt(long tenantId, long roleId);

  /** 역할에 붙은 권한 id(오름차순) — 소유자 시점의 실제 행. */
  List<Long> permissionIdsOfRole(long tenantId, long roleId);

  /** 테넌트 상태를 바꾼다(ACTIVE·SUSPENDED). */
  void setTenantStatus(long tenantId, String status);

  /** 테넌트 slug(소유자 시점). */
  String tenantSlug(long tenantId);

  /** 테넌트 이름(소유자 시점). */
  String tenantName(long tenantId);

  /** 테넌트 기본값 시드 루틴을 픽스처로 부른다(PG 함수, Tibero FH_TENANT 프로시저) — 시스템 역할이 필요한 시나리오용. */
  void provisionDefaults(long tenantId);

  /** 그 테넌트의 역할 id(이름으로). 없으면 예외. */
  long roleIdByName(long tenantId, String name);

  /** 그 테넌트의 시스템 역할 이름(오름차순). */
  List<String> systemRoleNames(long tenantId);

  /** 그 테넌트에서 사용자에게 붙은 역할 수. */
  int countUserRoles(long tenantId, long userId);

  /**
   * 그 테넌트의 행 수. table 은 시험 코드의 고정 이름만 넘긴다(role·role_permission·security_level·dataset_category·
   * report_template·tenant_settings).
   */
  int countTenantRows(String table, long tenantId);

  /** 전역 system_settings 행 하나(정리 대상에 올린다). */
  void insertSystemSetting(String key, String value, String description, LocalDateTime updatedAt);

  /** tenant_settings 행의 수정자(행이 없으면 예외). */
  Long tenantSettingUpdatedBy(long tenantId, String key);

  /** 리포지토리로 만든 테넌트도 정리 대상에 올린다. */
  void trackTenant(long tenantId);

  /** 테넌트 생성 시각을 직접 바꾼다(시각 왕복·정렬 재현용, 저장 TZ 벽시계). */
  void setTenantCreatedAt(long tenantId, LocalDateTime createdAt);

  /** 사용자 표시 이름을 바꾼다(검색 정렬 재현용). */
  void setUserName(long userId, String name);

  /** 사용자 생성 시각을 직접 바꾼다(최신순 정렬·시각 왕복 재현용, 저장 TZ 벽시계). */
  void setUserCreatedAt(long userId, LocalDateTime createdAt);

  /** 플랫폼(운영자) 역할 하나 — 이름은 전역 유일이라 호출자가 접두를 붙인다. 정리 대상에 올린다. */
  long createPlatformRole(String name);

  /** 플랫폼 역할에 권한을 붙인다. */
  void grantPlatformPermission(long platformRoleId, long permissionId);

  /** 사용자에게 플랫폼 역할을 배정한다. */
  void grantPlatformRole(long userId, long platformRoleId);

  /** 그 테넌트의 보안 등급 하나(허용 목록 불필요) — 가시성 시험용. 테넌트 정리 때 함께 지운다. */
  long createSecurityLevel(long tenantId, int rank, String name);

  /**
   * 그 테넌트의 데이터셋 카탈로그 행 하나(감사 로그 가시성 시험용, 실제 데이터 테이블은 만들지 않는다). securityLevelId 가 null 이면 테넌트 기본 등급이
   * 채워진다. 테넌트 정리 때 함께 지운다.
   */
  long createDataset(long tenantId, long createdBy, String tableName, Long securityLevelId);

  /**
   * 데이터셋 카탈로그 행 하나(묶음 ② 데이터셋 리포지토리 계약용, WD-707). 표시 이름은 그대로 넣고(검색 시험이 한글·대소문자·{@code %}·{@code _} 를
   * 넣는다), table_name 은 (테넌트, table_name) 유일 색인과 겹치지 않게 무작위 ASCII 로 만든다. 만든 이는 픽스처가 처음 한 번 만드는 사용자,
   * 보안 등급은 테넌트 기본 등급. 테넌트 정리 때 함께 지운다.
   */
  long createDataset(long tenantId, String name);

  /** 그 테넌트의 데이터셋 카테고리 하나(설명 없음). 테넌트 정리 때 함께 지운다(데이터셋을 먼저 지운 뒤). */
  long createCategory(long tenantId, String name);

  /** 데이터셋을 카테고리에 연결한다(소유자 시점 직접 갱신, WD-707). */
  void assignCategory(long datasetId, long categoryId);

  /**
   * 데이터셋의 컬럼 하나(data_type TEXT). 테넌트는 데이터셋 행에서 읽는다. 데이터셋을 지우면 FK CASCADE 로 함께 사라진다.
   *
   * @param order column_order
   */
  long createColumn(long datasetId, String name, int order);

  /** 컬럼의 표시 이름·설명을 바꾼다(소유자 시점, null 허용 — 데이터셋 검색·스키마 메타 시험용, WD-707). */
  void setColumnText(long columnId, String displayName, String description);

  /**
   * 데이터셋 행의 열 하나를 직접 바꾼다(소유자 시점, WD-707). column 은 시험 코드의 고정 이름만 — description·storage_type·
   * origin_type·status·status_note·security_level_id·source_pipeline_step_id(WD-711)·updated_at 만
   * 허용한다. value 가 null 이면 NULL 로 비운다.
   */
  void setDatasetField(long datasetId, String column, Object value);

  /** 데이터셋의 보안 등급 id(소유자 시점). */
  long datasetSecurityLevel(long datasetId);

  /** 데이터셋에 태그 하나를 붙인다(만든 이 없음). 데이터셋을 지우면 FK CASCADE 로 함께 사라진다. */
  void addTag(long datasetId, String tagName);

  /** 사용자의 데이터셋 즐겨찾기 하나. 데이터셋을 지우면 FK CASCADE 로 함께 사라진다. */
  void addFavorite(long userId, long datasetId);

  /** 이름·활성 여부를 정한 파이프라인 하나(만든 이는 픽스처 사용자, WD-707 데이터셋 참조 계약용). 테넌트 정리 때 지운다. */
  long createPipeline(long tenantId, String name, boolean active);

  /**
   * 파이프라인 스텝 하나(테넌트는 파이프라인 행에서 읽는다, WD-707). 이름은 파이프라인 안에서 유일해야 한다. 파이프라인을 지우면 FK CASCADE 로 함께
   * 사라진다.
   *
   * @param scriptType SQL·PYTHON 등
   * @param scriptContent null 허용
   * @param outputDatasetId null 허용
   * @param loadStrategy REPLACE·APPEND·MERGE
   */
  long createStep(
      long pipelineId,
      String name,
      String scriptType,
      String scriptContent,
      Long outputDatasetId,
      String loadStrategy);

  /** 스텝 입력 데이터셋 한 줄(pipeline_step_input). 스텝을 지우면 FK CASCADE 로 함께 사라진다. */
  void addStepInput(long stepId, long datasetId);

  /** 파이프라인 트리거 하나(config 는 JSON 문자열 그대로, 만든 이는 픽스처 사용자). 파이프라인을 지우면 FK CASCADE 로 함께 사라진다. */
  long createTrigger(long pipelineId, String name, String triggerType, String configJson);

  /** 저장 쿼리 하나(datasetId null 허용, 만든 이는 픽스처 사용자). 테넌트 정리 때 지운다(차트·위젯은 FK CASCADE). */
  long createSavedQuery(long tenantId, Long datasetId, String sqlText);

  /** 저장 쿼리를 쓰는 BAR 차트 하나(테넌트는 저장 쿼리 행에서 읽는다). 저장 쿼리를 지우면 FK CASCADE 로 함께 사라진다. */
  long createChart(long savedQueryId);

  /** 대시보드 하나(만든 이·공유 여부 지정). 테넌트 정리 때 지운다(위젯은 FK CASCADE). */
  long createDashboard(long tenantId, String name, long createdBy, boolean shared);

  /** 대시보드에 차트 위젯 하나를 붙인다(테넌트는 대시보드 행에서 읽는다). */
  void addWidget(long dashboardId, long chartId);

  /**
   * dataset_search_index 행의 config_hash 저장값 그대로(소유자 시점, WD-707) — 빈 해시가 PG 는 {@code ""}, Tibero 는
   * NULL 로 저장되는지 확인용(리포지토리 읽기는 둘 다 {@code ""} 로 맞춘다).
   */
  String searchIndexConfigHash(long datasetId);

  /**
   * dataset_search_index 행의 임대 만료 시각을 DB 시계 + seconds 초로 직접 맞춘다(소유자 시점, WD-707). 막 만료(음수)·아직 유효(양수)
   * 경계를 벽시계 대기 없이 만들려고 쓴다.
   */
  void setSearchLeaseFromDbNow(long datasetId, int seconds);

  /** 임대 만료 시각 − DB 현재 시각(소유자 시점 한 문장으로 둘 다 읽어 자바에서 뺀다). 임대가 비어 있으면 null. */
  java.time.Duration searchLeaseRemaining(long datasetId);

  /** 보안 등급의 ai_policy 를 바꾼다(소유자 시점, WD-707 — 임베딩 등급 허용 술어 시험용). 값은 ALL·SELF_HOSTED_ONLY·DENY. */
  void setSecurityLevelAiPolicy(long levelId, String aiPolicy);

  /** dataset_embedding 행의 source_text 저장값(소유자 시점, WD-707). 행이 없으면 null. */
  String embeddingSourceText(long datasetId);

  /** dataset_embedding 행의 tenant_id(소유자 시점) — 새 행에 컨텍스트 DEFAULT 가 채워졌는지 확인용. 행이 없으면 null. */
  Long embeddingTenantId(long datasetId);

  /** dataset_embedding.updated_at 을 지정 시각으로 되돌린다(소유자 시점) — upsert 가 갱신 시각을 새로 쓰는지 보려고. */
  void setEmbeddingUpdatedAt(long datasetId, LocalDateTime updatedAt);

  /** dataset_embedding.updated_at(소유자 시점, 드라이버가 돌려준 벽시계 그대로). */
  LocalDateTime embeddingUpdatedAt(long datasetId);

  /**
   * document_file 한 행을 소유자 시점으로 만든다(WD-712 — 청크 시험용). tenant_id 는 직접 넣고 올린 이는 픽스처 전용 사용자다. 데이터셋을
   * 지우면 FK CASCADE 로 파일·청크가 함께 사라진다.
   *
   * @param status PENDING·PARSING·COMPLETED 등
   */
  long createDocumentFile(long tenantId, long datasetId, String name, String status);

  /** 그 문서의 document_chunk 저장 행(소유자 시점, chunk_index 오름차순) — 청크 적재 왕복·테넌트 DEFAULT 확인용. */
  List<ChunkRow> chunkRows(long documentFileId);

  /** document_chunk 한 행의 저장값(소유자 시점). */
  record ChunkRow(
      long datasetId, int chunkIndex, String content, Integer tokenCount, long tenantId) {}

  /**
   * 감사 로그 한 행을 시각을 정해 직접 넣는다(정렬·기간·가시성 시험용). tenantId 가 null 이면 테넌트 없는(플랫폼) 행. 정리 대상에 올린다.
   *
   * @param actionTime 저장 TZ 벽시계 action_time
   * @param metadataJson null 이면 NULL
   */
  long insertAuditLog(
      Long tenantId,
      String username,
      String actionType,
      String resource,
      String resourceId,
      String description,
      LocalDateTime actionTime,
      String metadataJson);

  /** 리포지토리로 만든 감사 로그도 정리 대상에 올린다(테넌트 없는 행은 테넌트 정리로 지워지지 않는다). */
  void trackAuditLog(long id);

  /** 그 id 의 감사 로그가 지정 tenant_id(null 이면 NULL)로 저장돼 있으면 1, 아니면 0 — 정책을 우회한 실제 행 기준. */
  int countAuditLog(Long tenantId, long id);

  /** 비동기 작업 행의 단계·생성·갱신 시각을 직접 바꾼다(정체·삭제 기준 시험용, 저장 TZ 벽시계). */
  void setAsyncJobState(
      long tenantId, String id, String stage, LocalDateTime createdAt, LocalDateTime updatedAt);

  /** 그 id 의 비동기 작업이 지정 테넌트로 저장돼 있으면 1, 아니면 0 — 정책을 우회한 실제 행 기준. */
  int countAsyncJob(long tenantId, String id);

  /** 그 id 의 비동기 작업 행 수 — 테넌트와 무관하게 정책을 우회해 센다(거절된 쓰기가 아무 행도 남기지 않았는지 확인용). */
  int countAsyncJobAnyTenant(String id);

  /** tenant_data_store 행의 updated_at 을 직접 바꾼다(소유자 시점, 저장 TZ 벽시계, WD-707). */
  void setDataStoreUpdatedAt(long tenantId, LocalDateTime updatedAt);

  /** tenant_data_store 행의 updated_at(소유자 시점). */
  LocalDateTime dataStoreUpdatedAt(long tenantId);

  /** tenant_data_store 행의 host 열(소유자 시점) — 다른 테넌트 호출이 행을 건드리지 않았는지 확인용. */
  String dataStoreHost(long tenantId);

  /**
   * 별도 소유자 커넥션이 그 테넌트의 tenant_data_store 행을 FOR UPDATE 로 쥔 채 call 을 실행하고 끝나면 롤백한다 — 행 잠금 대기 시간 제한
   * 시험용(WD-707).
   */
  <T> T withDataStoreRowLocked(long tenantId, Supplier<T> call);

  /**
   * 다른 소유자 커넥션이 그 테넌트의 tenant_data_store 행을 FOR UPDATE NOWAIT 로 잡으려다 못 잡으면 true — 지금 다른 트랜잭션이 그 행을
   * 잠갔다는 뜻이다(WD-707).
   */
  boolean isDataStoreRowLockedByOther(long tenantId);

  /** 지정 테넌트(null 이면 테넌트 없음) 컨텍스트로 리포지토리 호출을 실행한다. */
  <T> T inTenant(Long tenantId, Supplier<T> call);

  /** 테넌트 컨텍스트를 비우고 트랜잭션 없이 실행한다 — 로그인 경로 재현. */
  <T> T noTenant(Supplier<T> call);

  /** AI 세션의 수정 시각을 직접 바꾼다(소유자 시점, 목록 정렬·페이지 재현용, 저장 TZ 벽시계 — WD-712). */
  void setAiSessionUpdatedAt(long sessionId, LocalDateTime updatedAt);

  /** 소유자 시점에서 본 AI 세션의 제목·수정 시각 — 리포지토리 쓰기 결과 확인용(WD-712). 행이 없으면 예외. */
  String aiSessionTitle(long sessionId);

  /**
   * embedding_reembed_state 행의 임대 만료 시각을 DB 시계 + seconds 초로 직접 맞춘다(소유자 시점, WD-712). 막 만료(음수)·아직
   * 유효(양수) 경계를 벽시계 대기 없이 만들려고 쓴다.
   */
  void setReembedLeaseFromDbNow(long tenantId, int seconds);

  /** 재임베딩 임대 만료 시각 − DB 현재 시각(소유자 시점 한 문장으로 둘 다 읽어 자바에서 뺀다, WD-712). 임대가 비어 있으면 null, 행이 없으면 예외. */
  java.time.Duration reembedLeaseRemaining(long tenantId);

  /**
   * embedding_reembed_state 행의 updated_at 을 DB 시계 + seconds 초로 직접 맞춘다(소유자 시점, 저장 기준 그대로 — WD-712).
   */
  void setReembedUpdatedAtFromDbNow(long tenantId, int seconds);

  /**
   * tenant_settings 한 행을 넣거나 바꾸고 updated_at 을 DB 시계 + seconds 초로 맞춘다(소유자 시점, WD-712). 운영 쓰기와 같은 저장
   * 기준이다 — PG 는 LOCALTIMESTAMP(jOOQ currentLocalDateTime), Tibero 는 UTC 벽시계(MyBatis dbNow). Tibero
   * 열 DEFAULT(LOCALTIMESTAMP)에는 기대지 않는다. 테넌트 정리 때 함께 지운다.
   */
  void putTenantSettingFromDbNow(long tenantId, String key, String value, int seconds);

  /** DB 시계의 현재 순간(소유자 시점, WD-712) — JVM 시간대와 무관하게 리포지토리가 읽은 시각과 비교하려고 쓴다. */
  java.time.Instant dbNow();

  /** 이 DB 가 빈 문자열을 NULL 로 저장하는가(Tibero true, PG false). */
  boolean emptyStringIsNull();

  // ---- 묶음 ③·⑥ 픽스처(WD-711) ----

  /**
   * 데이터셋 접근 허용 한 줄(dataset_access_grant). roleId·userId 는 정확히 하나만 채운다(사용자 XOR 역할). 테넌트 정리 때 함께 지운다.
   */
  void grantDatasetAccess(long tenantId, long datasetId, Long roleId, Long userId);

  /** 그 테넌트의 온톨로지 하나(domain = name, 나머지는 열 기본값). 테넌트 정리 때 함께 지운다(하위 행은 FK CASCADE). */
  long createOntology(long tenantId, String name);

  /**
   * 온톨로지 수정 시각을 지정 순간으로 직접 바꾼다(소유자 시점, WD-711 — updated_at 왕복·갱신 시험용). PG 는 timestamptz, Tibero 는
   * UTC 벽시계로 쓴다.
   */
  void setOntologyUpdatedAt(long ontologyId, java.time.Instant at);

  /**
   * 데이터셋↔온톨로지 연결 한 줄(dataset_ontology)을 소유자 시점으로 직접 넣는다(WD-711 — 리포지토리가 직접 쓰지 않은 행을 읽는지·참조 수 시험용).
   * 온톨로지를 지우면 FK CASCADE 로 함께 사라진다.
   */
  void bindDatasetOntology(long tenantId, long datasetId, long ontologyId);

  /** 데이터셋 매핑 한 줄(dataset_mapping, spec 은 빈 JSON 객체)을 소유자 시점으로 넣는다(WD-711). 온톨로지를 지우면 FK CASCADE. */
  void createDatasetMapping(long tenantId, long datasetId, long ontologyId);

  /** 그 테넌트·데이터셋의 dataset_ontology 저장 행(소유자 시점, 정책 우회) — 업서트가 한 행만 남겼는지·값 확인용(WD-711). */
  List<DatasetOntologyRow> datasetOntologyRows(long tenantId, long datasetId);

  /** dataset_ontology 의 bound_at 을 지정 순간으로 되돌린다(소유자 시점, WD-711 — 재바인딩이 시각을 새로 쓰는지 보려고). */
  void setDatasetOntologyBoundAt(long tenantId, long datasetId, java.time.Instant at);

  /** dataset_ontology 한 행의 저장값(소유자 시점). boundAt 은 UTC 순간. */
  record DatasetOntologyRow(long ontologyId, Long boundBy, java.time.Instant boundAt) {}

  /** 역할의 최대 열람 등급(role.max_security_level_id)을 바꾼다(소유자 시점, WD-711 — 자격 원자료 시험용). */
  void setRoleMaxLevel(long roleId, long levelId);

  /**
   * 보안 등급의 허용 목록 필요·ADMIN 우회 플래그를 바꾼다(소유자 시점, WD-711 — 접근 사실·허용 목록 판정 시험용). Tibero 불리언 열은 NUMBER(1)
   * 이라 구현이 1/0 으로 쓴다.
   */
  void setSecurityLevelFlags(long levelId, boolean allowlistRequired, boolean adminBypass);

  /**
   * 보안 등급의 [created_at, updated_at] 을 저장된 벽시계 그대로 읽는다(소유자 시점, WD-711 — 삽입 문장이 DB 시계로 시각을 쓰는지 확인용).
   * {@link #dbWallClockNow()} 와 비교한다.
   */
  List<LocalDateTime> securityLevelCreatedAndUpdatedAt(long levelId);

  /**
   * timestamp(시간대 없는) 열의 저장 기준 벽시계로 본 DB 현재 시각(WD-711). PG 는 LOCALTIMESTAMP(세션 TimeZone 벽시계 — now()
   * 를 timestamp 열에 넣은 값과 같다), Tibero 는 UTC 벽시계(MyBatis dbNow). 열 DEFAULT·dbNow 로 넣은 시각이 맞는지 볼 때 쓴다.
   */
  LocalDateTime dbWallClockNow();

  // ---- 묶음 ⑥ Task 6 픽스처(WD-711: 그래프 출처·적재 이력) ----

  /** 데이터셋 행을 소유자 시점으로 지운다(삭제된 데이터셋 출처 재현용 — 출처 행은 데이터셋 FK 가 없어 남는다). */
  void deleteDataset(long datasetId);

  /** 온톨로지의 schema_version 을 직접 바꾼다(소유자 시점 — 낡음 판정 시험용). */
  void setOntologySchemaVersion(long ontologyId, int version);

  /** 지식그래프 출처 한 줄(graph_ontology_source)을 소유자 시점으로 직접 넣는다. 온톨로지를 지우면 FK CASCADE 로 함께 사라진다. */
  void insertGraphSource(long tenantId, long ontologyId, long datasetId);

  /** 그 테넌트의 출처 행 수(소유자 시점, 정책 우회) — 멱등 기록이 한 행만 남겼는지 확인용. */
  int countGraphSources(long tenantId, long ontologyId, long datasetId);

  /** 적재 이력 한 행을 시각을 정해 소유자 시점으로 직접 넣는다(최신·동시각 판정 시험용, 저장 TZ 벽시계). 데이터셋 FK 가 없다. 테넌트 정리 때 함께 지운다. */
  long insertGraphIngest(
      long tenantId, long datasetId, int schemaVersion, LocalDateTime ingestedAt);

  // ---- 묶음 ③ Task 8 픽스처(WD-708: AI 추론 캐시·트리거 이벤트·파이프라인) ----

  /**
   * 파이프라인 실행 한 행(상태 지정, 실행자는 픽스처 사용자, 하트비트·시작 시각 NULL). 트리거 이벤트의 execution_id 용이며 파이프라인을 지우면 FK
   * CASCADE 로 사라진다. 5 인자판에 맡긴다.
   */
  default long createExecution(long pipelineId, String status) {
    return createExecution(pipelineId, datasetCreator(), status, null, null);
  }

  /** 픽스처가 만든 이로 쓰는 사용자 id — 처음 부를 때 한 번 만들고 정리 대상에 올린다. */
  long datasetCreator();

  /**
   * 트리거 이벤트 한 행을 시각을 정해 소유자 시점으로 직접 넣는다(정렬·기간 삭제 시험용, 저장 TZ 벽시계). pipeline_id 는 트리거 행에서, 테넌트는 파이프라인
   * 행에서 읽는다. executionId·detailJson 은 null 허용. 파이프라인을 지우면 FK CASCADE 로 사라진다.
   */
  long insertTriggerEvent(
      long triggerId,
      Long executionId,
      String eventType,
      String detailJson,
      LocalDateTime createdAt);

  /** 그 트리거의 이벤트 생성 시각(소유자 시점, id 오름차순) — 쓰기 문장이 DB 시계로 시각을 쓰는지 확인용. */
  List<LocalDateTime> triggerEventCreatedAts(long triggerId);

  /** 그 파이프라인의 이벤트 행 수(소유자 시점, 정책 우회). */
  int countTriggerEvents(long pipelineId);

  /** 트리거의 사용 여부를 직접 바꾼다(소유자 시점 — 활성 트리거 수 시험용). */
  void setTriggerEnabled(long triggerId, boolean enabled);

  /** 파이프라인 수정 시각을 직접 바꾼다(소유자 시점, null 이면 NULL — 수정 시각이 빈 옛 행 재현용). */
  void setPipelineUpdatedAt(long pipelineId, LocalDateTime updatedAt);

  /** 그 테넌트로 저장된 AI 추론 캐시 행 수(소유자 시점, 정책 우회) — 같은 키 두 번이 한 행이고 테넌트 DEFAULT 가 채워졌는지 확인용. */
  int countAiInferenceCache(long tenantId, String rowHash, String promptVersion);

  /** 그 테넌트의 AI 추론 캐시 행 created_at(소유자 시점, 저장 TZ 벽시계). 행이 없으면 null. */
  LocalDateTime aiInferenceCacheCreatedAt(long tenantId, String rowHash, String promptVersion);

  // ---- 묶음 ③ Task 9 픽스처(WD-708: API 연결·데이터셋 매핑) ----

  /** API 연결 한 행의 저장값(소유자 시점) — 리포지토리를 거치지 않고 실제 행을 본다. 시각은 저장 TZ 벽시계. */
  record ApiConnectionStored(
      String baseUrl,
      String healthCheckPath,
      String lastStatus,
      Long lastLatencyMs,
      String lastErrorMessage,
      String authConfig,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      LocalDateTime lastCheckedAt) {}

  /** API 연결 저장 행(소유자 시점, 정책 우회). 행이 없으면 null. */
  ApiConnectionStored apiConnectionStored(long id);

  /** API 연결의 수정 시각을 직접 바꾼다(소유자 시점, 저장 TZ 벽시계 — 갱신이 시각을 새로 쓰는지 보려고). */
  void setApiConnectionUpdatedAt(long id, LocalDateTime at);

  /**
   * API_CALL 스텝 하나(api_connection_id 로 연결을 참조, 테넌트는 파이프라인 행에서 읽는다). 파이프라인을 지우면 FK CASCADE 로 함께
   * 사라진다. 연결 삭제 전에 파이프라인이 먼저 지워지도록 정리 순서가 맞춰져 있다.
   */
  long createApiCallStep(long pipelineId, String name, long apiConnectionId);

  /** dataset_mapping 한 행의 저장값(소유자 시점). updatedAt 은 UTC 순간. */
  record DatasetMappingStored(
      long ontologyId, String status, Long updatedBy, java.time.Instant updatedAt) {}

  /** 그 테넌트·데이터셋의 dataset_mapping 저장 행(소유자 시점, 정책 우회) — 업서트가 한 행만 남겼는지·값 확인용. */
  List<DatasetMappingStored> datasetMappingRows(long tenantId, long datasetId);

  /** dataset_mapping 의 updated_at 을 지정 순간으로 되돌린다(소유자 시점 — 갱신이 시각을 새로 쓰는지 보려고). */
  void setDatasetMappingUpdatedAt(long tenantId, long datasetId, java.time.Instant at);

  // ---- 묶음 ③ Task 10 픽스처(WD-708: 파이프라인 스텝) ----

  /** 스텝 생성 시각(소유자 시점, 저장 TZ 벽시계) — 저장 문장이 DB 시계로 시각을 쓰는지·수정이 유지하는지 확인용. */
  LocalDateTime stepCreatedAt(long stepId);

  // ---- 묶음 ③ Task 11 픽스처(WD-708: 파이프라인 실행) ----

  /** setExecutionField 가 받는 열 — 열 이름을 SQL 에 끼워 넣으므로 허용 목록으로 막는다(PG·Tibero 픽스처 공용). */
  static String executionField(String column) {
    if (!java.util.Set.of(
            "created_at",
            "started_at",
            "completed_at",
            "cancel_requested_at",
            "cancel_requested_by",
            "status")
        .contains(column)) {
      throw new IllegalArgumentException("허용하지 않는 실행 열: " + column);
    }
    return column;
  }

  /**
   * 하트비트·시작 시각을 정한 파이프라인 실행 한 행(실행자는 executedBy, 테넌트는 파이프라인 행에서 읽는다, 저장 TZ 벽시계). 시각은 null 허용.
   * 파이프라인을 지우면 FK CASCADE 로 사라진다.
   */
  long createExecution(
      long pipelineId,
      long executedBy,
      String status,
      LocalDateTime heartbeatAt,
      LocalDateTime startedAt);

  /**
   * 스텝 실행 한 행(stepId·stepName·startedAt·completedAt null 허용, 테넌트는 실행 행에서 읽는다). 실행을 지우면 FK CASCADE.
   */
  long createStepExecution(
      long executionId,
      Long stepId,
      String stepName,
      String status,
      LocalDateTime startedAt,
      LocalDateTime completedAt);

  /**
   * 실행 행의 열 하나를 직접 바꾼다(소유자 시점 — 생성 시각·취소 요청 재현용). 열은 created_at·started_at·completed_at·
   * cancel_requested_at·cancel_requested_by·status 만 받는다. 시각은 LocalDateTime(저장 TZ 벽시계), null 이면
   * NULL.
   */
  void setExecutionField(long executionId, String column, Object value);

  /** 파이프라인 실행 한 행의 저장값(소유자 시점, 정책 우회). 시각은 저장 TZ 벽시계. */
  record ExecutionStored(
      long pipelineId,
      String status,
      long executedBy,
      String triggeredBy,
      Long triggerId,
      String errorMessage,
      LocalDateTime startedAt,
      LocalDateTime completedAt,
      LocalDateTime createdAt,
      LocalDateTime heartbeatAt,
      LocalDateTime cancelRequestedAt,
      Long cancelRequestedBy) {}

  /** 실행 저장 행(소유자 시점). 행이 없으면 null. */
  ExecutionStored executionStored(long executionId);

  /** 스텝 실행 한 행의 저장값(소유자 시점). injectedLastRunAt 은 UTC 순간, 나머지 시각은 저장 TZ 벽시계. */
  record StepExecutionStored(
      Long stepId,
      String stepName,
      String status,
      Integer outputRows,
      String log,
      String errorMessage,
      LocalDateTime startedAt,
      LocalDateTime completedAt,
      java.time.Instant injectedLastRunAt) {}

  /** 스텝 실행 저장 행(소유자 시점). 행이 없으면 null. */
  StepExecutionStored stepExecutionStored(long stepExecId);

  /**
   * pipeline_step_execution UPDATE 가 실패하도록 임시 트리거를 건 채 body 를 실행하고, 끝나면 반드시 지운다(소유자 DDL — 한 트랜잭션
   * 원자성 시험용). marker 가 있으면 새 error_message 가 그 값일 때, null 이면 새 상태가 SKIPPED 일 때 터진다.
   */
  void withFailingStepExecutionUpdate(String marker, Runnable body);

  // ---- 묶음 ③ Task 12 픽스처(WD-708: 트리거) ----

  /** 트리거 실행 상태(trigger_state)를 JSON 문자열 그대로 직접 바꾼다(소유자 시점 — 폴러가 남긴 스냅숏 재현용). */
  void setTriggerState(long triggerId, String stateJson);

  /** 트리거 수정 시각을 직접 바꾼다(소유자 시점, 저장 TZ 벽시계, null 이면 NULL — 수정 시각 유지·갱신 판정과 빈 옛 행 재현용). */
  void setTriggerUpdatedAt(long triggerId, LocalDateTime updatedAt);

  /** 트리거 한 행의 저장값(소유자 시점). JSON 열은 텍스트 그대로(시험이 파싱해 비교), 시각은 저장 TZ 벽시계. */
  record TriggerStored(
      String configJson,
      String stateJson,
      boolean enabled,
      String description,
      Long updatedBy,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  /** 트리거 저장 행(소유자 시점, 정책 우회 — 다른 테넌트 행이 바뀌지 않았는지 볼 때 쓴다). 행이 없으면 null. */
  TriggerStored triggerStored(long triggerId);

  // ---- 묶음 ④·⑤ 픽스처(WD-709·710: 알림·채널·선제 실행) ----

  /**
   * 슬랙 워크스페이스 하나(team_id 는 호출자가 유일하게 정한다). bot 토큰·서명 비밀은 고정 더미 값이고, revoked 면 revoked_at 이 지금으로
   * 채워진다. 테넌트 정리 때 함께 지운다.
   */
  long createSlackWorkspace(long tenantId, String teamId, boolean revoked);

  /** 선제 작업 하나(SCHEDULE, 활성, 프롬프트 더미). 테넌트 정리 때 지운다(실행 이력은 FK CASCADE). */
  long createProactiveJob(long tenantId, long userId, String name);

  /**
   * 트리거 유형·활성 여부·설정 JSON 을 정해 선제 작업을 만든다(WD-709 묶음 ④ Task 4 — 이상탐지 폴링 대상 조회 시험용). 설정은 JSON 문자열(null
   * 이면 열 기본값 {@code {}}). 테넌트 정리 때 함께 지운다(이상 이벤트·메트릭 스냅샷은 FK CASCADE).
   */
  long createProactiveJob(
      long tenantId,
      long userId,
      String name,
      boolean enabled,
      String triggerType,
      String configJson);

  /** 리포트 템플릿 하나(userId null 허용 — 시스템 템플릿). 테넌트 정리 때 지운다. */
  long createReportTemplate(long tenantId, Long userId, String name);

  /** 선제 작업 실행 한 행(상태 지정, 결과 없음). 작업을 지우면 FK CASCADE 로 함께 사라진다. */
  long createProactiveJobExecution(long tenantId, long jobId, String status);

  // ---- 묶음 ④ Task 5 픽스처(WD-709: 리포트 양식·선제 작업 저장 상태, 소유자 시점) ----

  /** 선제 작업 한 행의 저장값(소유자 시점). config 는 JSON 텍스트 그대로, 시각은 저장 TZ 벽시계. */
  record ProactiveJobStored(
      String name,
      String prompt,
      Long templateId,
      String cronExpression,
      String timezone,
      boolean enabled,
      String triggerType,
      String configJson,
      LocalDateTime lastExecutedAt,
      LocalDateTime nextExecuteAt,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  /** id 로 선제 작업 저장 행을 읽는다(정책 우회). 행이 없으면 null. */
  ProactiveJobStored proactiveJobStored(long id);

  /** 선제 작업의 수정 시각을 직접 바꾼다(소유자 시점, null 이면 NULL — 빈 옛 행 재현용). */
  void setProactiveJobUpdatedAt(long id, LocalDateTime updatedAt);

  /** 리포트 양식 한 행의 저장값(소유자 시점). sections 는 JSON 텍스트 그대로. */
  record ReportTemplateStored(
      String name,
      String description,
      String sectionsJson,
      String style,
      Long userId,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  /** id 로 리포트 양식 저장 행을 읽는다(정책 우회). 행이 없으면 null. */
  ReportTemplateStored reportTemplateStored(long id);

  /** 리포트 양식의 수정 시각을 직접 바꾼다(소유자 시점, null 이면 NULL — 빈 옛 행 재현용). */
  void setReportTemplateUpdatedAt(long id, LocalDateTime updatedAt);

  /** 리포트 양식의 sections 를 JSON 텍스트 그대로 바꾼다(소유자 시점) — 리포지토리가 쓰지 않는 원소 모양(문자열 원소 등) 재현용. */
  void setReportTemplateSectionsJson(long id, String sectionsJson);

  // ---- 묶음 ④ Task 2 픽스처(WD-709: OAuth state·채널 선호·슬랙 워크스페이스 저장 상태, 소유자 시점) ----

  /** oauth_state 한 행의 저장값(소유자 시점). 소비됐으면 consumed 가 true. */
  record OAuthStateStored(long userId, String channelType, long tenantId, boolean consumed) {}

  /** state 로 oauth_state 저장 행을 읽는다(정책 우회). 행이 없으면 null. */
  OAuthStateStored oauthStateStored(String state);

  /** 사용자 채널 선호의 저장 enabled 값(소유자 시점). 행이 없으면 null. */
  Boolean userChannelPreferenceEnabled(long tenantId, long userId, String channelType);

  /** 슬랙 워크스페이스 한 행의 저장값(소유자 시점). signingSecretEnc 는 저장된 그대로(Tibero 의 빈 값은 null). */
  record SlackWorkspaceStored(
      String teamId,
      String teamName,
      String botUserId,
      String botTokenEnc,
      String signingSecretEnc,
      Long installedByUserId,
      boolean revoked) {}

  /** id 로 슬랙 워크스페이스 저장 행을 읽는다(정책 우회). 행이 없으면 null. */
  SlackWorkspaceStored slackWorkspaceStored(long id);

  /** 슬랙 워크스페이스의 서명 비밀(암호문)을 직접 바꾼다 — 관리자가 서명 비밀을 입력한 상태 재현용. */
  void setSlackSigningSecret(long id, String enc);

  /** 슬랙 워크스페이스의 직전 서명 비밀과 만료 시각을 직접 채운다 — 서명 비밀 회전 직후 상태 재현용. */
  void setSlackPreviousSigningSecret(long id, String enc, java.time.Instant expiresAt);

  // ---- 묶음 ④ Task 6 픽스처(WD-709: 선제 작업 실행 저장 상태, 소유자 시점) ----

  /** 선제 작업 실행 한 행의 저장값(소유자 시점). result 는 JSON 텍스트 그대로, 시각은 저장 TZ 벽시계. */
  record ProactiveExecutionStored(
      long jobId,
      String status,
      LocalDateTime startedAt,
      LocalDateTime completedAt,
      String errorMessage,
      String resultJson,
      String deliveredChannels,
      Long securityLevelId,
      LocalDateTime heartbeatAt,
      LocalDateTime createdAt) {}

  /** id 로 선제 작업 실행 저장 행을 읽는다(정책 우회). 행이 없으면 null. */
  ProactiveExecutionStored proactiveExecutionStored(long id);

  /**
   * 선제 작업 실행 한 행의 열 하나를 직접 바꾼다(소유자 시점, null 이면 NULL) — 하트비트·시작·생성·완료 시각, 상태, 등급 id 를 시험 상태로 맞출 때
   * 쓴다. 열 이름은 {@link #proactiveExecutionField} 허용 목록만 받는다.
   */
  void setProactiveExecutionField(long id, String column, Object value);

  /** 선제 작업 실행의 result 를 JSON 텍스트 그대로 바꾼다(소유자 시점) — 최상위 배열처럼 리포지토리가 쓰지 않는 모양 재현용. */
  void setProactiveExecutionResultJson(long id, String resultJson);

  /** setProactiveExecutionField 가 받는 열 — 열 이름을 SQL 에 끼워 넣으므로 허용 목록으로 막는다(PG·Tibero 픽스처 공용). */
  static String proactiveExecutionField(String column) {
    if (!java.util.Set.of(
            "heartbeat_at",
            "started_at",
            "created_at",
            "completed_at",
            "status",
            "security_level_id")
        .contains(column)) {
      throw new IllegalArgumentException("허용하지 않는 선제 실행 열: " + column);
    }
    return column;
  }

  /** 만든 행을 모두 지운다. */
  void cleanup();
}
