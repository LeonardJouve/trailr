FROM ubuntu:24.04 AS build
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates git make g++ libsqlite3-dev zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 https://github.com/felt/tippecanoe \
    && make -C tippecanoe -j"$(nproc)" \
    && make -C tippecanoe install

FROM ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends \
    libsqlite3-0 zlib1g \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /usr/local/bin/ /usr/local/bin/
WORKDIR /work
CMD ["tippecanoe", "-e", "data/tiles", "-Z8", "-z15", "--force", "-L", "{\"file\":\"data/wanderwege.geojson\",\"layer\":\"wanderwege\"}", "-L", "{\"file\":\"data/veloland.geojson\",\"layer\":\"veloland\"}"]
