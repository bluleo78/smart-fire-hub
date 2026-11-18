import { createCanvas, loadImage } from '@napi-rs/canvas';
import { Chart } from 'chart.js';
import { describe, expect, it } from 'vitest';

import { renderChartPng } from './chart-png.js';

/**
 * WD-806: 모의 없이 실제 @napi-rs/canvas + chart.js 로 PNG 를 그려 검증한다.
 * (이전 구현은 canvas 네이티브 모듈 부재로 런타임에서만 깨졌으므로 실제 렌더 시험이 필요하다.)
 */
const barConfig = {
  type: 'bar',
  data: {
    labels: ['서울', '부산'],
    datasets: [{ label: '건수', data: [3, 5], backgroundColor: '#4dabf7' }],
  },
  options: {
    plugins: { title: { display: true, text: '지역별 건수' } },
  },
};

describe('renderChartPng', () => {
  it('PNG 시그니처와 요청한 크기(IHDR)의 이미지를 만든다', async () => {
    const buf = await renderChartPng(barConfig, 320, 200);

    // PNG 시그니처 89 50 4E 47
    expect([...buf.subarray(0, 4)]).toEqual([0x89, 0x50, 0x4e, 0x47]);
    // IHDR: 8바이트 시그니처 + 4 길이 + 4 'IHDR' 다음 너비·높이(빅엔디언)
    expect(buf.readUInt32BE(16)).toBe(320);
    expect(buf.readUInt32BE(20)).toBe(200);
  });

  it('배경이 투명이 아니라 흰색이다', async () => {
    const buf = await renderChartPng(barConfig, 320, 200);
    const img = await loadImage(buf);
    const canvas = createCanvas(img.width, img.height);
    const ctx = canvas.getContext('2d');
    ctx.drawImage(img, 0, 0);
    const [r, g, b, a] = ctx.getImageData(0, 0, 1, 1).data;

    expect([r, g, b, a]).toEqual([255, 255, 255, 255]);
  });

  it('잘못된 차트 type 이면 reject 하고 Chart 인스턴스를 남기지 않는다', async () => {
    await expect(
      renderChartPng({ ...barConfig, type: 'no-such-type' }, 200, 100),
    ).rejects.toThrow();
    // 생성자가 던진 뒤에도 Chart.instances 에 남던 누수(WD-806 리뷰)를 막았는지 확인
    expect(Object.keys(Chart.instances)).toHaveLength(0);
  });

  it('성공 렌더 뒤에도 Chart 인스턴스를 남기지 않는다', async () => {
    await renderChartPng(barConfig, 200, 100);
    expect(Object.keys(Chart.instances)).toHaveLength(0);
  });
});
