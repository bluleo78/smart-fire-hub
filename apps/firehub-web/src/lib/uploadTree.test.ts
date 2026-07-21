import { describe, expect, it } from 'vitest';

import { collectEntries, filesToItems } from './uploadTree';

/** webkitRelativePath는 생성자로 못 넣어 defineProperty로 부여한다(폴더 선택 시 브라우저가 채우는 값 모사). */
function fileWithRel(name: string, rel?: string): File {
  const f = new File(['x'], name);
  if (rel !== undefined) Object.defineProperty(f, 'webkitRelativePath', { value: rel });
  return f;
}

/** FileSystemFileEntry 모의 — file(cb)로 File을 넘긴다. */
function fileEntry(name: string): FileSystemEntry {
  return {
    isFile: true,
    isDirectory: false,
    name,
    file: (ok: (f: File) => void) => ok(new File(['x'], name)),
  } as unknown as FileSystemEntry;
}

/** FileSystemDirectoryEntry 모의 — readEntries는 첫 호출에 자식 전체, 다음 호출에 빈 배열(배치 종료 모사). */
function dirEntry(name: string, children: FileSystemEntry[]): FileSystemEntry {
  return {
    isFile: false,
    isDirectory: true,
    name,
    createReader: () => {
      let served = false;
      return {
        readEntries: (ok: (e: FileSystemEntry[]) => void) => {
          if (served) return ok([]);
          served = true;
          ok(children);
        },
      };
    },
  } as unknown as FileSystemEntry;
}

describe('filesToItems', () => {
  it('webkitRelativePath가 있으면 상대경로를, 없으면 파일명을 path로 쓴다', () => {
    const items = filesToItems([fileWithRel('a.jpg'), fileWithRel('b.jpg', 'site-A/b.jpg')]);
    expect(items.map((i) => i.path)).toEqual(['a.jpg', 'site-A/b.jpg']);
  });
});

describe('collectEntries', () => {
  it('중첩 폴더를 재귀 순회해 상대경로를 보존한다', async () => {
    const tree = [
      dirEntry('site-A', [dirEntry('2026', [fileEntry('img.jpg')])]),
      fileEntry('root.txt'),
    ];
    const items = await collectEntries(tree);
    expect(items.map((i) => i.path)).toEqual(['site-A/2026/img.jpg', 'root.txt']);
  });

  it('한 폴더 안 여러 파일을 모두 수집한다', async () => {
    const items = await collectEntries([
      dirEntry('imgs', [fileEntry('a.png'), fileEntry('b.png')]),
    ]);
    expect(items.map((i) => i.path)).toEqual(['imgs/a.png', 'imgs/b.png']);
  });
});
