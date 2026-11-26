import { useEffect, useState } from "react";
import { api, money } from "../../api/client";
import Modal from "../../components/Modal";
import "./sales-forecast.css";

export default function SalesForecast({ onClose }) {
  const [data, setData] = useState(null), [error, setError] = useState("");
  const [retry, setRetry] = useState(0), [period, setPeriod] = useState("months"), [source, setSource] = useState("forecast");
  const [year, setYear] = useState(""), [month, setMonth] = useState("");
  useEffect(() => {
    const controller = new AbortController(); let active = true;
    const timeout = setTimeout(() => controller.abort(), 20000);
    setError(""); setData(null);
    api("analytics/forecast", undefined, { signal: controller.signal }).then(value => {
      if (!value?.forecast?.days || !value?.nextMonth) throw new Error("Некорректный ответ прогноза");
      if (active) { setData(value); setYear(value.nextMonth.period.slice(0, 4)); setMonth(value.nextMonth.period); }
    }).catch(e => { if (active) setError(e.name === "AbortError" ? "Прогноз не ответил вовремя" : e.message); })
      .finally(() => clearTimeout(timeout));
    return () => { active = false; clearTimeout(timeout); controller.abort(); };
  }, [retry]);
  const rows = data ? data[source][period].filter(row => period === "years" || (period === "months" ? row.period.startsWith(year) : row.period.startsWith(month))) : [];
  const chart = data ? [...data.history.months.slice(-6).map(row => ({ ...row, predicted: false })), ...data.forecast.months.slice(0, 4).map(row => ({ ...row, predicted: true }))] : [];
  const maximum = Math.max(1, ...chart.map(row => row.units));
  function switchSource(value) {
    setSource(value);
    setYear(data[value].years[0].period);
    setMonth(data[value].months[0].period);
  }
  return <Modal title="ИИ-прогноз продаж" onClose={onClose}><section className="sales-forecast">
    <p className="eyebrow">ИИ · АНАЛИТИКА</p><h2>Прогноз продаж винила</h2>
    <p className="forecast-note">Демонстрация на синтетических данных. Реальные заказы, цены и остатки магазина не изменяются. Суммы — оценка выручки, не прибыли.</p>
    {error ? <div role="alert"><p>{error}</p><button className="button" onClick={() => setRetry(value => value + 1)}>Повторить прогноз</button></div> : !data ? <p role="status">Анализируем 36 месяцев продаж…</p> : <>
      <div className="forecast-metrics">
        <div><span>Следующий месяц · {data.nextMonth.period}</span><strong>{data.nextMonth.units.toLocaleString("ru-RU")} шт.</strong></div>
        <div><span>Ожидаемая выручка за месяц</span><strong>{money(data.nextMonth.revenue)}</strong></div>
        <div><span>Средняя ошибка проверки за день</span><strong>±{data.model.unitsMae} шт.</strong><small>MAE на последних 90 днях истории; не доверительный интервал</small></div>
      </div>
      <h3>Продажи по месяцам</h3>
      <p>Серый — синтетическая история, оранжевый — прогноз. Высота столбца — количество пластинок.</p>
      <div className="forecast-chart" role="img" aria-label="Продажи за последние шесть месяцев и прогноз на четыре месяца">
        <svg viewBox="0 0 800 210" aria-hidden="true">{chart.map((row, index) => <g key={row.period}>
          <rect x={index * 80 + 12} y={170 - row.units / maximum * 135} width="48" height={row.units / maximum * 135} className={row.predicted ? "predicted" : "historical"} />
          <text x={index * 80 + 36} y={160 - row.units / maximum * 135} textAnchor="middle">{row.units}</text>
          <text x={index * 80 + 36} y="195" textAnchor="middle">{row.period}</text>
        </g>)}</svg>
      </div>
      <details><summary>Числа на графике</summary><ul>{chart.map(row => <li key={row.period}>{row.period}: {row.units} шт. · {money(row.revenue)} · {row.predicted ? "прогноз" : "синтетическая история"}</li>)}</ul></details>
      <div className="forecast-filters">
        <label>Данные<select value={source} onChange={e => switchSource(e.target.value)}><option value="forecast">Планируемые продажи</option><option value="history">Синтетическая история</option></select></label>
        <label>Детализация<select value={period} onChange={e => setPeriod(e.target.value)}><option value="years">По годам</option><option value="months">По месяцам</option><option value="days">По дням</option></select></label>
        {period === "months" && <label>Год<select value={year} onChange={e => setYear(e.target.value)}>{data[source].years.map(row => <option key={row.period}>{row.period}</option>)}</select></label>}
        {period === "days" && <label>Месяц<select value={month} onChange={e => setMonth(e.target.value)}>{data[source].months.map(row => <option key={row.period}>{row.period}</option>)}</select></label>}
      </div>
      <div className="forecast-table"><table><caption>{source === "forecast" ? "Ожидаемые продажи" : "Вымышленные продажи"}</caption><thead><tr><th>Период</th><th>Пластинки, шт.</th><th>Выручка, ₽</th><th>Дней в расчёте</th></tr></thead><tbody>{rows.map(row => <tr key={row.period}><td>{row.period}</td><td>{row.units.toLocaleString("ru-RU")}</td><td>{money(row.revenue)}</td><td>{row.days || 1}</td></tr>)}</tbody></table></div>
      <p className="forecast-note">История: {data.historyStart} — {data.historyEnd}. Прогноз: {data.forecastStart} — {data.forecastEnd}. Текущий месяц прогнозируется целиком; неполные годы содержат только дни указанного диапазона.</p>
      <details><summary>Метод прогнозирования</summary><p>{data.model.name}. Обучение на синтетической сезонности, тренде и днях недели. Проверка на 90 последних днях без доступа к ним при обучении; затем модель переобучается на всей истории. Средняя ошибка выручки за день: {money(data.model.revenueMae)}. Дневные значения суммируются в месячные и годовые. Это учебный сценарий, а не гарантия будущих продаж.</p></details>
    </>}
  </section></Modal>;
}
