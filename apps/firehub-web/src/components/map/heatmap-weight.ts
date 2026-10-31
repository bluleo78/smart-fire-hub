import { toPlotNumber } from '@/components/analytics/numeric-columns';
import type { GeoJsonFeatureCollection } from '@/types/dataset';

/**
 * 히트맵 가중치 정규화(WD-354).
 *
 * maplibre 의 heatmap-weight 는 0~1 을 기준으로 밀도를 누적한다. 예전에는 컬럼 원값을 그대로 넣어
 * 0.0x 단위 값(비율·확률)은 히트맵이 통째로 사라지고, 큰 값(건수·금액)은 모든 지점이 빨갛게 포화돼
 * 1 과 100,000 의 차이도 보이지 않았다. 지금 그리는 데이터의 최솟값~최댓값을 WEIGHT_MIN~1 로 옮겨
 * 값의 상대적 크기대로 진하게 보이게 한다.
 */

/** 정규화된 가중치를 싣는 feature 속성 이름 — 사용자 컬럼과 겹치지 않게 내부 접두사를 쓴다 */
export const HEAT_WEIGHT_PROP = '__fh_heat_weight';

/**
 * 가장 작은 값이 받는 가중치. 0 이면 최솟값 지점(예: big_w 의 왼쪽 절반 1)이 아예 사라져
 * "그 위치에 데이터가 없다"로 읽히므로, 흐리게라도 보이도록 바닥을 둔다.
 */
export const WEIGHT_MIN = 0.1;

/** 값을 가중치용 숫자로 읽는다 — 산점도와 같은 규칙(numeric-columns.ts toPlotNumber) */
const toWeightNumber = toPlotNumber;

/** 가중치 컬럼의 숫자 값 범위. 숫자 값이 하나도 없으면 null(문자 컬럼 등) */
export function weightRange(
  data: GeoJsonFeatureCollection,
  weightProperty: string,
): { min: number; max: number } | null {
  let min = Infinity;
  let max = -Infinity;
  for (const f of data.features) {
    const n = toWeightNumber(f.properties?.[weightProperty]);
    if (n === null) continue;
    if (n < min) min = n;
    if (n > max) max = n;
  }
  return min === Infinity ? null : { min, max };
}

/**
 * 각 feature 에 정규화된 가중치(HEAT_WEIGHT_PROP)를 붙인 새 컬렉션을 돌려준다.
 *
 * - 가중치 컬럼이 없거나 숫자 값이 하나도 없으면(문자 컬럼) 모두 1 — 균등 가중치로 그린다(사라지지 않게).
 * - 모든 값이 같으면 모두 1 — 나눌 범위가 없다.
 * - 숫자가 아닌 행(NULL·문자)은 0 — 값이 없는 행은 밀도에 기여하지 않는다(예전과 같음).
 */
export function withNormalizedWeight(
  data: GeoJsonFeatureCollection,
  weightProperty?: string,
): GeoJsonFeatureCollection {
  const range = weightProperty ? weightRange(data, weightProperty) : null;
  const weightOf = (value: unknown): number => {
    if (!weightProperty || !range) return 1;
    const n = toWeightNumber(value);
    if (n === null) return 0;
    if (range.max === range.min) return 1;
    return WEIGHT_MIN + ((n - range.min) / (range.max - range.min)) * (1 - WEIGHT_MIN);
  };
  return {
    ...data,
    features: data.features.map((f) => ({
      ...f,
      properties: {
        ...f.properties,
        [HEAT_WEIGHT_PROP]: weightOf(weightProperty ? f.properties?.[weightProperty] : undefined),
      },
    })),
  };
}
