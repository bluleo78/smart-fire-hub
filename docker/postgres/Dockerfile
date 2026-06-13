# PostGIS 16 이미지에 pgvector 확장을 추가한다.
# imresamu/postgis는 Debian + PGDG apt 소스를 포함하므로 패키지 설치로 pgvector 추가 가능.
FROM imresamu/postgis:16-3.5

USER root
# 재현성을 위해 pgvector 버전을 고정한다 (PGDG bookworm 기준 candidate).
RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-16-pgvector=0.8.2-1.pgdg12+1 \
    && rm -rf /var/lib/apt/lists/*
USER postgres
