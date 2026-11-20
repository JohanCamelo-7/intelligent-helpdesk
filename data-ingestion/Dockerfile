FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /srv
COPY requirements.txt .
RUN pip install -r requirements.txt && useradd -r -u 10001 app
COPY ingestion ./ingestion
USER app
CMD ["python", "-m", "ingestion.main"]
