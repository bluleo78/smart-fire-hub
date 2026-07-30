// HITL 승인된 정정 속성값을 Neo4j 엔티티 노드에 write한다(속성 정규화 검수 승인).
// loader.ts와 동일한 예약키 방어 — 정체성 필드를 덮어써 노드를 깨뜨리지 않게 한다.
import { getSession } from './neo4j-client.js';
import { GraphMutationRejectedError, GraphTargetMissingError, affectedCount } from './graph-mutation-guard.js';
import { normalizePropertyChecked } from './property-normalizer.js';

const RESERVED_NODE_KEYS = new Set(['key', 'type', 'name', 'sourceChunkIds', 'schemaVersion']);

/** text 정정값 상한 — 속성값은 문장이 아니라 짧은 값이라는 전제. 초과분은 오추출/오입력으로 본다. */
const MAX_TEXT_LENGTH = 1000;

/**
 * 사람이 입력한 정정값이 속성의 dataType 규약을 만족하지 못하는 상태(#311).
 * 검수자가 값을 고치면 해결되므로 장애가 아니라 거절(409 + 한국어 사유)로 전파한다.
 */
export class PropertyValueInvalidError extends GraphMutationRejectedError {
  constructor(message: string) {
    super(message);
    this.name = 'PropertyValueInvalidError';
  }
}

/** YYYY-MM-DD로 정규화된 날짜가 달력상 실재하는지 확인한다(2026-02-31 같은 논리적 무효 날짜 배제). */
function isRealDate(normalized: string): boolean {
  const [y, m, d] = normalized.split('-').map(Number);
  if (m < 1 || m > 12 || d < 1) return false;
  const dt = new Date(Date.UTC(y, m - 1, d));
  // 존재하지 않는 날짜는 JS Date가 다음 달로 굴려버리므로(2026-02-31 → 3-03) 구성요소 왕복으로 판별한다.
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === m - 1 && dt.getUTCDate() === d;
}

/**
 * 정정값(문자열)을 dataType 규약에 맞게 검증·정규화해 그래프에 저장할 값으로 바꾼다(#311).
 *
 * 왜 필요한가: 이 값은 "자동 정규화가 실패해 사람이 대신 정규화한 값"이다. 여기서 검증하지 않으면
 * `date` 속성에 '작년겨울' 같은 원문이 그대로 적재되어 정규화 검수 자체가 무의미해지고, 이후 이 속성을
 * 날짜로 소비하는 질의가 전부 깨진다. 예전에는 number만 NaN 검사가 있었고 date/text는 무검증이었다.
 * (number도 공백 문자열이 Number('') === 0으로 통과해 0이 조용히 적재되는 구멍이 있었다.)
 *
 * 관용 입력(2026.1.5, 2026년 1월 5일)은 자동 정규화기와 같은 규칙으로 받아 YYYY-MM-DD로 저장한다 —
 * 클라이언트가 안내하는 YYYY-MM-DD는 이 허용 범위의 부분집합이라 UI와 어긋나지 않는다.
 */
export function coercePropertyValue(dataType: 'text' | 'number' | 'date', value: string): number | string {
  const trimmed = value.trim();
  if (trimmed === '') {
    throw new PropertyValueInvalidError('정정값이 비어 있습니다. 값을 입력해 주세요.');
  }
  if (dataType === 'number') {
    const n = Number(trimmed);
    if (!Number.isFinite(n)) {
      throw new PropertyValueInvalidError(`숫자 속성의 정정값은 숫자여야 합니다(예: 30000000). 입력값: "${value}"`);
    }
    return n;
  }
  if (dataType === 'date') {
    const normalized = normalizePropertyChecked('date', undefined, trimmed).value;
    if (typeof normalized !== 'string') {
      throw new PropertyValueInvalidError(`날짜 속성의 정정값은 YYYY-MM-DD 형식이어야 합니다(예: 2026-01-05). 입력값: "${value}"`);
    }
    if (!isRealDate(normalized)) {
      throw new PropertyValueInvalidError(`존재하지 않는 날짜입니다: "${value}"`);
    }
    return normalized;
  }
  // text — 검수 큐로 오는 일은 사실상 없다(비어있지 않은 텍스트는 자동 정규화가 항상 성공해 status='failed'가 안 난다).
  // 그래도 이 라우트는 직접 호출 가능하므로 최소 방어만 둔다: 공백뿐인 값 거부(위에서 처리) + 길이 상한.
  // 내부 연속 공백은 정규화기와 동일하게 한 칸으로 접는다.
  const collapsed = trimmed.replace(/\s+/g, ' ');
  if (collapsed.length > MAX_TEXT_LENGTH) {
    throw new PropertyValueInvalidError(`텍스트 속성의 정정값은 ${MAX_TEXT_LENGTH}자 이하여야 합니다(현재 ${collapsed.length}자).`);
  }
  return collapsed;
}

/**
 * entityKey로 식별되는 노드의 propertyName 속성값을 정정값으로 설정한다.
 * 정정값은 dataType 규약에 맞게 검증·정규화한 뒤 저장한다(#311 — 위반 시 PropertyValueInvalidError).
 * 예약 속성명은 거부한다.
 * 대상 노드가 없으면(MATCH 미스) 예전에는 조용히 no-op이었고, 호출측은 이를 성공으로 보고 검수 항목을
 * approved로 바꿔 정정 내용이 유실됐다(#310). 이제 반영 건수 0이면 실패로 전파한다.
 */
export async function setEntityProperty(
  entityKey: string, propertyName: string, dataType: 'text' | 'number' | 'date', value: string,
): Promise<void> {
  if (RESERVED_NODE_KEYS.has(propertyName)) {
    throw new Error(`예약된 속성명은 변경할 수 없습니다: ${propertyName}`);
  }
  const coerced = coercePropertyValue(dataType, value);
  // 예약키 방어를 통과한 propertyName만으로 병합 맵을 구성한다(동적 키를 JS측에서 안전하게 결정).
  const props: Record<string, number | string> = { [propertyName]: coerced };
  const session = getSession();
  try {
    // loader.ts와 동일한 맵-병합 관용구(SET n += $props) — Neo4j 버전 무관, 주입 안전.
    const result = await session.run(
      'MATCH (n:Entity {key: $key}) SET n += $props RETURN count(n) AS updated',
      { key: entityKey, props },
    );
    // 판정 기준은 "MATCH가 노드를 찾았는가"다. 정정값이 기존 값과 같아 실제 write가 없어도 성공이어야 한다.
    if (affectedCount(result, 'updated') === 0) {
      throw new GraphTargetMissingError(
        `대상 엔티티가 그래프에 없어 속성을 정정할 수 없습니다(key=${entityKey}).`,
      );
    }
  } finally {
    await session.close();
  }
}
