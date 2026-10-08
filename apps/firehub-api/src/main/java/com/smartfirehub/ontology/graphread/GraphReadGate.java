package com.smartfirehub.ontology.graphread;

import static com.smartfirehub.jooq.Tables.DATASET;

import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 지식그래프 읽기 판정(WD-28). 어떤 사용자가 온톨로지 O 의 그래프를 읽으려면 O 의 출처(graph_ontology_source)인 <b>존재하는</b> 데이터셋을
 * <b>전부</b> VIEW 할 수 있어야 한다. 하나라도 못 보면 그래프 읽기 세 경로(시각화·graphrag_query· structured_query)를 모두 막는다.
 * 판정은 거칠다 — 노드 단위 정밀 판정은 S3 이후 과제다.
 *
 * <p>등급·허용 목록 규칙은 {@link DatasetAccessGuard#visibleCondition} 을 그대로 쓴다(새 판정 SQL 을 만들지 않는다). 온톨로지
 * 스키마(목록·요소 편집)는 데이터가 아니라 이 판정의 대상이 아니다.
 */
@Service
@RequiredArgsConstructor
public class GraphReadGate {

  /** 제한 응답 코드 — web 과 ai-agent 가 이 문자열로 분기한다. */
  public static final String RESTRICTED_CODE = "GRAPH_READ_RESTRICTED";

  /** 시각화 화면용 문구(스펙 §5 web 원문). 어느 데이터셋 때문인지는 밝히지 않는다. */
  public static final String RESTRICTED_MESSAGE = "이 지식그래프에는 열람 권한이 없는 데이터가 포함되어 있어 표시할 수 없습니다.";

  private final GraphOntologySourceRepository sourceRepository;
  private final DatasetAccessGuard accessGuard;
  private final ClearanceResolver clearanceResolver;

  /** 현재 요청 사용자 기준 판정. */
  public boolean canRead(long ontologyId) {
    return canRead(clearanceResolver.current(), ontologyId);
  }

  /**
   * 명시 자격 기준 판정. 사용자 신원이 없는 자격(미인증 {@code Clearance.none(-1L, …)})은 판정할 주체가 없으므로 false 다
   * (fail-closed). 출처가 없는 온톨로지도 신원 없이는 열지 않는다.
   *
   * <p>여기에 @Transactional 을 두지 않는다 — 판정 쿼리의 트랜잭션은 GraphOntologySourceRepository 가 맡는다(자기 호출이 프록시를
   * 우회하는 함정, 그 클래스 주석 참고).
   */
  public boolean canRead(Clearance c, long ontologyId) {
    if (c.userId() <= 0) {
      return false;
    }
    return !sourceRepository.existsInvisibleSource(
        ontologyId, accessGuard.visibleCondition(c, DATASET.ID, DATASET.SECURITY_LEVEL_ID));
  }
}
