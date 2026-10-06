# Build context: repository root. No local README, cache or weight is a build input.
FROM python:3.11.11-slim
RUN apt-get update && apt-get install -y --no-install-recommends git espeak-ng libsndfile1 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY ruoyi-voice/ruoyi_voice ./ruoyi_voice
COPY ruoyi-voice/model-runtimes/kokoro ./runtime
COPY ruoyi-voice/model-runtimes/build-constraints.txt ./runtime/build-constraints.txt
RUN python -m ruoyi_voice.build_inputs source runtime/source.lock.json /opt/kokoro \
    && PIP_CONSTRAINT=/app/runtime/build-constraints.txt pip install --no-cache-dir --extra-index-url https://download.pytorch.org/whl/cpu --require-hashes -r runtime/requirements.lock \
    && pip install --no-deps --no-build-isolation /opt/kokoro
ENV HOME=/tmp/ln-voice LN_VOICE_MODEL_PROVIDER=KOKORO LN_VOICE_OFFICIAL_SOURCE=/opt/kokoro \
    LN_VOICE_SOURCE_LOCK=/app/runtime/source.lock.json LN_VOICE_MODEL_DIR=/models/kokoro \
    LN_VOICE_MODEL_BIND=0.0.0.0 LN_VOICE_MODEL_PORT=8011 LN_VOICE_CPU_THREADS=2 \
    HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1
USER 65534:65534
EXPOSE 8011
ENTRYPOINT ["python", "-m", "ruoyi_voice.model_server"]
