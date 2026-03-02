import type maplibregl from 'maplibre-gl';
import { useEffect, useRef } from 'react';

import type { GeoJsonFeature, GeoJsonFeatureCollection } from '@/types/dataset';

const SOURCE_ID = 'geojson-layer-source';
const LAYER_POINT = 'geojson-layer-point';
const LAYER_LINE = 'geojson-layer-line';
const LAYER_FILL = 'geojson-layer-fill';
const LAYER_FILL_OUTLINE = 'geojson-layer-fill-outline';

interface GeoJsonLayerProps {
  map: maplibregl.Map;
  data: GeoJsonFeatureCollection;
  onFeatureClick?: (feature: GeoJsonFeature) => void;
}

function fitBounds(map: maplibregl.Map, data: GeoJsonFeatureCollection) {
  if (data.features.length === 0) return;
  let minLon = Infinity, minLat = Infinity, maxLon = -Infinity, maxLat = -Infinity;

  for (const feature of data.features) {
    const geom = feature.geometry as { type: string; coordinates: unknown };
    if (!geom || !geom.coordinates) continue;
    const coords = flattenCoordinates(geom.type, geom.coordinates);
    for (const [lon, lat] of coords) {
      if (lon < minLon) minLon = lon;
      if (lat < minLat) minLat = lat;
      if (lon > maxLon) maxLon = lon;
      if (lat > maxLat) maxLat = lat;
    }
  }

  if (minLon === Infinity) return;
  if (minLon === maxLon && minLat === maxLat) {
    map.flyTo({ center: [minLon, minLat], zoom: 13 });
  } else {
    map.fitBounds([[minLon, minLat], [maxLon, maxLat]], { padding: 60, maxZoom: 15 });
  }
}

function flattenCoordinates(geomType: string, coords: unknown): [number, number][] {
  if (geomType === 'Point') return [coords as [number, number]];
  if (geomType === 'LineString' || geomType === 'MultiPoint') return coords as [number, number][];
  if (geomType === 'Polygon' || geomType === 'MultiLineString') {
    return (coords as [number, number][][]).flat();
  }
  if (geomType === 'MultiPolygon') {
    return (coords as [number, number][][][]).flat(2);
  }
  return [];
}

function addLayers(map: maplibregl.Map) {
  if (!map.getSource(SOURCE_ID)) {
    map.addSource(SOURCE_ID, { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
  }

  if (!map.getLayer(LAYER_FILL)) {
    map.addLayer({
      id: LAYER_FILL,
      type: 'fill',
      source: SOURCE_ID,
      filter: ['in', ['geometry-type'], ['literal', ['Polygon', 'MultiPolygon']]],
      paint: {
        'fill-color': '#3b82f6',
        'fill-opacity': 0.35,
      },
    });
  }
  if (!map.getLayer(LAYER_FILL_OUTLINE)) {
    map.addLayer({
      id: LAYER_FILL_OUTLINE,
      type: 'line',
      source: SOURCE_ID,
      filter: ['in', ['geometry-type'], ['literal', ['Polygon', 'MultiPolygon']]],
      paint: {
        'line-color': '#1d4ed8',
        'line-width': 1.5,
      },
    });
  }
  if (!map.getLayer(LAYER_LINE)) {
    map.addLayer({
      id: LAYER_LINE,
      type: 'line',
      source: SOURCE_ID,
      filter: ['in', ['geometry-type'], ['literal', ['LineString', 'MultiLineString']]],
      paint: {
        'line-color': '#3b82f6',
        'line-width': 2,
      },
    });
  }
  if (!map.getLayer(LAYER_POINT)) {
    map.addLayer({
      id: LAYER_POINT,
      type: 'circle',
      source: SOURCE_ID,
      filter: ['in', ['geometry-type'], ['literal', ['Point', 'MultiPoint']]],
      paint: {
        'circle-radius': 6,
        'circle-color': '#3b82f6',
        'circle-stroke-width': 1.5,
        'circle-stroke-color': '#ffffff',
      },
    });
  }
}

function removeLayers(map: maplibregl.Map) {
  for (const id of [LAYER_POINT, LAYER_LINE, LAYER_FILL_OUTLINE, LAYER_FILL]) {
    if (map.getLayer(id)) map.removeLayer(id);
  }
  if (map.getSource(SOURCE_ID)) map.removeSource(SOURCE_ID);
}

export function GeoJsonLayer({ map, data, onFeatureClick }: GeoJsonLayerProps) {
  const onFeatureClickRef = useRef(onFeatureClick);

  useEffect(() => {
    onFeatureClickRef.current = onFeatureClick;
  });

  useEffect(() => {
    function setup() {
      addLayers(map);
      const src = map.getSource(SOURCE_ID) as maplibregl.GeoJSONSource | undefined;
      if (src) src.setData(data as unknown as Parameters<typeof src.setData>[0]);
      fitBounds(map, data);
    }

    if (map.isStyleLoaded()) {
      setup();
    } else {
      map.once('style.load', setup);
    }

    return () => {
      // Attempt cleanup — safe to call even if layers don't exist
      if (map.isStyleLoaded()) {
        try { removeLayers(map); } catch { /* ignore */ }
      }
    };
  // Re-run when data reference changes
   
  }, [map, data]);

  // Click handler
  useEffect(() => {
    const clickableLayers = [LAYER_POINT, LAYER_LINE, LAYER_FILL];

    function handleClick(e: maplibregl.MapMouseEvent & { features?: maplibregl.MapGeoJSONFeature[] }) {
      if (!e.features || e.features.length === 0) return;
      const feature = e.features[0];
      if (onFeatureClickRef.current) {
        onFeatureClickRef.current({
          type: 'Feature',
          geometry: feature.geometry as unknown as Record<string, unknown>,
          properties: (feature.properties ?? {}) as Record<string, unknown>,
        });
      }
    }

    function handleMouseEnter() {
      map.getCanvas().style.cursor = 'pointer';
    }
    function handleMouseLeave() {
      map.getCanvas().style.cursor = '';
    }

    for (const layer of clickableLayers) {
      map.on('click', layer, handleClick);
      map.on('mouseenter', layer, handleMouseEnter);
      map.on('mouseleave', layer, handleMouseLeave);
    }

    return () => {
      for (const layer of clickableLayers) {
        map.off('click', layer, handleClick);
        map.off('mouseenter', layer, handleMouseEnter);
        map.off('mouseleave', layer, handleMouseLeave);
      }
    };
  }, [map]);

  return null;
}
