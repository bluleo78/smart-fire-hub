package com.smartfirehub.settings.repository.jooq;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.settings.repository.SettingsRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/** SettingsRepository 의 jOOQ(PostgreSQL) 구현 — WD-485. */
@Repository
@ConditionalOnPostgresPlatformDb
@RequiredArgsConstructor
public class JooqSettingsRepository implements SettingsRepository {

  private final DSLContext dsl;

  private static final Table<?> SYSTEM_SETTINGS = table(name("system_settings"));
  private static final Field<String> KEY = field(name("system_settings", "key"), String.class);
  private static final Field<String> VALUE = field(name("system_settings", "value"), String.class);
  private static final Field<String> DESCRIPTION =
      field(name("system_settings", "description"), String.class);
  private static final Field<LocalDateTime> UPDATED_AT =
      field(name("system_settings", "updated_at"), LocalDateTime.class);

  @Override
  public List<SettingResponse> findByPrefix(String prefix) {
    return dsl.select(KEY, VALUE, DESCRIPTION, UPDATED_AT)
        .from(SYSTEM_SETTINGS)
        .where(KEY.like(LikePatternUtils.settingKeyPrefixPattern(prefix), '\\'))
        .orderBy(KEY)
        .fetch(
            r ->
                new SettingResponse(
                    r.get(KEY), r.get(VALUE), r.get(DESCRIPTION), r.get(UPDATED_AT)));
  }

  @Override
  public Optional<String> getValue(String key) {
    return dsl.select(VALUE).from(SYSTEM_SETTINGS).where(KEY.eq(key)).fetchOptional(VALUE);
  }
}
