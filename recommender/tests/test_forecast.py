import sys
from pathlib import Path
import unittest
from datetime import date
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from forecast import build_forecast, aggregate, synthetic_history, dates, month_shift, SalesNetwork


class ForecastTests(unittest.TestCase):
    def test_forecast_dates_and_totals_agree(self):
        result = build_forecast('2026-11-01')
        self.assertEqual(result['nextMonth']['period'], '2026-12')
        self.assertEqual(result['historyEnd'], '2026-10-31')
        self.assertEqual(result['forecastEnd'], '2027-11-30')
        for kind in ['history', 'forecast']:
            section = result[kind]
            self.assertEqual(aggregate(section['days'], 7), section['months'])
            self.assertEqual(aggregate(section['days'], 4), section['years'])
            self.assertEqual(sum(r['units'] for r in section['days']), sum(r['units'] for r in section['years']))
            self.assertTrue(all(r['units'] >= 0 and r['revenue'] >= 0 for r in section['days']))
        self.assertEqual(result['nextMonth']['days'], 31)

    def test_leap_year_and_december_rollover(self):
        self.assertEqual(month_shift(date(2026, 12, 1), 1), date(2027, 1, 1))
        feb = build_forecast('2028-01-01')['nextMonth']
        self.assertEqual(feb['period'], '2028-02')
        self.assertEqual(feb['days'], 29)

    def test_history_is_stable_and_model_beats_constant_holdout(self):
        days = dates(date(2023, 11, 1), date(2026, 11, 1))
        rows = synthetic_history(days)
        self.assertEqual(rows[-30:], synthetic_history(days[-30:]))
        predictions = SalesNetwork().fit(days[:-90], rows[:-90]).predict(days[-90:])
        mae = np.mean([abs(p['units'] - r['units']) for p, r in zip(predictions, rows[-90:])])
        baseline = np.mean([r['units'] for r in rows[:-90]])
        baseline_mae = np.mean([abs(r['units'] - baseline) for r in rows[-90:]])
        self.assertLess(mae, baseline_mae)
        print(f'Forecast holdout MAE: neural={mae:.2f}, constant={baseline_mae:.2f}')


if __name__ == '__main__':
    unittest.main()
