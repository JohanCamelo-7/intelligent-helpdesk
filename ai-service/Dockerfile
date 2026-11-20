FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /srv
COPY requirements.txt .
RUN pip install -r requirements.txt && useradd -r -u 10001 app && mkdir -p /models && chown app /models
# El usuario "app" necesita una carpeta personal escribible y la caché de Hugging Face (incluida la de hf-xet)
# debe vivir en el volumen de modelos; sin esto la descarga de Whisper falla con "Permission denied".
RUN mkdir -p /home/app && chown app /home/app
ENV HOME=/home/app HF_HOME=/models/huggingface
COPY app ./app
USER app
EXPOSE 8000
# Un solo worker: las sesiones del agente y los modelos de voz viven en memoria.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--proxy-headers"]
