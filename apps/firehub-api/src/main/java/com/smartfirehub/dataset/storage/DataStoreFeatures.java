package com.smartfirehub.dataset.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 백엔드별 사용 가능 여부(WD-73). 아직 구현이 완성되지 않은 백엔드를 운영자가 고르지 못하게 막는 기능 플래그다. */
@Component
public class DataStoreFeatures {

  private final boolean tiberoEnabled;

  public DataStoreFeatures(@Value("${app.data-store.tibero-enabled:false}") boolean tiberoEnabled) {
    this.tiberoEnabled = tiberoEnabled;
  }

  /** POSTGRESQL 은 항상, TIBERO 는 플래그에 따라, MYSQL 은 아직 구현이 없어 항상 false. */
  public boolean isAvailable(DataStoreBackend backend) {
    return switch (backend) {
      case POSTGRESQL -> true;
      case TIBERO -> tiberoEnabled;
      case MYSQL -> false;
    };
  }
}
