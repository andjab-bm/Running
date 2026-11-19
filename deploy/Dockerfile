#
# One image with the API and the web app. Built from the repository root:
#
#   docker build -f deploy/Dockerfile -t running .
#   docker buildx build -f deploy/Dockerfile --platform linux/arm64,linux/amd64 -t running .
#
# The web app and the server are both compiled on the machine that builds (BUILDPLATFORM); the
# server is cross-compiled for the other architecture, so nothing runs under emulation.

# --- 1. web app: static files, the same for every architecture ---
FROM --platform=$BUILDPLATFORM node:26-trixie-slim AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json web/.npmrc ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY web/ ./
RUN npm run build

# --- 2. server: one binary with the web app embedded ---
FROM --platform=$BUILDPLATFORM rust:1-trixie AS build
ARG TARGETARCH
# A C cross-compiler is only needed when the image is not for the building machine
# (SQLite and zstd are compiled from C).
RUN set -eux; \
    case "$TARGETARCH" in \
      arm64) triple=aarch64-unknown-linux-gnu; cross="gcc-aarch64-linux-gnu libc6-dev-arm64-cross" ;; \
      amd64) triple=x86_64-unknown-linux-gnu; cross="gcc-x86-64-linux-gnu libc6-dev-amd64-cross" ;; \
      *) echo "unsupported architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    echo "$triple" > /triple; \
    rustup target add "$triple"; \
    if [ "$TARGETARCH" != "$(dpkg --print-architecture)" ]; then \
      apt-get update; \
      apt-get install -y --no-install-recommends $cross; \
      rm -rf /var/lib/apt/lists/*; \
    fi
ENV CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=aarch64-linux-gnu-gcc \
    CC_aarch64_unknown_linux_gnu=aarch64-linux-gnu-gcc \
    CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER=x86_64-linux-gnu-gcc \
    CC_x86_64_unknown_linux_gnu=x86_64-linux-gnu-gcc
WORKDIR /src/server
COPY server/ ./
COPY --from=web /src/web/build /src/web/build
# Each architecture gets its own registry cache: both builds run at the same time, and sharing one
# makes them unpack the same crate simultaneously ("File exists (os error 17)").
RUN --mount=type=cache,target=/usr/local/cargo/registry,id=cargo-registry-$TARGETARCH \
    --mount=type=cache,target=/src/server/target,id=target-$TARGETARCH \
    set -eux; \
    triple="$(cat /triple)"; \
    cargo build --release --locked --target "$triple"; \
    cp "target/$triple/release/running-server" /running-server; \
    mkdir /data

# --- 3. runtime: the binary and the C library, no shell, not root ---
FROM gcr.io/distroless/cc-debian13:nonroot
COPY --from=build /running-server /usr/local/bin/running-server
# Owned by the image's unprivileged user, so a fresh named volume mounted here is writable.
COPY --from=build --chown=65532:65532 /data /data
ENV PORT=8080 \
    DATA_DIR=/data
EXPOSE 8080
VOLUME /data
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["/usr/local/bin/running-server", "healthcheck"]
ENTRYPOINT ["/usr/local/bin/running-server"]
