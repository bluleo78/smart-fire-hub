package com.smartfirehub.audit.time;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 저장 벽시계(LocalDateTime)를 저장 TZ 오프셋을 붙인 ISO 문자열로 직렬화한다(WD-11).
 *
 * <p>대상은 {@code TIMESTAMP DEFAULT NOW()} 로 세션 TZ(= JVM TZ, {@link AuditTimes#storageZone}) 벽시계가
 * 저장되는 컬럼이다 — 감사 {@code action_time} 과 테넌트 {@code tenant.created_at}(운영자 콘솔 생성일). 오프셋 없이 내보내면
 * 클라이언트가 저장 TZ 를 알 수 없어 운영(UTC 저장)에서 KST 사용자에게 9시간 어긋난다.
 *
 * <p>Java 타입은 LocalDateTime 으로 두고 JSON 만 바꾸는 이유: 타입을 바꾸면 감사 응답을 다른 DTO 로 복사하는 경로 (DataImportService
 * 의 가져오기 이력)까지 파급된다. 웹 parseUtcDate 는 오프셋을 존중하므로 운영 표시는 그대로다.
 */
public class StorageZoneDateTimeSerializer extends JsonSerializer<LocalDateTime> {

  @Override
  public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider provider)
      throws IOException {
    gen.writeString(
        AuditTimes.fromStorage(value, AuditTimes.storageZone())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
  }
}
