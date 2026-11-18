# ---------- build ----------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN sed -i "s/\r$//" gradlew && chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet
COPY src src
RUN ./gradlew --no-daemon installDist

# ---------- runtime ----------
FROM eclipse-temurin:17-jre

# ffmpeg for merging, python for yt-dlp, deno as yt-dlp's JS runtime for YouTube
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg python3 python3-venv ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY --from=denoland/deno:bin-2.5.6 /deno /usr/local/bin/deno

# yt-dlp[default] pulls in yt-dlp-ejs (YouTube JS challenge solver)
RUN python3 -m venv /opt/yt-dlp \
 && /opt/yt-dlp/bin/pip install --no-cache-dir -U "yt-dlp[default]"
ENV PATH="/opt/yt-dlp/bin:${PATH}"

COPY --from=build /src/build/install/ps5-vod-fetcher /app

ENV DOWNLOAD_DIR=/data/vods \
    CLIPS_DIR=/data/clips \
    STATE_FILE=/data/processed.txt
VOLUME /data

ENTRYPOINT ["/app/bin/ps5-vod-fetcher"]
