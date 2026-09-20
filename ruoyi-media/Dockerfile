FROM python:3.12-slim

WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/src

COPY requirements.lock ./requirements.lock
RUN pip install --no-cache-dir -r requirements.lock

COPY pyproject.toml README.md ./
COPY src ./src

CMD ["uvicorn", "ruoyi_media.api.app:app", "--host", "0.0.0.0", "--port", "8002"]
