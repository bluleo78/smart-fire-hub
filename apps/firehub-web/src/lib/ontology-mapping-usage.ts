import axios from 'axios';

import type { ElementMappingUsage, OntologyMappingUsage } from '@/types/ontology';

/**
 * WD-129: 지식 모델 요소 ↔ 데이터셋 매핑 사용처 계산·문구.
 *
 * 매핑은 요소를 이름으로 참조하므로, 매핑이 쓰는 요소를 지우면 매핑이 없는 요소를 가리킨 채 남는다. 서버는 그런 삭제를 409
 * (MAPPING_IN_USE_CODE)로 막고, 삭제 확인 창은 확정 전에 같은 사실을 미리 보여 준다.
 */

/** 서버 MappingReferenceService.IN_USE_CODE 와 같은 값 — 문구가 아니라 코드로 "매핑 사용 중" 409 를 가린다. */
export const MAPPING_IN_USE_CODE = 'ONTOLOGY_ELEMENT_IN_USE_BY_MAPPING';

/** 이름을 다 나열하면 창이 길어지므로 앞의 몇 개만 보이고 나머지는 "외 n개"로 줄인다(서버 문구와 같은 규칙). */
const MAX_NAMES = 5;

export type MappingElementKind = 'entityType' | 'property' | 'relation';

const KIND_KEY: Record<MappingElementKind, keyof OntologyMappingUsage> = {
  entityType: 'entityTypes',
  property: 'properties',
  relation: 'relations',
};

/**
 * 요소 하나의 매핑 사용처. 사용처가 없거나, 아직 모르거나(조회 중·실패), id 가 없으면 null 이다 — null 이면 막지 않는다
 * (최종 판정은 서버 409). 서버가 사용처 있는 요소만 담아 주므로 null 이 아니면 곧 "사용 중"이다.
 */
export function mappingUsageFor(
  usage: OntologyMappingUsage | undefined,
  kind: MappingElementKind,
  id: number | undefined,
): ElementMappingUsage | null {
  if (!usage || id == null) return null;
  return usage[KIND_KEY[kind]][String(id)] ?? null;
}

/**
 * 삭제 불가 안내 문구. 예: "이 타입은 「화재사건표」 데이터셋 매핑에서 사용 중이라 삭제할 수 없습니다. 매핑에서 먼저 빼 주세요."
 * 서버 MappingReferenceService.inUseMessage 와 같은 규칙이다 — 한쪽을 고치면 다른 쪽도 고칠 것.
 */
export function mappingUsageMessage(subject: string, usage: ElementMappingUsage): string {
  const shown = usage.datasets.slice(0, MAX_NAMES).map((d) => `「${d.datasetName}」`);
  const rest = usage.datasets.length - shown.length;
  const hidden = usage.hiddenCount;

  const visiblePart = shown.length > 0 ? `${shown.join(', ')}${rest > 0 ? ` 외 ${rest}개` : ''} 데이터셋` : null;
  const hiddenPart = hidden > 0 ? `볼 수 없는 데이터셋 ${hidden}개` : null;
  const where =
    visiblePart && hiddenPart
      ? `${visiblePart}과 ${hiddenPart}의 매핑에서`
      : visiblePart
        ? `${visiblePart} 매핑에서`
        : `${hiddenPart}의 매핑에서`;
  return `${subject} ${where} 사용 중이라 삭제할 수 없습니다. 매핑에서 먼저 빼 주세요.`;
}

/** 서버가 "매핑 사용 중"으로 삭제를 거부한 409 인가 — 재시도로는 풀리지 않으므로 재시도 어포던스를 주지 않는다. */
export function isMappingInUseError(error: unknown): boolean {
  return (
    axios.isAxiosError(error) &&
    error.response?.status === 409 &&
    (error.response.data as { code?: string } | undefined)?.code === MAPPING_IN_USE_CODE
  );
}

/**
 * WD-185: 서버 MappingReferenceService.TYPE_CONFLICT_CODE 와 같은 값. 속성 자료형을 바꾸려 했으나 데이터셋 매핑이 그 속성에 연결한
 * 컬럼 타입과 맞지 않아 거절된 409 다.
 */
export const MAPPING_TYPE_CONFLICT_CODE = 'ONTOLOGY_PROPERTY_TYPE_CONFLICTS_WITH_MAPPING';

/** 자료형 변경이 매핑 연결 컬럼과 충돌해 거절된 409 인가 — 매핑에서 연결을 바꾸기 전엔 다시 해도 같으므로 재시도를 주지 않는다. */
export function isMappingTypeConflictError(error: unknown): boolean {
  return (
    axios.isAxiosError(error) &&
    error.response?.status === 409 &&
    (error.response.data as { code?: string } | undefined)?.code === MAPPING_TYPE_CONFLICT_CODE
  );
}
