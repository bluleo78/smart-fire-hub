import 'maplibre-gl/dist/maplibre-gl.css';

import maplibregl from 'maplibre-gl';
import { useTheme } from 'next-themes';
import { useEffect, useRef } from 'react';

const LIGHT_STYLE = 'https://tiles.openfreemap.org/styles/liberty';
const DARK_STYLE = 'https://tiles.openfreemap.org/styles/liberty';

interface MapViewProps {
  className?: string;
  onMapReady?: (map: maplibregl.Map) => void;
}

export function MapView({ className, onMapReady }: MapViewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const { resolvedTheme } = useTheme();

  // Initialize map once
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    const map = new maplibregl.Map({
      container: containerRef.current,
      style: LIGHT_STYLE,
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
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const style = resolvedTheme === 'dark' ? DARK_STYLE : LIGHT_STYLE;
    if (map.isStyleLoaded()) {
      map.setStyle(style);
    }
  }, [resolvedTheme]);

  return (
    <div
      ref={containerRef}
      className={className ?? 'w-full h-full min-h-[400px]'}
    />
  );
}
