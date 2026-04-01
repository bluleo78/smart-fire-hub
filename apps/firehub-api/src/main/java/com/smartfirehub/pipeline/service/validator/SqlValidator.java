package com.smartfirehub.pipeline.service.validator;

import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SqlValidator {

  private static final Set<String> BLOCKED_KEYWORDS =
      Set.of(
          "DROP",
          "ALTER",
          "CREATE",
          "TRUNCATE",
          "GRANT",
          "REVOKE",
          "SET ROLE",
          "RESET ROLE",
          "SET SESSION AUTHORIZATION",
          "COPY",
          "\\COPY",
          "CREATE EXTENSION",
          "LOAD",
          "PG_READ_FILE",
          "PG_READ_BINARY_FILE",
          "PG_LS_DIR",
          "LO_IMPORT",
          "LO_EXPORT",
          "DBLINK",
          "DBLINK_CONNECT",
          "DO $$",
          "DO $");

  /**
   * SQL 스크립트 내용을 검증한다. 차단 패턴이 발견되면 ScriptExecutionException을 던진다. 이것은 심층 방어 레이어 — DB 역할 자체가 이러한 권한을
   * 갖지 않지만, 애플리케이션 레이어에서 차단하여 명확한 에러 메시지를 제공한다.
   */
  public void validate(String scriptContent) {
    if (scriptContent == null || scriptContent.isBlank()) {
      throw new ScriptExecutionException("SQL 스크립트가 비어 있습니다.");
    }

    String upperScript = scriptContent.toUpperCase();
    for (String keyword : BLOCKED_KEYWORDS) {
      if (upperScript.contains(keyword.toUpperCase())) {
        log.warn("SQL 검증 실패: 차단 키워드 '{}' 발견", keyword);
        throw new ScriptExecutionException(
            "SQL 스크립트에 차단된 키워드가 포함되어 있습니다: "
                + keyword
                + ". 파이프라인 SQL은 data 스키마의 SELECT, INSERT, UPDATE, DELETE만 허용됩니다.");
      }
    }
  }
}
