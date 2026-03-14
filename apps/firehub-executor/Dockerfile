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

# App code
COPY app/ app/

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "4"]
