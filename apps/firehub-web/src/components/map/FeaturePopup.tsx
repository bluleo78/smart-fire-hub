import maplibregl from 'maplibre-gl';
import { useEffect, useRef } from 'react';

import type { GeoJsonFeature } from '@/types/dataset';

interface FeaturePopupProps {
  map: maplibregl.Map;
  feature: GeoJsonFeature | null;
  onClose?: () => void;
}

const TITLE_KEYS = ['name', 'NAME', '이름', 'title', 'label', 'id'];

export function FeaturePopup({ map, feature, onClose }: FeaturePopupProps) {
  const popupRef = useRef<maplibregl.Popup | null>(null);
  const onCloseRef = useRef(onClose);

  useEffect(() => {
    onCloseRef.current = onClose;
  });

  useEffect(() => {
    if (popupRef.current) {
      popupRef.current.remove();
      popupRef.current = null;
    }

    if (!feature) return;

    const geom = feature.geometry as { type: string; coordinates: unknown };
    const lngLat = extractLngLat(geom);
    if (!lngLat) return;

    const html = buildPopupHtml(feature);

    const popup = new maplibregl.Popup({
      closeButton: true,
      maxWidth: '320px',
      className: 'sfh-popup',
    })
      .setLngLat(lngLat)
      .setHTML(html)
      .addTo(map);

    popup.on('close', () => {
      if (onCloseRef.current) onCloseRef.current();
    });

    popupRef.current = popup;

    return () => {
      popup.remove();
      popupRef.current = null;
    };
  }, [map, feature]);

  return null;
}

function extractLngLat(geom: { type: string; coordinates: unknown }): [number, number] | null {
  if (geom.type === 'Point') {
    const [lng, lat] = geom.coordinates as [number, number];
    return [lng, lat];
  }
  if (geom.type === 'LineString' || geom.type === 'MultiPoint') {
    const coords = geom.coordinates as [number, number][];
    return coords.length > 0 ? coords[0] : null;
  }
  if (geom.type === 'Polygon') {
    const ring = (geom.coordinates as [number, number][][])[0];
    return ring && ring.length > 0 ? ring[0] : null;
  }
  if (geom.type === 'MultiPolygon') {
    const ring = ((geom.coordinates as [number, number][][][])[0] ?? [])[0];
    return ring && ring.length > 0 ? ring[0] : null;
  }
  return null;
}

function buildPopupHtml(feature: GeoJsonFeature): string {
  const entries = Object.entries(feature.properties).filter(
    ([k]) => !k.startsWith('_'),
  );

  // Find a title from well-known keys
  const titleKey = TITLE_KEYS.find(k => feature.properties[k] != null);
  const title = titleKey ? escapeHtml(String(feature.properties[titleKey])) : null;

  // Properties rows (exclude title key to avoid duplication)
  const propRows = entries
    .filter(([k]) => k !== titleKey)
    .map(([k, v]) => {
      const val =
        v == null
          ? '<span class="sfh-null">null</span>'
          : escapeHtml(String(v));
      return `
        <div class="sfh-row">
          <span class="sfh-key">${escapeHtml(k)}</span>
          <span class="sfh-val">${val}</span>
        </div>`;
    })
    .join('');

  return `
    <style>
      .sfh-popup .maplibregl-popup-content {
        padding: 0 !important;
        border-radius: 10px !important;
        box-shadow: 0 4px 24px rgba(0,0,0,.12), 0 1px 4px rgba(0,0,0,.08) !important;
        overflow: hidden;
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      }
      .sfh-popup .maplibregl-popup-close-button {
        top: 8px; right: 8px;
        font-size: 18px;
        color: #64748b;
        width: 24px; height: 24px;
        display: flex; align-items: center; justify-content: center;
        border-radius: 4px;
        transition: background .15s;
      }
      .sfh-popup .maplibregl-popup-close-button:hover {
        background: rgba(0,0,0,.06);
        color: #0f172a;
      }
      .sfh-popup .maplibregl-popup-tip {
        border-top-color: #fff !important;
      }
      .sfh-header {
        padding: 12px 14px 10px;
        border-bottom: 1px solid #e2e8f0;
        background: #f8fafc;
      }
      .sfh-title {
        font-size: 14px;
        font-weight: 600;
        color: #0f172a;
        margin: 0;
        line-height: 1.3;
      }
      .sfh-body {
        padding: 8px 14px 12px;
        max-height: 200px;
        overflow-y: auto;
      }
      .sfh-row {
        display: flex;
        justify-content: space-between;
        gap: 12px;
        padding: 5px 0;
        border-bottom: 1px solid #f1f5f9;
        font-size: 12px;
        line-height: 1.4;
      }
      .sfh-row:last-child { border-bottom: none; }
      .sfh-key {
        color: #64748b;
        white-space: nowrap;
        flex-shrink: 0;
      }
      .sfh-val {
        color: #0f172a;
        text-align: right;
        word-break: break-all;
        font-weight: 500;
      }
      .sfh-null {
        color: #cbd5e1;
        font-style: italic;
        font-weight: 400;
      }
      .sfh-empty {
        color: #94a3b8;
        font-size: 12px;
        text-align: center;
        padding: 12px 0;
      }
    </style>
    ${title ? `<div class="sfh-header"><div class="sfh-title">${title}</div></div>` : ''}
    <div class="sfh-body">
      ${propRows || '<div class="sfh-empty">속성 정보 없음</div>'}
    </div>`;
}

function escapeHtml(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}
