package com.smartfirehub.settings.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.settings.dto.SettingResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettingsRepository {

  private final DSLContext dsl;

  private static final Table<?> SYSTEM_SETTINGS = table(name("system_settings"));
  private static final Field<String> KEY = field(name("system_settings", "key"), String.class);
  private static final Field<String> VALUE = field(name("system_settings", "value"), String.class);
  private static final Field<String> DESCRIPTION =
      field(name("system_settings", "description"), String.class);
  private static final Field<LocalDateTime> UPDATED_AT =
      field(name("system_settings", "updated_at"), LocalDateTime.class);
  private static final Field<Long> UPDATED_BY =
      field(name("system_settings", "updated_by"), Long.class);

  /**
   * 프리픽스에 속한 설정 행. <b>프리픽스에 마침표를 붙이지 않는다</b> — 예: {@code "ai"}(O),
   * {@code "ai."}(X). 이 메서드가 스스로 {@code prefix + ".%"} 를 만들기 때문에 마침표를 붙이면
   * {@code "ai..%"} 가 되어 <b>아무 행도 매칭하지 않고, 예외도 없이 빈 목록</b>을 돌려준다.
   * 실제로 {@code ProactiveJobAsyncRunner} 가 이 함정에 빠져 저장된 {@code ai.agent_type} 을 한
   * 번도 읽지 못했다(P7-b Task 8 에서 수정).
   */
  public List<SettingResponse> findByPrefix(String prefix) {
    return dsl.select(KEY, VALUE, DESCRIPTION, UPDATED_AT)
        .from(SYSTEM_SETTINGS)
        .where(KEY.like(LikePatternUtils.escape(prefix) + ".%", '\\'))
        .orderBy(KEY)
        .fetch(
            r ->
                new SettingResponse(
                    r.get(KEY), r.get(VALUE), r.get(DESCRIPTION), r.get(UPDATED_AT)));
  }

  /**
   * 전체 설정 행.
   *
   * <p>{@link #findByPrefix} 로는 대신할 수 없다 — 그것은 {@code prefix + ".%"} 패턴을 쓰므로 빈
   * 프리픽스가 아무것도 매칭하지 않는다. 운영자 평면이 18키 전체를 한 번에 보여 주기 위해 필요하다.
   */
  public List<SettingResponse> findAll() {
    return dsl.select(KEY, VALUE, DESCRIPTION, UPDATED_AT)
        .from(SYSTEM_SETTINGS)
        .orderBy(KEY)
        .fetch(
            r ->
                new SettingResponse(
                    r.get(KEY), r.get(VALUE), r.get(DESCRIPTION), r.get(UPDATED_AT)));
  }

  public Optional<String> getValue(String key) {
    return dsl.select(VALUE).from(SYSTEM_SETTINGS).where(KEY.eq(key)).fetchOptional(VALUE);
  }

  /**
   * 플랫폼 기본값을 저장한다. <b>UPDATE 가 아니라 upsert 다.</b>
   *
   * <p>이전에는 {@code UPDATE ... WHERE key = ?} 였다. 그러면 <b>행이 없는 키를 쓰면 0행이 갱신되고,
   * 예외도 없이 성공으로 끝난다</b> — 호출부는 {@code updatePlatformSettings} 를 지나 204 를 돌려주고
   * 운영자는 저장됐다고 믿는다. 실제로 {@code ai.session_max_tokens} 가 이 상태였다: 화이트리스트
   * ({@code ALLOWED_AI_KEYS})에도 있고 값 검증도 통과하지만 어떤 마이그레이션도 시드하지 않아
   * <b>플랫폼 운영자가 영원히 설정할 수 없는 키</b>였다({@code getAll} 도 {@code system_settings} 를
   * 읽으므로 목록에 나타나지도 않았다).
   *
   * <p>시드 행을 하나 추가하는 것으로도 그 키는 고쳐지지만, 그러면 <b>다음에 화이트리스트에 키를
   * 추가하면서 시드를 잊는 사람</b>이 같은 함정에 다시 빠진다. 그래서 "쓸 수 있는 키인가"의 판단을
   * 시드 행 존재 여부가 아니라 {@code ALLOWED_*} 화이트리스트 한 곳에만 두도록 저장소 쪽을 고쳤다.
   * 임의의 키가 생기는 것은 아니다 — 화이트리스트 밖의 키는 여기 도달하기 전에 거부된다.
   */
  public void updateSettings(Map<String, String> settings, Long userId) {
    dsl.transaction(
        tx -> {
          var ctx = tx.dsl();
          for (var entry : settings.entrySet()) {
            ctx.insertInto(SYSTEM_SETTINGS)
                .set(KEY, entry.getKey())
                .set(VALUE, entry.getValue())
                .set(UPDATED_AT, currentLocalDateTime())
                .set(UPDATED_BY, userId)
                .onConflict(KEY)
                .doUpdate()
                .set(VALUE, entry.getValue())
                .set(UPDATED_AT, currentLocalDateTime())
                .set(UPDATED_BY, userId)
                .execute();
          }
        });
  }
}
