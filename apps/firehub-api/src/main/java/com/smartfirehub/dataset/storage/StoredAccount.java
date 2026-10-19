package com.smartfirehub.dataset.storage;

/** {@code tenant_data_store_account} 한 행(WD-73). 비밀번호는 암호화된 상태로만 보관·전달한다. */
public record StoredAccount(DataStoreAccountRole role, String username, String encryptedPassword) {

  /** 암호문이 로그·예외 메시지에 새지 않도록 마스킹한다. */
  @Override
  public String toString() {
    return "StoredAccount[role=" + role + ", username=" + username + ", encryptedPassword=****]";
  }
}
