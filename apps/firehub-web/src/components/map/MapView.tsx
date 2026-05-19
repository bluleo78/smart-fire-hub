import 'maplibre-gl/dist/maplibre-gl.css';

import maplibregl from 'maplibre-gl';
import { useTheme } from 'next-themes';
import { useEffect, useRef } from 'react';

const LIGHT_STYLE = 'https://tiles.openfreemap.org/styles/liberty';
// 다크 테마 전용 스타일 URL — OpenFreeMap의 dark 스타일(배경 rgb(12,12,12))
const DARK_STYLE = 'https://tiles.openfreemap.org/styles/dark';

interface MapViewProps {
  className?: string;
  onMapReady?: (map: maplibregl.Map) => void;
}

export function MapView({ className, onMapReady }: MapViewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const { resolvedTheme } = useTheme();
  // 현재 적용된 style URL 을 DOM data attribute 로 노출 — E2E 가 결정적으로 대기하기 위한 신호
  const appliedStyleRef = useRef<string>(LIGHT_STYLE);

  // Initialize map once — 초기 스타일을 현재 테마에 맞춰 선택 (mount 직후 dark 일 때 liberty 를 불필요하게 fetch 하지 않도록)
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    const initialStyle = resolvedTheme === 'dark' ? DARK_STYLE : LIGHT_STYLE;
    appliedStyleRef.current = initialStyle;
    containerRef.current.dataset.mapStyle =
      initialStyle === DARK_STYLE ? 'dark' : 'light';

    const map = new maplibregl.Map({
      container: containerRef.current,
      style: initialStyle,
      center: [127.0, 37.5], // Seoul default
      zoom: 10,
    });

    map.addControl(new maplibregl.NavigationControl(), 'top-right');

    map.on('load', () => {
      if (onMapReady) onMapReady(map);
    });

    mapRef.current = map;

    // ResizeObserver for responsive layout
    const ro = new ResizeObserver(() => {
      map.resize();
    });
    ro.observe(containerRef.current);

    return () => {
      ro.disconnect();
      map.remove();
      mapRef.current = null;
    };
    // onMapReady intentionally omitted — only run on mount
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Dark mode: reload style when theme changes
  // 초기 init 직후 style 이 아직 로드되기 전 테마가 변경되는 race 를 방지하기 위해
  // isStyleLoaded() 가 false 이면 'load' 이벤트를 기다렸다가 적용한다.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const style = resolvedTheme === 'dark' ? DARK_STYLE : LIGHT_STYLE;
    if (style === appliedStyleRef.current) return;

    const apply = () => {
      // 다시 한 번 ref 를 확인해 unmount 이후 호출 방지
      if (!mapRef.current) return;
      mapRef.current.setStyle(style);
      appliedStyleRef.current = style;
      if (containerRef.current) {
        containerRef.current.dataset.mapStyle =
          style === DARK_STYLE ? 'dark' : 'light';
      }
    };

    if (map.isStyleLoaded()) {
      apply();
    } else {
      map.once('load', apply);
    }
  }, [resolvedTheme]);

  return (
    <div
      ref={containerRef}
      className={className ?? 'w-full h-full min-h-[400px]'}
      data-map-style="light"
    />
  );
}
