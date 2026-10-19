package com.smartfirehub.dataset.storage;

/** 외부 저장소의 스키마·계정을 만드는 방식(WD-73). */
public enum DataStoreProvisionMode {
  /** 운영자가 준 DBA 계정으로 앱이 자동 생성한다. */
  DBA_AUTO,
  /** 앱이 만든 스크립트를 운영자(DBA)가 직접 실행한다. */
  SCRIPT
}
