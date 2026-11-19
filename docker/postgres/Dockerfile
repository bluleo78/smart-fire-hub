# gosu 를 최신 Go 로 다시 빌드하는 스테이지(WD-833).
# 베이스 이미지의 gosu 는 go1.24.6 으로 빌드돼 Go stdlib 의 HIGH·CRITICAL CVE 가 남는다 — 같은 소스(1.20 태그)를
# 최신 Go 로 정적 빌드해 최종 이미지의 /usr/local/bin/gosu 를 덮어쓴다. 태그가 가리키는 커밋을 확인해 소스 바꿔치기를 막는다.
# Go 판은 고정한다(재현성). trivy 가 gosu 의 stdlib CVE 를 다시 잡으면 golang 태그만 수정판으로 올린다.
# BuildKit 전용 문법은 쓰지 않는다: Testcontainers 가 레거시 빌더로 이 파일을 빌드할 수 있다.
FROM golang:1.27.4-trixie AS gosu
WORKDIR /src
RUN git clone --depth 1 --branch 1.20 https://github.com/tianon/gosu.git . \
    && test "$(git rev-parse HEAD)" = "29b939aa5bd4dc588af765b8248f6e39150d2565" \
    && CGO_ENABLED=0 go build -trimpath -mod=readonly -ldflags '-d -w' -o /out/gosu .

# PostGIS 16 이미지에 pgvector 확장을 추가한다.
# imresamu/postgis는 Debian + PGDG apt 소스를 포함하므로 패키지 설치로 pgvector 추가 가능.
# 베이스는 16-3.6.1-trixie(PG16·PostGIS 3.6.1·debian 13)다(WD-794): 옛 16-3.5(debian 12) 태그는 2025-09 뒤로 갱신되지 않아
# --pull 로도 보안 수정을 받지 못했고, PG16+PostGIS 3.5 의 trixie 태그는 없다. 옛 볼륨을 이 이미지로 열면 glibc 가 바뀌어
# 콜레이션 재색인·PostGIS 확장 갱신이 필요하다 — 절차는 .claude/docs/deploy.md 「postgres 베이스를 PostGIS 3.6·debian 13 으로 옮긴 뒤」.
# 태그는 PostGIS 판(3.6.1)까지 고정한다: 움직이는 16-3.6-trixie 태그는 다시 빌드되면 PostGIS 3.6.x 로 올라가 운영 카탈로그에
# ALTER EXTENSION 이 다시 필요해지고 postgis-safe-functions.txt 허용목록도 어긋난다. PostGIS 판을 올릴 때는 이 줄과 허용목록을 함께 바꾼다.
FROM imresamu/postgis:16-3.6.1-trixie

USER root
# postgis 패키지를 hold 해 PostGIS 를 3.6.1 그대로 둔다: PGDG 가 더 새 PostGIS 를 내면 upgrade·install 이 확장 판을 올려
# 운영 DB 에 ALTER EXTENSION 이 다시 필요해지고 postgis-safe-functions.txt 허용목록도 어긋나기 때문이다.
# hold 를 upgrade 와 다른 층(그 앞)에 두는 이유(WD-798): upgrade 를 건너뛰는 시험 빌드(OS_PATCH=skip)에서도 아래 pgvector 층의
# apt-get install 이 의존성을 따라 PostGIS 를 올리지 못하게 하려는 것이다. apt-mark 는 dpkg 상태만 바꿔 apt 목록 없이도 된다.
# postgresql-16 은 hold 하지 않는다 — PG 마이너 판(16.x)은 보안 수정이라 받는 쪽이 낫고 데이터 디렉터리 호환도 같다.
RUN apt-mark hold postgis postgresql-16-postgis-3 postgresql-16-postgis-3-scripts
# 베이스 태그도 자주 다시 빌드되지 않아(16-3.6.1-trixie 는 2026-02 빌드) 그 뒤에 나온 debian 보안 수정(openssl·gnutls·perl·
# mariadb 라이브러리 등)이 빠져 있다 — apt-get upgrade 로 받는다(WD-794).
# 기본값은 「패치함」(OS_PATCH=apply)이다(WD-798): 인자 없는 빌드(운영 compose 의 db, scripts/work-loop/compose.shared.yml,
# 손 빌드)가 모두 보안 수정을 받게 하려는 것이다. 건너뛰기(OS_PATCH=skip)는 시험 이미지만 쓴다 — Gradle(build.gradle.kts 의
# 시험 이미지 준비)이 넘기며, upgrade(~15분)를 시험마다 기다리지 않게 한다. 그래서 시험 이미지는 PostGIS(3.6.1, hold)·pgvector 판은
# 운영과 같지만 PG 마이너(16.x)·GEOS·PROJ 같은 라이브러리가 운영보다 옛 판일 수 있다(.claude/docs/deploy.md 「Docker 빌드 규칙」).
# OS_PATCH_DATE 는 이 층의 캐시 무효화 키일 뿐이다(값과 상관없이 upgrade 한다) — 이유는 scripts/deploy.sh 머리 주석.
# --force-unsafe-io 는 dpkg 의 fsync 를 끄는 빌드 속도용 옵션이다(이미지 내용은 같다).
ARG OS_PATCH=apply
ARG OS_PATCH_DATE=static
RUN if [ "${OS_PATCH}" = "skip" ]; then echo "os patch: skipped (OS_PATCH=skip, test image)"; exit 0; fi \
    && echo "os patch date: ${OS_PATCH_DATE}" \
    && export DEBIAN_FRONTEND=noninteractive \
    && apt-get update \
    && apt-get upgrade -y --no-install-recommends \
       -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-unsafe-io \
    && rm -rf /var/lib/apt/lists/*
# pgvector 설치는 층을 나눈다 — 판을 바꿔도 위의 오래 걸리는 upgrade 층(~15분)은 캐시를 그대로 쓴다.
# 재현성을 위해 pgvector 의 업스트림 버전(0.8.7)을 고정한다.
# 데비안 리비전(-1.pgdg13+N)까지 고정하지 않는 이유: PGDG 는 같은 업스트림의 옛 리비전을 저장소에서 내려(0.8.6-1.pgdg12+1 이 사라져 이미지 빌드가 깨졌다)
# 리비전 고정은 다음 재빌드 때 다시 깨진다. 리비전은 패키징 수정뿐이라 업스트림 버전만 고정해도 확장 동작은 같다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-16-pgvector=0.8.7-* \
    && rm -rf /var/lib/apt/lists/*
# 위 gosu 스테이지에서 새로 빌드한 바이너리로 베이스의 gosu 를 덮어쓴다(WD-833).
# 바로 실행해 판 출력과 uid 전환(nobody)이 되는지 빌드 중에 확인한다.
COPY --from=gosu /out/gosu /usr/local/bin/gosu
RUN gosu --version && gosu nobody true
USER postgres
