package com.smartfirehub.file.service;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * FILE 데이터셋 업로드용 오브젝트 키 생성기.
 * 키 규약 "<prefix><robotId>/<yyyy-MM-dd>/<uuid>.<ext>"를 앱이 강제하여 프리픽스 격리와 명명 규칙을 보장한다.
 * 클라이언트는 확장자/로봇ID만 제안하며, 최종 키는 앱이 결정하므로 프리픽스 밖으로 나갈 수 없다.
 */
@Component
public class ObjectKeyGenerator {

  /** robotId 정제: 소문자화 후 [a-z0-9-] 외 문자는 '-'로 치환하고 앞뒤 '-' 제거. 결과가 비면 "web". */
  public String sanitizeRobotId(String robotId) {
    if (robotId == null) return "web";
    String s = robotId.toLowerCase().replaceAll("[^a-z0-9-]", "-").replaceAll("^-+|-+$", "");
    return s.isEmpty() ? "web" : s;
  }

  /** ext 정제: 소문자화 후 [a-z0-9]만 남기고 최대 10자로 자른다. 결과가 비면 "bin". */
  public String sanitizeExt(String ext) {
    if (ext == null) return "bin";
    String s = ext.toLowerCase().replaceAll("[^a-z0-9]", "");
    if (s.length() > 10) s = s.substring(0, 10);
    return s.isEmpty() ? "bin" : s;
  }

  /** 규약 키 생성. prefix는 항상 trailing '/'로 끝난다고 가정한다(FileDatasetConfig에서 보장). */
  public String generateKey(String prefix, String robotId, String ext) {
    return prefix
        + sanitizeRobotId(robotId)
        + "/"
        + LocalDate.now()
        + "/"
        + UUID.randomUUID()
        + "."
        + sanitizeExt(ext);
  }
}
