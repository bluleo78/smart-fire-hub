FROM python:3.12-slim

# System dependencies + nsjail build
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        build-essential \
        git \
        flex \
        bison \
        protobuf-compiler \
        libprotobuf-dev \
        libnl-3-dev \
        libnl-route-3-dev \
        pkg-config \
        libcap-dev \
        libseccomp-dev \
        curl \
    && git clone --depth 1 --branch 3.4 https://github.com/google/nsjail.git /tmp/nsjail \
    && cd /tmp/nsjail && make && cp /tmp/nsjail/nsjail /usr/sbin/nsjail \
    && rm -rf /tmp/nsjail \
    && apt-get purge -y build-essential git protobuf-compiler pkg-config flex bison \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

# Python packages for user scripts (used inside nsjail)
RUN pip install --no-cache-dir --target=/opt/python-packages \
    psycopg2-binary \
    requests \
    pandas \
    openpyxl \
    beautifulsoup4 \
    lxml \
    numpy

# App dependencies
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# ODBC 계층 (WD-75): Tibero 테넌트 접속용 unixODBC + pyodbc.
# pyodbc 는 맥 개발 venv 에 깔리지 않도록 requirements.txt 가 아닌 별도 파일로 둔다.
# slim 이미지는 wheel 이 libodbc 를 동봉하지 않으므로 런타임 라이브러리 unixodbc 를 설치하고,
# 드라이버 등록 확인용 odbcinst CLI 도 함께 설치한다(Debian 은 별도 패키지).
RUN apt-get update && \
    apt-get install -y --no-install-recommends unixodbc odbcinst && \
    rm -rf /var/lib/apt/lists/*
COPY requirements-odbc.txt .
RUN pip install --no-cache-dir -r requirements-odbc.txt

# Tibero ODBC 드라이버는 선택형: 라이선스 바이너리라 리포에 없다. 빌드 컨텍스트의
# drivers/tibero/ 에 libtbodbc.so 와 의존 so 를 두면 포함되고, 없으면 .gitkeep 만 복사되어
# 빌드는 성공하되 드라이버는 등록되지 않는다(디렉터리는 항상 존재하므로 COPY 가 실패하지 않는다).
COPY drivers/tibero/ /opt/tibero/lib/
# [Tibero] 섹션 이름은 코드의 ODBC_DRIVER_NAME 과 같아야 한다. 드라이버가 있을 때만 등록한다.
RUN if [ -f /opt/tibero/lib/libtbodbc.so ]; then \
      printf '[Tibero]\nDescription=Tibero ODBC Driver\nDriver=/opt/tibero/lib/libtbodbc.so\n' > /etc/odbcinst.ini; \
    fi
# TBCLI_WCHAR_TYPE=ucs2: Tibero 클라이언트가 유니코드(한글)를 UCS-2 로 주고받도록 맞춘다
# (unixODBC 의 SQLWCHAR 폭과 일치시키지 않으면 한글이 깨진다). 드라이버 의존 so 탐색 경로도 지정한다.
ENV TBCLI_WCHAR_TYPE=ucs2 \
    LD_LIBRARY_PATH=/opt/tibero/lib

# App code
COPY app/ app/

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "4"]
