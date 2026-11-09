package com.smartfirehub.pipeline.service;

import com.smartfirehub.dataset.storage.DatasetStructureLock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 데이터 저장소에 직접 쓰는 파이프라인 쓰기(SQL 스텝·실행기 적재) 한 대상의 구조 잠금 계획(WD-670).
 *
 * <p>쓰는 쪽은 자기 데이터 저장소 트랜잭션의 첫 문장으로 {@link #key()} 의 공유 advisory 잠금을 잡고(여럿이면 키 순서 — 두 쓰기가 거꾸로 기다리지
 * 않게), 잡은 뒤 테이블이 아직 있는지·계획한 컬럼이 그대로인지 다시 확인한다. 계획(컬럼 목록)은 잠금 전에 세웠으므로 그 사이 커밋된 구조 변경을 여기서 잡아 낸다.
 *
 * <p>확인 규칙: 테이블이 없으면 바뀐 것이다. {@code columns} 가 있으면 (1) 그 컬럼이 모두 물리 테이블에 있어야 하고 (2) 물리 테이블에 값이 꼭 필요한
 * 컬럼(NOT NULL·기본값 없음·identity 아님)이 {@code columns} 밖에 있으면 안 된다(실행 중 추가된 NOT NULL 필드). {@code
 * columns} 가 null 이면(사용자 DML 의 쓰기 대상 — 쓸 컬럼을 모른다) 존재만 본다.
 *
 * @param tableName 데이터셋 테이블 이름(스키마 없이)
 * @param columns 계획 때 알던 컬럼(출력 데이터셋 필드 목록), 존재만 볼 때는 null
 */
public record StructureGuard(String tableName, List<String> columns) {

  /** 존재만 확인하는 대상(사용자 DML 쓰기 대상). */
  public static StructureGuard exists(String tableName) {
    return new StructureGuard(tableName, null);
  }

  /** 현재 테넌트의 구조 잠금 키. */
  public String key() {
    return DatasetStructureLock.keyFor(tableName);
  }

  /** 같은 테이블은 하나로 합치고(컬럼이 있는 쪽 우선) 테이블 이름순으로 정렬한다 — 잠금 순서가 모든 쓰기에서 같아야 서로 거꾸로 기다리지 않는다. */
  public static List<StructureGuard> normalize(Collection<StructureGuard> guards) {
    Map<String, StructureGuard> byTable = new LinkedHashMap<>();
    for (StructureGuard g : guards) {
      if (g == null || g.tableName() == null) {
        continue;
      }
      byTable.merge(g.tableName(), g, (a, b) -> a.columns() != null ? a : b);
    }
    List<StructureGuard> out = new ArrayList<>(byTable.values());
    out.sort(Comparator.comparing(StructureGuard::tableName));
    return out;
  }

  /**
   * firehub-executor 요청에 싣는 모양({@code [{key, columns}]}) — 실행기는 키를 테넌트 스키마 형식으로 검증한 뒤 같은 문장으로 잠그고
   * 같은 규칙으로 확인한다. 테넌트 문맥 안에서 부른다(키에 스키마가 들어간다).
   */
  public static List<Map<String, Object>> toExecutorPayload(Collection<StructureGuard> guards) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (StructureGuard g : normalize(guards)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("key", g.key());
      m.put("columns", g.columns());
      out.add(m);
    }
    return out;
  }
}
