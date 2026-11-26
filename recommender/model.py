"""Synthetic purchase history and recommendations. Never connects to the shop DB."""
import calendar
import hashlib
import math
import threading
from datetime import datetime, timedelta, timezone

import numpy as np

from neural import NeuralNetwork

SEED = 23112026
TRAINING_SIZE = 3600
VALIDATION_SIZE = 900
MOSCOW = timezone(timedelta(hours=3), name="Europe/Moscow")


def stable_fraction(text):
    return int.from_bytes(hashlib.sha256(text.encode()).digest()[:4], "big") / (2 ** 32 - 1)


def features(record, tastes, now):
    genre = record["genre"].strip().lower()
    phase = stable_fraction(genre)
    return [
        0.5 if not tastes else float(genre in tastes),
        float(bool(tastes)),
        stable_fraction(record["id"]),  # Fictional popularity prior, not actual sales.
        min(1, math.log1p(record["price"]) / math.log1p(100_000)),
        record["discountPercent"] / 90,
        math.cos(2 * math.pi * ((now.month - 1) / 12 - phase)),
        math.cos(2 * math.pi * ((now.day - 1) / calendar.monthrange(now.year, now.month)[1] - phase)),
        math.cos(2 * math.pi * (now.hour / 24 - phase)),
        float(now.weekday() >= 5), float(now.day >= 25), float(now.hour >= 18),
    ]


def generate_history(records):
    """4500 fictional exposures: record, taste, date/hour and quantity (0 means no purchase)."""
    rng = np.random.default_rng(SEED)
    genres = sorted({record["genre"].strip().lower() for record in records})
    rows, history, quantities = [], [], []
    for _ in range(TRAINING_SIZE + VALIDATION_SIZE):
        record = records[int(rng.integers(len(records)))]
        taste = set() if rng.random() < 0.25 else {genres[int(rng.integers(len(genres)))]}
        now = datetime(2025, 1, 1, tzinfo=MOSCOW) + timedelta(hours=int(rng.integers(365 * 24)))
        x = features(record, taste, now)
        # Assumptions used ONLY to generate educational data, not to rank results.
        demand = (-1.8 + 4.8 * (x[0] - 0.5) + 1.1 * x[2] - 0.6 * x[3] + 1.2 * x[4]
                  + 0.65 * x[5] + 0.5 * x[6] + 0.75 * x[7] + 0.2 * x[8] + 0.35 * x[9] + 0.15 * x[10])
        purchased = rng.random() < 1 / (1 + math.exp(-demand))
        quantity = (1 + (int(rng.integers(1, 3)) if rng.random() < 0.2 else 0)) if purchased else 0
        rows.append(x)
        quantities.append(quantity)
        history.append({"recordId": record["id"], "occurredAt": now.isoformat(), "genres": sorted(taste), "quantity": quantity})
    return np.array(rows), np.array(quantities), history


def promotion_title(now):
    if now.month == 12 and now.day >= 20:
        return "Пластинки к Новому году"
    if now.day >= 25:
        return "Находки конца месяца"
    if now.weekday() >= 5:
        return "Виниловые выходные"
    if now.hour >= 18 or now.hour < 6:
        return "Пластинки для вечернего прослушивания"
    return "Выгодные находки дня"


class Recommender:
    def __init__(self):
        self._cached = None
        self._lock = threading.Lock()

    def _model_for(self, records):
        # Purchases do not trigger retraining: stock is excluded from the signature.
        signature = tuple((r["id"], r["genre"], r["price"], r["discountPercent"]) for r in records)
        with self._lock:
            if self._cached is not None and self._cached[0] == signature:
                return self._cached[1:]
            x, quantities, _ = generate_history(records)
            network = NeuralNetwork()
            network.train(x[:TRAINING_SIZE], quantities[:TRAINING_SIZE])
            info = {"architecture": "11 → 16 (tanh) → 1 (sigmoid)", "trainingExamples": TRAINING_SIZE,
                    "validationExamples": VALIDATION_SIZE, "fictionalPurchases": int(np.count_nonzero(quantities)),
                    "fictionalUnits": int(quantities.sum()), "validationLoss": network.loss(x[TRAINING_SIZE:], quantities[TRAINING_SIZE:])}
            self._cached = signature, network, info
            return network, info

    def recommend(self, records, tastes=(), limit=3, now=None):
        records = sorted(records, key=lambda record: record["id"])
        tastes = {genre.strip().lower() for genre in tastes}
        now = (now or datetime.now(MOSCOW)).astimezone(MOSCOW)
        result = {"items": [], "promotion": {"items": []}, "generatedAt": now.isoformat(), "training": None}
        if not records:
            return result
        network, info = self._model_for(records)
        available = [record for record in records if record["stock"] > 0]
        if available:
            scores = network.predict(np.array([features(record, tastes, now) for record in available]))
            ranked = []
            for record, score in zip(available, scores):
                reason = ("Совпадает с выбранным жанром; учтены дата и время" if record["genre"].strip().lower() in tastes
                          else "Попробуйте новое звучание" if tastes else "Знакомство с коллекцией с учётом даты и времени")
                ranked.append({"recordId": record["id"], "score": float(score), "reason": reason})
            ranked.sort(key=lambda pick: (-pick["score"], pick["recordId"]))
            discounts = {r["id"] for r in available if r["discountPercent"] > 0}
            result["items"] = ranked[:limit]
            result["promotion"] = {"title": promotion_title(now), "items": [pick for pick in ranked if pick["recordId"] in discounts][:3]}
        result["training"] = info
        return result
