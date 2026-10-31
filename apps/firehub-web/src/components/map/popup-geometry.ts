/** 지도 정보 팝업의 기준점·폭 계산 — FeaturePopup 이 쓰고, 단위 테스트가 직접 검증한다(WD-338). */

/**
 * 팝업 기준점. 점은 그 위치, 선·면은 모든 꼭짓점을 감싸는 사각형의 가운데를 쓴다(WD-338) —
 * 예전에는 첫 꼭짓점(면이면 한쪽 모서리)을 써서 팝업이 도형 가장자리에 붙어 열렸다.
 */
export function extractLngLat(geom: {
  type: string;
  coordinates: unknown;
}): [number, number] | null {
  if (geom.type === "Point") {
    const [lng, lat] = geom.coordinates as [number, number];
    return [lng, lat];
  }
  if (
    ![
      "LineString",
      "MultiPoint",
      "Polygon",
      "MultiPolygon",
      "MultiLineString",
    ].includes(geom.type)
  ) {
    return null;
  }
  // 중첩 깊이와 상관없이 [lng, lat] 쌍만 모은다
  const points: [number, number][] = [];
  const collect = (c: unknown) => {
    if (!Array.isArray(c)) return;
    if (c.length >= 2 && typeof c[0] === "number" && typeof c[1] === "number") {
      points.push([c[0], c[1]]);
      return;
    }
    c.forEach(collect);
  };
  collect(geom.coordinates);
  if (points.length === 0) return null;
  const lngs = points.map((pt) => pt[0]);
  const lats = points.map((pt) => pt[1]);
  return [
    (Math.min(...lngs) + Math.max(...lngs)) / 2,
    (Math.min(...lats) + Math.max(...lats)) / 2,
  ];
}

/** 팝업 최대 폭(px) — 넓은 지도에서는 320px, 좁은 지도에서는 양쪽 8px 여백을 남긴 지도 폭 */
export const POPUP_MAX_WIDTH = 320;
export function popupMaxWidth(mapWidth: number): number {
  return Math.max(160, Math.min(POPUP_MAX_WIDTH, mapWidth - 16));
}
