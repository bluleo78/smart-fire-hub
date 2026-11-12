package com.smartfirehub.pipeline.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformRetries;
import com.smartfirehub.pipeline.repository.AiInferenceCacheRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;

/**
 * AiInferenceCacheRepository 의 MyBatis 구현(WD-708) — {@code JooqAiInferenceCacheRepository} 와 같은 의미.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 을 일부러 붙이지 않는다</b> — jOOQ 구현과 같다. 호출자(AiClassifyExecutor)가 캐시
 * 조회·쓰기를 각각 좁은 트랜잭션으로 감싸 그 사이 외부 호출 동안 커넥션을 쥐지 않는다. 격리 컨텍스트는 호출자 트랜잭션 안에서만 심기므로 반드시 그 안에서 부른다.
 *
 * <p>여러 건 삽입은 해시 중복을 앞의 것만 남겨 뺀 뒤 {@link InChunks#SIZE} 건씩 다건 문장 하나로 넣는다(PG 다건 ON CONFLICT DO
 * NOTHING, Tibero DUAL UNION ALL 소스 MERGE — 한 MERGE 에 같은 키 소스가 둘이면 -4008 이라 중복을 미리 뺀다). 같은 키가 같은
 * 호출에 두 번 있어도 앞의 것만 남는다(ON CONFLICT DO NOTHING 과 같다). 동시 쓰기와 부딪쳐 한 조각이 중복 키로 실패하면(Tibero MERGE — 문장
 * 단위로만 되돌린다) 그 조각만 한 건씩 다시 넣는다.
 *
 * <p>결과 JSON 이 {@link #BATCH_MAX_JSON_CHARS} 자를 넘는 건은 다건 문장에 싣지 않고 한 건씩 넣는다 — Tibero 드라이버는 긴 문자열
 * 바인드를 VARCHAR 가 아닌 LONG 형으로 보내 DUAL UNION ALL 소스의 열 형이 어긋난다(JDBC-11022 「incompatible data types」,
 * 계약 테스트 실측). 한 건 MERGE 는 값을 VALUES 에 바로 바인딩해 긴 값도 들어간다.
 */
@PlatformMyBatisRepository
public class MyBatisAiInferenceCacheRepository implements AiInferenceCacheRepository {

  /**
   * 다건 문장에 실을 결과 JSON 의 최대 글자 수. UTF-8 로 4000 바이트를 넘지 않는 보수적 상한(한 글자 최대 3 바이트, 보충 문자는 2 글자에 4
   * 바이트)이라 드라이버가 VARCHAR 로 보낸다. AI 분류 결과는 보통 이보다 훨씬 짧다.
   */
  static final int BATCH_MAX_JSON_CHARS = 1_000;

  private final AiInferenceCacheMapper mapper;

  public MyBatisAiInferenceCacheRepository(AiInferenceCacheMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<String> findResultJson(String rowHash, String promptVersion, Long tenantId) {
    return Optional.ofNullable(mapper.selectResultJson(rowHash, promptVersion, tenantId));
  }

  @Override
  public void insertIgnoringConflicts(String promptVersion, List<Entry> entries) {
    // 같은 해시(같은 호출이라 버전도 같다)는 앞의 것만 남긴다 — ON CONFLICT DO NOTHING 의 「먼저 들어간 행 유지」와 같다.
    Map<String, Entry> firstByHash = new LinkedHashMap<>();
    for (Entry entry : entries) {
      firstByHash.putIfAbsent(entry.rowHash(), entry);
    }
    List<Entry> distinct = new ArrayList<>();
    List<Entry> oversized = new ArrayList<>();
    for (Entry entry : firstByHash.values()) {
      boolean fits =
          entry.resultJson() == null || entry.resultJson().length() <= BATCH_MAX_JSON_CHARS;
      (fits ? distinct : oversized).add(entry);
    }
    // 긴 결과는 한 건씩(키가 서로 달라 순서는 결과에 영향이 없다).
    insertOneByOne(promptVersion, oversized);
    for (int from = 0; from < distinct.size(); from += InChunks.SIZE) {
      List<Entry> chunk = distinct.subList(from, Math.min(from + InChunks.SIZE, distinct.size()));
      try {
        mapper.insertIgnoringBatch(promptVersion, chunk);
      } catch (DuplicateKeyException e) {
        insertOneByOne(promptVersion, chunk);
      }
    }
  }

  /** 조각 폴백 — 한 건씩 넣고, 동시 삽입의 패자가 받는 중복 키는 한 번 다시 돌아 이미 들어간 행을 만나 건너뛴다(PG ON CONFLICT 와 같은 결과). */
  private void insertOneByOne(String promptVersion, List<Entry> chunk) {
    for (Entry entry : chunk) {
      PlatformRetries.runOnceOnDuplicateKey(
          () -> mapper.insertIgnoring(entry.rowHash(), promptVersion, entry.resultJson()));
    }
  }
}
