/**
 * HeatmapLayer 컴포넌트 단위 테스트
 * maplibre Map 인스턴스를 모킹하여 source/layer mount-update-unmount 라이프사이클 검증.
 */
import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

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
}

function createMockMap(): MockMap {
  const sources = new Map<string, unknown>();
  const layers = new Map<string, unknown>();
  return {
    addSource: vi.fn((id: string, src: unknown) => { sources.set(id, src); }),
    addLayer: vi.fn((lyr: { id: string }) => { layers.set(lyr.id, lyr); }),
    removeLayer: vi.fn((id: string) => { layers.delete(id); }),
    removeSource: vi.fn((id: string) => { sources.delete(id); }),
    getLayer: vi.fn((id: string) => layers.get(id)),
    getSource: vi.fn((id: string) => sources.get(id)),
    setPaintProperty: vi.fn(),
    on: vi.fn(),
    off: vi.fn(),
    isStyleLoaded: vi.fn(() => true),
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

  it('weightProperty 미지정 시 heatmap-weight 가 상수 1 이다', () => {
    const map = createMockMap();
    render(<HeatmapLayer map={map as never} data={emptyFc} />);

    const layerArg = map.addLayer.mock.calls[0][0] as {
      paint: Record<string, unknown>;
    };
    expect(layerArg.paint['heatmap-weight']).toBe(1);
  });

  it('weightProperty 지정 시 heatmap-weight 가 to-number expression 이다', () => {
    const map = createMockMap();
    render(<HeatmapLayer map={map as never} data={emptyFc} weightProperty="cnt" />);

    const layerArg = map.addLayer.mock.calls[0][0] as {
      paint: Record<string, unknown>;
    };
    expect(layerArg.paint['heatmap-weight']).toEqual([
      'to-number',
      ['get', 'cnt'],
      0,
    ]);
  });

  it('언마운트 시 layer 와 source 를 제거한다', () => {
    const map = createMockMap();
    const { unmount } = render(<HeatmapLayer map={map as never} data={emptyFc} />);
    unmount();

    expect(map.removeLayer).toHaveBeenCalledWith('heatmap-lyr');
    expect(map.removeSource).toHaveBeenCalledWith('heatmap-src');
  });
});
