# PostGIS 16 이미지에 pgvector 확장을 추가한다.
# imresamu/postgis는 Debian + PGDG apt 소스를 포함하므로 패키지 설치로 pgvector 추가 가능.
# 베이스는 16-3.6.1-trixie(PG16·PostGIS 3.6.1·debian 13)다(WD-794): 옛 16-3.5(debian 12) 태그는 2025-09 뒤로 갱신되지 않아
# --pull 로도 보안 수정을 받지 못했고, PG16+PostGIS 3.5 의 trixie 태그는 없다. 옛 볼륨을 이 이미지로 열면 glibc 가 바뀌어
# 콜레이션 재색인·PostGIS 확장 갱신이 필요하다 — 절차는 .claude/docs/deploy.md 「postgres 베이스를 PostGIS 3.6·debian 13 으로 옮긴 뒤」.
# 태그는 PostGIS 판(3.6.1)까지 고정한다: 움직이는 16-3.6-trixie 태그는 다시 빌드되면 PostGIS 3.6.x 로 올라가 운영 카탈로그에
# ALTER EXTENSION 이 다시 필요해지고 postgis-safe-functions.txt 허용목록도 어긋난다. PostGIS 판을 올릴 때는 이 줄과 허용목록을 함께 바꾼다.
FROM imresamu/postgis:16-3.6.1-trixie

USER root
# 베이스 태그도 자주 다시 빌드되지 않아(16-3.6.1-trixie 는 2026-02 빌드) 그 뒤에 나온 debian 보안 수정(openssl·gnutls·perl·
# mariadb 라이브러리 등)이 빠져 있다 — apt-get upgrade 로 받는다(WD-794). postgis 패키지는 hold 해 PostGIS 를 3.6.1 그대로 둔다:
# PGDG 가 더 새 PostGIS 를 내면 upgrade 가 확장 판을 올려 운영 DB 에 ALTER EXTENSION 이 다시 필요해지기 때문이다.
# postgresql-16 은 hold 하지 않는다 — PG 마이너 판(16.x)은 보안 수정이라 받는 쪽이 낫고 데이터 디렉터리 호환도 같다.
# --force-unsafe-io 는 dpkg 의 fsync 를 끄는 빌드 속도용 옵션이다(이미지 내용은 같다).
# OS_PATCH_DATE 는 이 층의 캐시 무효화 키다 — 이유는 scripts/deploy.sh 머리 주석. 기본값이면 개발·시험 빌드는 캐시를 쓴다.
ARG OS_PATCH_DATE=static
RUN echo "os patch date: ${OS_PATCH_DATE}" \
    && export DEBIAN_FRONTEND=noninteractive \
    && apt-get update \
    && apt-mark hold postgis postgresql-16-postgis-3 postgresql-16-postgis-3-scripts \
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
USER postgres
