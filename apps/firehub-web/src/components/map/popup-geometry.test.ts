import { describe, expect, it } from "vitest";

import { extractLngLat, popupMaxWidth } from "./popup-geometry";

/** 지도 정보 팝업 기준점·폭 (WD-338) */
describe("extractLngLat", () => {
  it("점은 그 위치를 쓴다", () => {
    expect(
      extractLngLat({ type: "Point", coordinates: [126.97, 37.56] }),
    ).toEqual([126.97, 37.56]);
  });

  it("면은 첫 꼭짓점(모서리)이 아니라 감싸는 사각형의 가운데를 쓴다", () => {
    const square = [
      [
        [126.0, 37.0],
        [127.0, 37.0],
        [127.0, 38.0],
        [126.0, 38.0],
        [126.0, 37.0],
      ],
    ];
    expect(extractLngLat({ type: "Polygon", coordinates: square })).toEqual([
      126.5, 37.5,
    ]);
  });

  it("여러 면·선은 모든 꼭짓점을 감싸는 사각형의 가운데를 쓴다", () => {
    const multi = [
      [
        [
          [0, 0],
          [1, 0],
          [1, 1],
          [0, 0],
        ],
      ],
      [
        [
          [4, 4],
          [5, 4],
          [5, 6],
          [4, 4],
        ],
      ],
    ];
    expect(extractLngLat({ type: "MultiPolygon", coordinates: multi })).toEqual(
      [2.5, 3],
    );
    expect(
      extractLngLat({
        type: "LineString",
        coordinates: [
          [0, 0],
          [2, 4],
        ],
      }),
    ).toEqual([1, 2]);
  });

  it("좌표가 없거나 모르는 유형이면 null", () => {
    expect(extractLngLat({ type: "Polygon", coordinates: [] })).toBeNull();
    expect(
      extractLngLat({ type: "GeometryCollection", coordinates: undefined }),
    ).toBeNull();
  });
});

describe("popupMaxWidth", () => {
  it("넓은 지도에서는 320px", () => {
    expect(popupMaxWidth(1200)).toBe(320);
  });

  it("모바일 지도(342px)에서는 양쪽 8px 여백을 남겨 지도 안에 들어간다", () => {
    expect(popupMaxWidth(342)).toBe(320);
    expect(popupMaxWidth(300)).toBe(284);
  });

  it("아주 좁아도 160px 아래로는 줄이지 않는다", () => {
    expect(popupMaxWidth(100)).toBe(160);
  });
});
