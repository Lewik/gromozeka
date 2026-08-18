FROM eclipse-temurin:21-jdk-jammy

RUN apt-get update \
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
