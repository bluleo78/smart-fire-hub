# PostGIS 16 이미지에 pgvector 확장을 추가한다.
# imresamu/postgis는 Debian + PGDG apt 소스를 포함하므로 패키지 설치로 pgvector 추가 가능.
FROM imresamu/postgis:16-3.5

USER root
# 재현성을 위해 pgvector 의 업스트림 버전(0.8.7)을 고정한다.
# 데비안 리비전(-1.pgdg12+N)까지 고정하지 않는 이유: PGDG 는 같은 업스트림의 옛 리비전을 저장소에서 내려(0.8.6-1.pgdg12+1 이 사라져 이미지 빌드가 깨졌다)
# 리비전 고정은 다음 재빌드 때 다시 깨진다. 리비전은 패키징 수정뿐이라 업스트림 버전만 고정해도 확장 동작은 같다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-16-pgvector=0.8.7-* \
    && rm -rf /var/lib/apt/lists/*
USER postgres
