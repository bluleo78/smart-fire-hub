package com.smartfirehub.dataset.storage.tibero;

import com.smartfirehub.dataset.storage.DataStoreAccountRole;

/**
 * 생성할 외부 DB 계정 한 개(역할·이름·비밀번호).
 *
 * <p>비밀번호가 로그·예외 메시지에 새지 않도록 {@link #toString()} 에서 마스킹한다.
 */
public record PlannedAccount(DataStoreAccountRole role, String username, String password) {

  @Override
  public String toString() {
    return "PlannedAccount[role=" + role + ", username=" + username + ", password=****]";
  }
}
