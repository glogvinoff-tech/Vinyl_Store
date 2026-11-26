FROM python:3.12-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 RECOMMENDER_HOST=0.0.0.0 OPENBLAS_NUM_THREADS=1
COPY recommender/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt && useradd --create-home app
COPY recommender/*.py ./
USER app
EXPOSE 8091
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8091/health', timeout=2)"
CMD ["python", "server.py"]
