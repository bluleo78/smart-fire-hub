package com.smartfirehub.dataset.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** StoredAccount.toString 이 암호문을 노출하지 않는지 확인한다. */
class StoredAccountTest {

  @Test
  void toString_masksEncryptedPassword() {
    StoredAccount a = new StoredAccount(DataStoreAccountRole.OWNER, "GENIA_T1", "enc:SECRET");
    assertThat(a.toString()).doesNotContain("enc:SECRET").contains("encryptedPassword=****");
  }
}
