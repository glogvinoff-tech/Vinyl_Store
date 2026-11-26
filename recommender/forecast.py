"""Daily synthetic vinyl sales and an extreme learning machine forecast."""
from datetime import date, datetime, timedelta
from collections import defaultdict
from functools import lru_cache
import hashlib
import numpy as np
from model import MOSCOW


def month_shift(day, offset):
    index = day.year * 12 + day.month - 1 + offset
    return date(index // 12, index % 12 + 1, 1)


def dates(start, end):
    return [start + timedelta(days=i) for i in range((end - start).days)]


def inputs(days):
    rows = []
    for day in days:
        annual = 2 * np.pi * (day.timetuple().tm_yday - 1) / (366 if day.year % 4 == 0 and (day.year % 100 != 0 or day.year % 400 == 0) else 365)
        weekly = 2 * np.pi * day.weekday() / 7
        rows.append([(day - date(2020, 1, 1)).days / 365.25, np.sin(annual), np.cos(annual),
                     np.sin(2 * annual), np.cos(2 * annual), np.sin(weekly), np.cos(weekly), float(day.day >= 25)])
    return np.asarray(rows)


def synthetic_history(days):
    result = []
    for day, x in zip(days, inputs(days)):
        seed = int.from_bytes(hashlib.sha256(day.isoformat().encode()).digest()[:8], 'big')
        rng = np.random.default_rng(seed)
        expected = np.exp(3.2 + .07 * x[0] + .30 * x[2] + .12 * x[4] - .18 * x[5] + .10 * x[7])
        units = int(rng.poisson(expected))
        price = round(2400 * np.exp(.025 * x[0] + .04 * x[1]) * rng.uniform(.94, 1.06), 2)
        result.append({"period": day.isoformat(), "units": units, "revenue": round(units * price, 2), "unitPrice": price})
    return result


class SalesNetwork:
    """ELM: fixed seeded tanh layer, trained output via ridge regression; two log targets."""
    def __init__(self):
        rng = np.random.default_rng(23112026)
        self.hidden = rng.normal(0, .4, (7, 24))
        self.bias = rng.normal(0, .2, 24)

    def design(self, days):
        x = inputs(days)
        # Direct linear inputs preserve trend extrapolation outside the historical interval.
        return np.column_stack([np.ones(len(x)), x, np.tanh(x[:, 1:] @ self.hidden + self.bias)])

    def fit(self, days, history):
        x = self.design(days)
        y = np.log1p([[row['units'], row['unitPrice']] for row in history])
        penalty = np.eye(x.shape[1]) * .2
        penalty[0, 0] = 0
        self.weights = np.linalg.solve(x.T @ x + penalty, x.T @ y)
        return self

    def predict(self, days):
        predictions = np.maximum(0, np.expm1(np.clip(self.design(days) @ self.weights, 0, 20)))
        rows = []
        for day, (units, price) in zip(days, predictions):
            units = int(round(float(units)))
            price = round(float(price), 2)
            rows.append({"period": day.isoformat(), "units": units, "revenue": round(units * price, 2)})
        return rows


def aggregate(rows, length):
    grouped = defaultdict(lambda: {"units": 0, "cents": 0, "days": 0})
    for row in rows:
        group = grouped[row['period'][:length]]
        group['units'] += row['units']
        group['cents'] += round(row['revenue'] * 100)
        group['days'] += 1
    return [{"period": period, "units": value['units'], "revenue": value['cents'] / 100, "days": value['days']}
            for period, value in sorted(grouped.items())]


@lru_cache(maxsize=2)
def build_forecast(month):
    start = date.fromisoformat(month)
    history_days = dates(month_shift(start, -36), start)
    history = synthetic_history(history_days)
    # Chronological holdout; no future or validation observations enter this fit.
    split = len(history_days) - 90
    validation = SalesNetwork().fit(history_days[:split], history[:split]).predict(history_days[split:])
    units_mae = float(np.mean([abs(a['units'] - b['units']) for a, b in zip(validation, history[split:])]))
    revenue_mae = float(np.mean([abs(a['revenue'] - b['revenue']) for a, b in zip(validation, history[split:])]))
    network = SalesNetwork().fit(history_days, history)
    future = network.predict(dates(start, month_shift(start, 13)))
    history_public = [{k: row[k] for k in ('period', 'units', 'revenue')} for row in history]
    months = aggregate(future, 7)
    return {"dataSource": "SYNTHETIC", "asOfMonth": month, "timeZone": "Europe/Moscow", "currency": "RUB",
            "historyStart": history_days[0].isoformat(), "historyEnd": history_days[-1].isoformat(),
            "forecastStart": future[0]['period'], "forecastEnd": future[-1]['period'], "nextMonth": months[1],
            "history": {"days": history_public, "months": aggregate(history, 7), "years": aggregate(history, 4)},
            "forecast": {"days": future, "months": months, "years": aggregate(future, 4)},
            "model": {"name": "ELM: 24 tanh-нейрона + линейный тренд", "trainingDays": split,
                      "validationDays": 90, "finalTrainingDays": len(history_days),
                      "unitsMae": round(units_mae, 2), "revenueMae": round(revenue_mae, 2)}}


def forecast_now():
    return build_forecast(datetime.now(MOSCOW).date().replace(day=1).isoformat())
