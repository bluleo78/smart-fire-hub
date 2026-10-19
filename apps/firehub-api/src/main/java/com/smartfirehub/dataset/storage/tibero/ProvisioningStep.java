package com.smartfirehub.dataset.storage.tibero;

/**
 * 계정 프로비저닝의 한 단계.
 *
 * @param description 로그·오류용 한국어 설명(비밀번호 없음)
 * @param sql 실행할 SQL(비밀번호 포함 가능 — 로그에 남기지 말 것)
 * @param createdUsername 이 단계가 계정을 만들면 그 이름, 아니면 null(실패 시 보상 삭제 대상 추적용)
 */
public record ProvisioningStep(String description, String sql, String createdUsername) {

  /** SQL 에 비밀번호가 있을 수 있어 설명만 노출한다. */
  @Override
  public String toString() {
    return "ProvisioningStep[" + description + "]";
  }
}
