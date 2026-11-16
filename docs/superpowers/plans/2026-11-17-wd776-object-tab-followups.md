# WD-776 FILE 데이터셋 오브젝트 탭·데이터셋 목록 후속

이슈: https://genia-works.iacloud.kr/projects/WD/issues/776
워크트리: `.claude/worktrees/WD-776` (브랜치 `fix/wd-776`, base origin/main 3a3c8c2e8)
배경: 통합 PR #1034 의 WD-761(오브젝트 탭 폴더 탐색·이름 검색)·WD-754(임시 데이터셋 기본 숨김) 리뷰에서 나온 막지 않는 결함 6건.

## 공통 규칙 (하위 에이전트 브리프에 그대로)
- 셸 명령은 단순하게: 한 줄 셸 함수 정의(`f(){ …; }`), `${…:-{…}}` 같은 중괄호 확장, `;`·`&&` 로 길게 이은 한 줄 금지. 복잡하면 스크립트 파일로.
- 한국어 주석 필수(무엇을·왜), 이슈 키 WD-776.
- 커밋·push·`git add -A`·`git stash` 금지. 저장소 전체 포매터 금지(바꾼 파일만).
- `pnpm dev`·`pnpm db:*`·`docker compose down` 금지, dev 포트 5xxx 건드리지 않음, 공용 테스트 락 파일(`.git/firehub-test-gate.lock` 등) 절대 지우지 않음.
- E2E 는 `PW_PORT=6776`. 지금 다른 게이트가 E2E 락을 쥐고 있으면(06:00 KST 무렵까지) E2E 실행은 하지 말고 spec 작성·`npx tsc -p apps/firehub-web/tsconfig.e2e.json --noEmit` 까지만.

## 내가 정한 것
- 2번(공백뿐·`.` 폴더): **열리게** 한다 — 목록에 보이는 폴더는 들어갈 수 있어야 하고, 빼면 그 안 파일은 검색으로만 닿는다. 서버·웹 정규화가 그 세그먼트를 버리지 않고 그대로 보존한다(`..` 는 계속 거부 — 경로 탈출 오해 방지). 단, 열리게 하는 것이 키 안전성을 해친다고 판단되면(근거를 보고) 목록에서 빼는 쪽으로 바꾼다.
- 1번(`\` 폴더): 폴더 경로에서 `\` 를 `/` 로 바꾸지 않는다(서버 normalizeFolderPath·웹 toFolderParam 둘 다). 파일명 정제(generateKey 의 파일명 쪽)는 지금 규칙 유지.
- 3번(폴더 안 긴 이름): 루트처럼 **파일명 쪽을 잘라** 올린다(확장자 보존, 폴더는 자르지 않음). 폴더만으로 상한에 가까워 파일명이 들어갈 자리가 없을 때(예: 남은 바이트 < 확장자+16)만 그 파일 하나를 거부하고 묶음의 나머지는 진행한다 — 응답 모양은 기존 upload-urls 응답을 확인해 파일별 실패를 실을 수 있으면 싣고, 없으면 그 파일을 400 대신 결과에서 빼는 방법 중 웹이 실패를 보여 줄 수 있는 쪽.
- 4번: 첫 쪽 재조회 실패(데이터가 이미 있고 refetch 가 실패)는 「목록을 새로 불러오지 못했습니다. 다시 시도하세요.」, 다음 쪽 실패만 「다음 목록을 불러오지 못했습니다…」.
- 6번: 출처 필터가 **무엇이든 골라져 있으면**(지금은 「임시」만) 「임시 데이터셋 보기」를 비활성으로 두고 같은 툴팁 방식으로 이유를 보이며, 빈 목록 안내에서 토글을 켜라는 문장을 뺀다.

## Task 1 — api (ObjectKeyGenerator·FileObjectStorageService + 단위 시험)
1. normalizeFolderPath 가 `\` 를 바꾸지 않고, 공백뿐·`.` 세그먼트를 보존(`..` 거부 유지, 빈 세그먼트 `a//b` 는 지금처럼 흡수할지 확인해 목록에 그런 폴더가 보이는지에 맞춤). 시험: `report\2024`, ` `(공백), `.` 폴더 경로가 그대로 prefix 로.
2. 위 폴더의 목록·업로드가 그 prefix 를 쓰는지(서비스 시험).
3. generateKey(prefix, folder, filename): 폴더 + 파일 > 900B 이면 파일명 쪽을 잘라 맞춤(확장자 보존). 자리가 없으면 그 파일만 실패 — upload-urls 묶음은 나머지를 계속. 시험: 긴 이름 하나 + 짧은 이름 하나 묶음 → 둘 다 URL(긴 것은 잘린 키), 폴더가 거의 상한인 경우 → 그 파일만 실패.
5. searchObjects: 상한에 닿았으면 `remaining = it.hasNext()` 를 부르지 않는다(다음 S3 쪽을 받지 않게). hasMore 판정은 상한에 닿았다는 것으로(또는 「더 있을 수 있음」). 시험: S3 목 페이지 호출 수 단정.

## Task 2 — web (objectFile.ts·DatasetObjectsTab.tsx·DatasetListPage.tsx + E2E)
1·2. toFolderParam 이 `\` 를 보존하고 공백뿐·`.` 세그먼트를 버리지 않게(서버와 같은 규칙). URL 파라미터 인코딩 확인. E2E(`e2e/pages/data/` 의 기존 오브젝트 탭 spec·fixture 재사용): `report\2024`·공백·`.` 폴더를 눌러 들어가면 그 prefix 로 목록 API 가 불리고(요청 파라미터 캡처) 브레드크럼이 맞음, 그 폴더에서 업로드하면 upload-urls 요청의 folder 값이 그 경로.
3. 업로드 결과에 파일별 실패가 오면 그 파일만 실패로 보이고 나머지는 올라감(E2E: 응답 목으로).
4. 첫 쪽 재조회 실패 vs 다음 쪽 실패 안내 구분(E2E: 업로드 뒤 invalidate 재조회 500 → 「목록을 새로 불러오지 못했습니다」, 다음 쪽 500 → 기존 문구).
6. DatasetListPage: originType 이 비어 있지 않으면 토글 비활성 + 이유 툴팁, 빈 목록 안내에서 토글 문장 생략. E2E: 출처 「원본」 선택 → 토글 disabled·aria 설명, 빈 목록 문구.

## 확인
- api: `cd apps/firehub-api && ./gradlew test --tests "*ObjectKeyGenerator*" --tests "*FileObjectStorage*"`(관련 시험 클래스 이름은 확인)
- web: `pnpm -C apps/firehub-web typecheck`, `npx tsc -p apps/firehub-web/tsconfig.e2e.json --noEmit`, 바꾼 파일 eslint, (락이 풀리면) 대상 spec E2E.

## Review Focus
- `\`·공백·`.` 보존이 `..`·절대경로·빈 세그먼트 처리와 섞여 경로 탈출이나 다른 폴더 가리키기를 만들지 않는가.
- 서버·웹 정규화가 같은 결과를 내는가(목록의 폴더 이름 → 들어갈 때 파라미터 → 서버 prefix 가 한 바퀴 돌아 같은가).
- 긴 이름 자르기가 UTF-8 경계·확장자를 지키고, 같은 이름이 잘려 같은 키가 되는 충돌을 기존 규칙(루트)과 같게 다루는가.
- searchObjects 상한 변경이 hasMore(「더 있음」) 표시를 틀리게 하지 않는가.
- 6번 토글 비활성이 URL 의 showTemp 와 어긋날 때(출처 필터 + showTemp=1) 목록 요청이 지금과 같은가.
