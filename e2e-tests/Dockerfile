ARG JAVA_BUILD_IMAGE=eclipse-temurin:21-jdk-jammy
FROM ${JAVA_BUILD_IMAGE}

ARG UBUNTU_ARCHIVE_MIRROR
RUN if [ -n "$UBUNTU_ARCHIVE_MIRROR" ]; then \
        sed -i -E "s#https?://(archive|security)\.ubuntu\.com/ubuntu#${UBUNTU_ARCHIVE_MIRROR%/}#g" /etc/apt/sources.list; \
    fi \
    && apt-get -o APT::Update::Error-Mode=any update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        fontconfig \
        libasound2 \
        libfreetype6 \
        libegl1 \
        libgl1 \
        libgtk-3-0 \
        libx11-6 \
        libxcursor1 \
        libxext6 \
        libxi6 \
        libxinerama1 \
        libxrandr2 \
        libxrender1 \
        libxtst6 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
COPY . .

CMD ["bash", "e2e-tests/run-in-container.sh"]
