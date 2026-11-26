"""A small neural network written with NumPy, without an external AI API."""
import numpy as np


class NeuralNetwork:
    """11 inputs -> 16 tanh neurons -> sigmoid, weighted binary cross entropy."""

    def __init__(self, inputs=11, hidden=16, seed=23112026):
        rng = np.random.default_rng(seed)
        self.w1 = rng.normal(0, 1 / np.sqrt(inputs), (inputs, hidden))
        self.b1 = np.zeros(hidden)
        self.w2 = rng.normal(0, 1 / np.sqrt(hidden), hidden)
        self.b2 = 0.0

    def predict(self, x):
        hidden = np.tanh(np.asarray(x) @ self.w1 + self.b1)
        return 1 / (1 + np.exp(-np.clip(hidden @ self.w2 + self.b2, -30, 30)))

    def train(self, x, quantities, epochs=100, seed=23112026):
        rng = np.random.default_rng(seed)
        targets = (quantities > 0).astype(float)
        weights = np.maximum(1, quantities)
        for epoch in range(epochs):
            order = rng.permutation(len(x))
            rate = 0.12 / (1 + epoch * 0.015)
            for offset in range(0, len(x), 128):
                indices = order[offset:offset + 128]
                batch = x[indices]
                hidden = np.tanh(batch @ self.w1 + self.b1)
                output = 1 / (1 + np.exp(-np.clip(hidden @ self.w2 + self.b2, -30, 30)))
                error = (output - targets[indices]) * weights[indices] / weights[indices].sum()
                # Calculate both gradients before updating either layer.
                hidden_error = error[:, None] * self.w2 * (1 - hidden ** 2)
                self.w2 -= rate * (hidden.T @ error)
                self.b2 -= rate * error.sum()
                self.w1 -= rate * (batch.T @ hidden_error)
                self.b1 -= rate * hidden_error.sum(axis=0)

    def loss(self, x, quantities):
        p = np.clip(self.predict(x), 1e-12, 1 - 1e-12)
        y = quantities > 0
        return float(np.average(-np.where(y, np.log(p), np.log1p(-p)), weights=np.maximum(1, quantities)))
