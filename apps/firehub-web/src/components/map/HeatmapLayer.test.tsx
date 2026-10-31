/**
 * HeatmapLayer 컴포넌트 단위 테스트
 * maplibre Map 인스턴스를 모킹하여 source/layer mount-update-unmount 라이프사이클 검증.
 */
import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { HEAT_WEIGHT_PROP, WEIGHT_MIN } from './heatmap-weight';
import { HeatmapLayer } from './HeatmapLayer';

interface MockMap {
  addSource: ReturnType<typeof vi.fn>;
  addLayer: ReturnType<typeof vi.fn>;
  removeLayer: ReturnType<typeof vi.fn>;
  removeSource: ReturnType<typeof vi.fn>;
  getLayer: ReturnType<typeof vi.fn>;
  getSource: ReturnType<typeof vi.fn>;
  setPaintProperty: ReturnType<typeof vi.fn>;
  on: ReturnType<typeof vi.fn>;
  off: ReturnType<typeof vi.fn>;
  isStyleLoaded: ReturnType<typeof vi.fn>;
  flyTo: ReturnType<typeof vi.fn>;
  fitBounds: ReturnType<typeof vi.fn>;
  /** getSource 가 돌려주는 GeoJSON source 의 setData — 데이터 갱신 검증용 */
  __source: { setData: ReturnType<typeof vi.fn> };
}

function createMockMap(): MockMap {
  const sources = new Map<string, unknown>();
  const layers = new Map<string, unknown>();
  const source = { setData: vi.fn() };
  return {
    __source: source,
    addSource: vi.fn((id: string, src: unknown) => { sources.set(id, src); }),
    addLayer: vi.fn((lyr: { id: string }) => { layers.set(lyr.id, lyr); }),
    removeLayer: vi.fn((id: string) => { layers.delete(id); }),
    removeSource: vi.fn((id: string) => { sources.delete(id); }),
    getLayer: vi.fn((id: string) => layers.get(id)),
    getSource: vi.fn((id: string) => (sources.has(id) ? source : undefined)),
    setPaintProperty: vi.fn(),
    on: vi.fn(),
    off: vi.fn(),
    isStyleLoaded: vi.fn(() => true),
    flyTo: vi.fn(),
    fitBounds: vi.fn(),
  };
}

const emptyFc = { type: 'FeatureCollection' as const, features: [] };

describe('HeatmapLayer', () => {
  it('마운트 시 source 와 heatmap layer 를 추가한다', () => {
    const map = createMockMap();
    render(<HeatmapLayer map={map as never} data={emptyFc} />);

    expect(map.addSource).toHaveBeenCalledWith('heatmap-src', expect.objectContaining({
      type: 'geojson',
    }));
    expect(map.addLayer).toHaveBeenCalledWith(expect.objectContaining({
      id: 'heatmap-lyr',
      type: 'heatmap',
      source: 'heatmap-src',
    }));
  });

  // WD-354: 원값 대신 데이터에 미리 정규화해 실은 가중치 속성을 읽는다
  it('heatmap-weight 는 정규화 가중치 속성을 읽는다', () => {
    const map = createMockMap();
    render(<HeatmapLayer map={map as never} data={emptyFc} weightProperty="cnt" />);

    const layerArg = map.addLayer.mock.calls[0][0] as {
      paint: Record<string, unknown>;
    };
    expect(layerArg.paint['heatmap-weight']).toEqual(['get', HEAT_WEIGHT_PROP]);
  });

  it('source 에 최솟값~최댓값으로 정규화한 가중치를 싣고, 가중치 컬럼을 바꾸면 다시 보낸다', () => {
    const map = createMockMap();
    const fc = {
      type: 'FeatureCollection' as const,
      features: [1, 100000].map((big, i) => ({
        type: 'Feature' as const,
        geometry: { type: 'Point', coordinates: [127 + i, 37.5] },
        properties: { big, tiny: [0.0001, 0.04][i] },
      })),
    };
    const { rerender } = render(<HeatmapLayer map={map as never} data={fc} weightProperty="big" />);
    const added = map.addSource.mock.calls[0][1] as { data: typeof fc };
    expect(added.data.features.map((f) => f.properties[HEAT_WEIGHT_PROP as 'big'])).toEqual([WEIGHT_MIN, 1]);

    rerender(<HeatmapLayer map={map as never} data={fc} weightProperty="tiny" />);
    const sent = map.__source.setData.mock.calls.at(-1)?.[0] as typeof fc;
    expect(sent.features.map((f) => f.properties[HEAT_WEIGHT_PROP as 'tiny'])).toEqual([WEIGHT_MIN, 1]);
  });

  // 회귀 가드(WD-355): 테마 전환(setStyle)으로 source·layer 가 지워지면 styledata 에서 다시 붙이는데,
  // 예전엔 그 핸들러가 마운트 때의 데이터·가중치를 붙들어 바꾼 가중치가 처음 값으로 되돌아갔다.
  it('스타일이 다시 로드되면 처음 값이 아니라 지금 가중치로 source 를 다시 붙인다 (WD-355)', () => {
    const map = createMockMap();
    const fc = {
      type: 'FeatureCollection' as const,
      features: [1, 2, 3].map((n, i) => ({
        type: 'Feature' as const,
        geometry: { type: 'Point', coordinates: [127 + i, 37.5] },
        properties: { even: 5, grow: n },
      })),
    };
    // 처음엔 가중치 없음(균등) → 사용자가 grow 컬럼으로 바꾼다
    const { rerender } = render(<HeatmapLayer map={map as never} data={fc} />);
    rerender(<HeatmapLayer map={map as never} data={fc} weightProperty="grow" />);

    // 테마 전환: 새 스타일이 source·layer 를 비운 뒤 styledata 를 낸다
    (map.removeLayer as unknown as (id: string) => void)('heatmap-lyr');
    (map.removeSource as unknown as (id: string) => void)('heatmap-src');
    map.addSource.mockClear();
    const onStyleData = map.on.mock.calls.find(([event]) => event === 'styledata')?.[1] as () => void;
    onStyleData();

    const readded = map.addSource.mock.calls[0][1] as { data: typeof fc };
    const weights = readded.data.features.map((f) => f.properties[HEAT_WEIGHT_PROP as 'grow']);
    // 균등 가중치(모두 같은 값)가 아니라 grow 로 정규화한 값 — 최솟값 행이 가장 작고 최댓값 행이 1
    expect(weights[0]).toBe(WEIGHT_MIN);
    expect(weights[2]).toBe(1);
    expect(map.addLayer).toHaveBeenCalledTimes(2);
  });

  it('언마운트 시 layer 와 source 를 제거한다', () => {
    const map = createMockMap();
    const { unmount } = render(<HeatmapLayer map={map as never} data={emptyFc} />);
    unmount();

    expect(map.removeLayer).toHaveBeenCalledWith('heatmap-lyr');
    expect(map.removeSource).toHaveBeenCalledWith('heatmap-src');
  });

  // WD-348: 히트맵도 점 모드처럼 데이터 범위로 지도를 맞춘다 — 예전엔 기본 위치(서울)에 머물러 부산 데이터가 빈 지도로 보였다.
  describe('데이터 범위 맞춤 (WD-348)', () => {
    const busan = {
      type: 'FeatureCollection' as const,
      features: [{ type: 'Feature' as const, geometry: { type: 'Point', coordinates: [129.07, 35.18] }, properties: {} }],
    };

    it('점 하나면 그 위치로 이동한다', () => {
      const map = createMockMap();
      render(<HeatmapLayer map={map as never} data={busan as never} />);
      expect(map.flyTo).toHaveBeenCalledWith({ center: [129.07, 35.18], zoom: 13 });
    });

    it('점이 여럿이면 모두 감싸는 영역으로 맞춘다', () => {
      const map = createMockMap();
      const two = {
        type: 'FeatureCollection' as const,
        features: [
          { type: 'Feature' as const, geometry: { type: 'Point', coordinates: [126.97, 37.56] }, properties: {} },
          { type: 'Feature' as const, geometry: { type: 'Point', coordinates: [129.07, 35.18] }, properties: {} },
        ],
      };
      render(<HeatmapLayer map={map as never} data={two as never} />);
      expect(map.fitBounds).toHaveBeenCalledWith([[126.97, 35.18], [129.07, 37.56]], { padding: 60, maxZoom: 15 });
    });

    it('데이터가 바뀌면 새 범위로 다시 맞춘다', () => {
      const map = createMockMap();
      const { rerender } = render(<HeatmapLayer map={map as never} data={emptyFc} />);
      expect(map.flyTo).not.toHaveBeenCalled();
      expect(map.fitBounds).not.toHaveBeenCalled();
      rerender(<HeatmapLayer map={map as never} data={busan as never} />);
      expect(map.flyTo).toHaveBeenCalledWith({ center: [129.07, 35.18], zoom: 13 });
    });
  });
});
