# Smart Data Platform — 브랜딩 산출물 (K8S, 설정 전용)

단일 이미지를 유지한 채 **설정만으로** "Smart Data Platform" 브랜드로 배포하기 위한 산출물.
이미지 재빌드 불필요 — web 은 `/config.js` 교체, 백엔드는 env 주입.

## 파일
| 파일 | 용도 |
|------|------|
| `config.js` | web 런타임 브랜딩 override (정본). `window.__APP_CONFIG__` 소비 |
| `web-branding-configmap.yaml` | 위 `config.js` 를 담은 K8S ConfigMap (`web-branding`) |
| `deployment-env-patch.yaml` | web volume 마운트 + api/ai-agent env 패치 예시 |

## 주입 지점 3곳
| 대상 | 방식 | 값 |
|------|------|-----|
| **web** | `/usr/share/nginx/html/config.js` 교체 (ConfigMap subPath 마운트) | `brandName: 'Smart Data Platform'` |
| **firehub-api** | env `APP_BRANDING_NAME` | `Smart Data Platform` |
| **firehub-ai-agent** | env `BRAND_NAME` | `Smart Data Platform` |

## 적용
```bash
# 1) web config.js ConfigMap 생성
kubectl apply -f web-branding-configmap.yaml            # 필요 시 -n <namespace>

# 2) 세 Deployment 에 설정 추가 (이름/namespace 는 대상 환경에 맞춰 조정)
kubectl patch deployment firehub-web -p "$(cat deployment-env-patch.yaml)"   # 또는 kustomize patch
kubectl patch deployment firehub-api      -p '{"spec":{"template":{"spec":{"containers":[{"name":"api","env":[{"name":"APP_BRANDING_NAME","value":"Smart Data Platform"}]}]}}}}'
kubectl patch deployment firehub-ai-agent -p '{"spec":{"template":{"spec":{"containers":[{"name":"ai-agent","env":[{"name":"BRAND_NAME","value":"Smart Data Platform"}]}]}}}}'
```

## 확인
- 브라우저 새로고침 → 탭 타이틀·로그인/사이드바 브랜드가 "Smart Data Platform"
- `/config.js` 는 이미지 기본 nginx.conf 에 `Cache-Control: no-store` 가 구워져 있어 ConfigMap 갱신 후 즉시 반영
- api 재기동 후 리포트/알림 브랜드 표기 변경, ai-agent 재기동 후 어시스턴트 자기소개 변경

## 주의 — AI 페르소나 DB 시드는 env 로 안 바뀜
일반 채팅 시스템 프롬프트 뒤에 append 되는 DB 값 `ai.system_prompt`(V69 시드)는
"당신은 Smart Fire Hub의 AI 어시스턴트입니다." 문구를 담고 있으며 **`BRAND_NAME` 으로 덮이지 않는다**.
이는 관리자가 설정 화면에서 직접 편집하는 DB 콘텐츠이므로, 화이트라벨 시 **관리자 설정 화면에서
`ai.system_prompt` 의 페르소나 문구를 "Smart Data Platform" 으로 수정**해야 자기소개까지 완전히 바뀐다.

## 로고 / 파비콘 (선택)
`config.js` 의 `logoUrl`(null=기본 Flame 아이콘)·`faviconUrl` 만 바꾸면 된다.
자체 에셋은 동일 오리진으로 서빙되는 경로(별도 ConfigMap/볼륨 마운트 또는 오브젝트 스토리지 공개 URL)를
지정한다. 파비콘은 SVG 권장(`.png/.ico` 사용 시 `link.type` 도 함께 조정).

> 참고: 전체 화이트라벨 배경·컨벤션은 리포지토리 `.claude/docs/deploy.md` 의 "사이트별 브랜딩(화이트라벨)" 절 참조.
