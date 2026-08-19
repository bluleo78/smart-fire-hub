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

  public void updateSettings(Map<String, String> settings, Long userId) {
    dsl.transaction(
        tx -> {
          var ctx = tx.dsl();
          for (var entry : settings.entrySet()) {
            ctx.update(SYSTEM_SETTINGS)
                .set(VALUE, entry.getValue())
                .set(UPDATED_AT, currentLocalDateTime())
                .set(UPDATED_BY, userId)
                .where(KEY.eq(entry.getKey()))
                .execute();
          }
        });
  }
}
