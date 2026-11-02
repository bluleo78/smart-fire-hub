package com.smartfirehub.pipeline.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/**
 * AI 추론 캐시(ai_inference_cache) 접근 리포지토리 — AI_CLASSIFY 실행기가 행 내용 해시별 분류 결과를 조회·저장한다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 을 일부러 붙이지 않는다.</b> 호출자(AiClassifyExecutor)는 캐시 조회와 쓰기
 * <b>사이</b>에 외부 HTTP(LLM) 호출이 있어, 조회 루프·쓰기 루프만 각각 좁은 {@code TransactionTemplate} 으로 감싼다(커넥션 풀 고갈
 * 방지). 여기서 트랜잭션을 더하면 그 경계가 바뀐다. ai_inference_cache 는 RLS 대상이고 격리 GUC 는 트랜잭션 로컬이라, 이 메서드들은 반드시 호출자가
 * 연 트랜잭션 안에서 불러야 한다.
 */
@Repository
@RequiredArgsConstructor
public class AiInferenceCacheRepository {

  private static final Table<?> AI_INFERENCE_CACHE = table(name("ai_inference_cache"));
  private static final Field<String> CACHE_ROW_HASH = field(name("row_hash"), String.class);
  private static final Field<String> CACHE_PROMPT_VERSION =
      field(name("prompt_version"), String.class);
  private static final Field<JSONB> CACHE_RESULT_JSON = field(name("result_json"), JSONB.class);

  /**
   * 캐시 조회에 붙이는 테넌트 술어용 컬럼. V103 이 유니크를 {@code (tenant_id, row_hash, prompt_version)} 으로 접었으므로 캐시는
   * 테넌트별로 파티션된다(R2) — {@code row_hash} 는 분류 대상 <b>행 내용</b>의 해시라, 술어 없이 조회하면 A 테넌트 행의 존재와 추론 결과가 B
   * 테넌트의 캐시 히트로 관측된다. 정책(V104)에 앞서 쿼리 자체가 격리를 성립시킨다.
   */
  private static final Field<Long> CACHE_TENANT_ID = field(name("tenant_id"), Long.class);

  private final DSLContext dsl;

  /** 캐시에 넣을 한 건 — 행 내용 해시와 분류 결과 JSON 문자열. */
  public record Entry(String rowHash, String resultJson) {}

  /**
   * 행 해시·프롬프트 버전·테넌트로 캐시된 분류 결과 JSON 을 조회한다. 없거나 결과 컬럼이 null 이면 비어 돌아온다(= 캐시 미스).
   *
   * @return 캐시된 결과 JSON 문자열
   */
  public Optional<String> findResultJson(String rowHash, String promptVersion, Long tenantId) {
    var cached =
        dsl.select(CACHE_RESULT_JSON)
            .from(AI_INFERENCE_CACHE)
            .where(CACHE_ROW_HASH.eq(rowHash))
            .and(CACHE_PROMPT_VERSION.eq(promptVersion))
            .and(CACHE_TENANT_ID.eq(tenantId))
            .fetchOne();
    JSONB json = cached != null ? cached.get(CACHE_RESULT_JSON) : null;
    return Optional.ofNullable(json).map(JSONB::data);
  }

  /**
   * 캐시 항목들을 다중 VALUES 한 문장으로 넣는다. 이미 있는 키는 건드리지 않는다.
   *
   * <p>tenant_id 는 일부러 세팅하지 않는다 — GUC 파생 DEFAULT(V103)가 채운다. 앱이 직접 싣는 유일한 지점이 되면 GUC 와 어긋날 여지가 생기고,
   * 이 저장소의 다른 테넌트 테이블 선례(MappingRepository, DatasetOntologyRepository)와도 달라진다. {@code
   * onConflictDoNothing()} 은 conflict target 이 없는 형태라 인덱스 추론을 하지 않는다 — V103 의 유니크 접기로 깨질 지점이 아니다.
   *
   * @param promptVersion 모든 항목에 공통인 프롬프트 버전(해시)
   * @param entries 넣을 항목(비어 있으면 호출하지 않는다)
   */
  public void insertIgnoringConflicts(String promptVersion, List<Entry> entries) {
    var insert =
        dsl.insertInto(AI_INFERENCE_CACHE, CACHE_ROW_HASH, CACHE_PROMPT_VERSION, CACHE_RESULT_JSON);
    for (Entry entry : entries) {
      insert = insert.values(entry.rowHash(), promptVersion, JSONB.valueOf(entry.resultJson()));
    }
    insert.onConflictDoNothing().execute();
  }
}
