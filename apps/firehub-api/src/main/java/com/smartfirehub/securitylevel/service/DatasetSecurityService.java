package com.smartfirehub.securitylevel.service;

import static com.smartfirehub.jooq.Tables.DATASET;

import com.smartfirehub.securitylevel.repository.DatasetAccessGrantRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 데이터셋 단위 보안 작업 — 등급 변경·허용 목록·상속·(S2) 파이프라인 출력 상향. 판정(볼 수 있는가)은 하지 않는다 — 호출 경로의 인터셉터·가드가 이미 했다. */
@Service
@RequiredArgsConstructor
public class DatasetSecurityService {

  private final DSLContext dsl;
  private final DatasetAccessGrantRepository grantRepository;
  private final SecurityAuditRecorder audit;

  /**
   * clone 은 원본 등급을 상속하고 허용 목록을 복사한다(스펙 §4.2 2행, §4.5). 복사하지 않으면 허용 목록 필요 등급의 사본은 아무도 못 보는 고아가 된다.
   * 복제자는 원본을 볼 수 있었으므로 이미 목록에 있거나 역할로 충족한다.
   */
  @Transactional
  public void inheritFromSource(long sourceDatasetId, long newDatasetId, long actorUserId) {
    Long sourceLevel =
        dsl.select(DATASET.SECURITY_LEVEL_ID)
            .from(DATASET)
            .where(DATASET.ID.eq(sourceDatasetId))
            .fetchSingle(DATASET.SECURITY_LEVEL_ID);
    dsl.update(DATASET)
        .set(DATASET.SECURITY_LEVEL_ID, sourceLevel)
        .where(DATASET.ID.eq(newDatasetId))
        .execute();
    int copied = grantRepository.copy(sourceDatasetId, newDatasetId, actorUserId);
    audit.record(
        actorUserId,
        "DATASET_SECURITY_LEVEL_CHANGE",
        "dataset",
        String.valueOf(newDatasetId),
        "복제 원본 등급 상속",
        Map.of(
            "sourceDatasetId", sourceDatasetId, "toLevelId", sourceLevel, "copiedGrants", copied));
  }
}
