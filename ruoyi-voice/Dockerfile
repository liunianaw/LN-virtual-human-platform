FROM python:3.11.11-slim
WORKDIR /app
COPY ruoyi-voice/pyproject.toml ruoyi-voice/requirements.lock ./
COPY ruoyi-voice/ruoyi_voice ./ruoyi_voice
RUN pip install --no-cache-dir -r requirements.lock && pip install --no-cache-dir --no-build-isolation .
USER 65534:65534
EXPOSE 8003
ENTRYPOINT ["ruoyi-voice"]
