package com.smartfirehub.securitylevel.access;

/** 데이터셋에 대한 행위 종류(스펙 §2.5). VIEW 가 다른 모든 행위의 전제다. */
public enum DatasetAction {
  VIEW,
  EXPORT,
  AI,
  SHARE
}
