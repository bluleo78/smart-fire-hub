/**
 * 차트 SVG 엘리먼트를 SVG/PNG 파일로 내보내는 유틸리티.
 *
 * 왜 필요한가?
 * - recharts/nivo는 차트를 <svg>로 렌더하지만, 사용자에게 노출되는 다운로드 버튼이 없었다.
 *   분석가가 차트를 보고서·메일에 첨부하려면 OS 캡처 도구로 직접 화면을 잘라야 했다.
 *   (이슈 #74)
 *
 * 동작 방식:
 * - SVG 추출: 차트 컨테이너에서 첫 번째 <svg>를 찾아 XMLSerializer로 직렬화 후 Blob 다운로드.
 * - PNG 변환: 직렬화된 SVG를 data URL로 만들어 Image에 로드하고 Canvas에 그린 뒤 toBlob.
 *   computed style을 inline 스타일로 복사하지 않으면 외부 CSS(Tailwind 토큰 등)가 누락되므로
 *   주요 stroke/fill/font 속성을 수동으로 inline 처리한다.
 */
import { downloadBlob } from './download';

/** 컨테이너에서 차트 SVG 엘리먼트를 찾는다 (recharts/nivo 모두 SVG 기반). */
export function findChartSvg(container: HTMLElement | null): SVGSVGElement | null {
  if (!container) return null;
  return container.querySelector('svg');
}

/**
 * SVG 엘리먼트를 자체 완결형 문자열로 직렬화.
 * - viewBox/width/height 보장
 * - computed style 일부를 inline 처리 → 외부 CSS 누락 방지
 */
function serializeSvg(svg: SVGSVGElement): string {
  const clone = svg.cloneNode(true) as SVGSVGElement;

  // 원본 svg와 clone 모두 동일 트리 순서이므로 동시 순회로 computed style 복사.
  const originals = svg.querySelectorAll<SVGElement>('*');
  const clones = clone.querySelectorAll<SVGElement>('*');
  // 직렬화 시 보존할 핵심 시각 속성만 복사 (전체 복사 시 결과 파일이 비대해짐)
  const COPY_PROPS = [
    'fill',
    'stroke',
    'stroke-width',
    'stroke-dasharray',
    'stroke-linecap',
    'stroke-linejoin',
    'opacity',
    'fill-opacity',
    'stroke-opacity',
    'font-size',
    'font-family',
    'font-weight',
    'text-anchor',
  ];
  originals.forEach((node, idx) => {
    const target = clones[idx];
    if (!target) return;
    const computed = window.getComputedStyle(node);
    const styles: string[] = [];
    COPY_PROPS.forEach((prop) => {
      const value = computed.getPropertyValue(prop);
      if (value) styles.push(`${prop}: ${value}`);
    });
    if (styles.length > 0) {
      const existing = target.getAttribute('style') ?? '';
      target.setAttribute('style', `${existing};${styles.join(';')}`);
    }
  });

  // width/height/viewBox 보강
  const rect = svg.getBoundingClientRect();
  if (!clone.getAttribute('width')) clone.setAttribute('width', String(rect.width));
  if (!clone.getAttribute('height')) clone.setAttribute('height', String(rect.height));
  if (!clone.getAttribute('xmlns')) clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
  if (!clone.getAttribute('viewBox')) {
    clone.setAttribute('viewBox', `0 0 ${rect.width} ${rect.height}`);
  }

  return new XMLSerializer().serializeToString(clone);
}

/** 차트 SVG를 .svg 파일로 다운로드. */
export function exportChartAsSvg(svg: SVGSVGElement, filename: string): void {
  const serialized = serializeSvg(svg);
  const blob = new Blob([serialized], { type: 'image/svg+xml;charset=utf-8' });
  downloadBlob(filename.endsWith('.svg') ? filename : `${filename}.svg`, blob);
}

/**
 * 차트 SVG를 PNG로 변환해 다운로드.
 * @param scale 캔버스 픽셀 배율(고해상도 출력용, 기본 2배)
 */
export async function exportChartAsPng(
  svg: SVGSVGElement,
  filename: string,
  scale = 2
): Promise<void> {
  const serialized = serializeSvg(svg);
  const rect = svg.getBoundingClientRect();
  const width = rect.width || 800;
  const height = rect.height || 400;

  // base64 인코딩으로 data URL 생성 (한글 등 멀티바이트 안전)
  const base64 = window.btoa(unescape(encodeURIComponent(serialized)));
  const dataUrl = `data:image/svg+xml;base64,${base64}`;

  // SVG → Image → Canvas → PNG Blob
  const img = new Image();
  img.crossOrigin = 'anonymous';
  await new Promise<void>((resolve, reject) => {
    img.onload = () => resolve();
    img.onerror = () => reject(new Error('SVG → Image 로드 실패'));
    img.src = dataUrl;
  });

  const canvas = document.createElement('canvas');
  canvas.width = Math.ceil(width * scale);
  canvas.height = Math.ceil(height * scale);
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('Canvas 2D context를 가져올 수 없습니다.');

  // 차트 배경이 투명하면 PNG에서 검게 보일 수 있어 흰색으로 채움
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.scale(scale, scale);
  ctx.drawImage(img, 0, 0, width, height);

  const blob = await new Promise<Blob | null>((resolve) =>
    canvas.toBlob((b) => resolve(b), 'image/png')
  );
  if (!blob) throw new Error('PNG Blob 변환 실패');
  downloadBlob(filename.endsWith('.png') ? filename : `${filename}.png`, blob);
}

/** 파일명용 안전 문자열로 변환 (공백·특수문자 → 언더스코어). */
export function sanitizeFilename(name: string): string {
  return name.replace(/[\\/:*?"<>|\s]+/g, '_').replace(/^_+|_+$/g, '') || 'chart';
}
