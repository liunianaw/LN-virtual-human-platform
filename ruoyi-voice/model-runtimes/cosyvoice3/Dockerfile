# Torch's pinned Linux wheel supplies CUDA libraries; host provides the GPU driver.
FROM python:3.11.11-slim
RUN apt-get update && apt-get install -y --no-install-recommends git ffmpeg libsndfile1 build-essential && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY ruoyi-voice/ruoyi_voice ./ruoyi_voice
COPY ruoyi-voice/model-runtimes/cosyvoice3 ./runtime
COPY ruoyi-voice/model-runtimes/build-constraints.txt ./runtime/build-constraints.txt
RUN python -m ruoyi_voice.build_inputs source runtime/source.lock.json /opt/CosyVoice \
    && pip install --no-cache-dir setuptools==75.8.0 wheel==0.45.1 \
    && PIP_CONSTRAINT=/app/runtime/build-constraints.txt pip install --no-cache-dir --require-hashes -r runtime/requirements.lock
ENV HOME=/tmp/ln-voice LN_VOICE_MODEL_PROVIDER=COSYVOICE3 LN_VOICE_OFFICIAL_SOURCE=/opt/CosyVoice \
    LN_VOICE_SOURCE_LOCK=/app/runtime/source.lock.json LN_VOICE_MODEL_DIR=/models/cosyvoice3 \
    LN_VOICE_TEXT_RESOURCE_LOCK=/app/runtime/text-resources.lock.json LN_VOICE_TEXT_RESOURCE_DIR=/models/wetext \
    LN_VOICE_MODEL_BIND=0.0.0.0 LN_VOICE_MODEL_PORT=8012 HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1
USER 65534:65534
EXPOSE 8012
ENTRYPOINT ["python", "-m", "ruoyi_voice.model_server"]
