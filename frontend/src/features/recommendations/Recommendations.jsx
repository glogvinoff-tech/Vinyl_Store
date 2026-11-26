import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useStore } from "../../app/StoreContext";
import { api, money, recordModel } from "../../api/client";
import AlbumCard from "../../components/AlbumCard";
import { MAX_GENRES, readPreferences, savePreferences, recommendationQuery } from "./preferences";
import "./recommendations.css";

export default function Recommendations() {
  const { user, records } = useStore();
  // Changing accounts resets the component before it can display another account's preferences.
  return <RecommendationPanel key={user?.id || "guest"} userId={user?.id} records={records} />;
}

function browserStorage() {
  try { return window.localStorage; } catch { return null; }
}

function RecommendationPanel({ userId, records }) {
  const genres = [...new Map(records.filter(record => record.genreCode).map(record => [record.genreCode, record.genre])).entries()]
    .sort((a, b) => a[1].localeCompare(b[1], "ru"));
  const [selected, setSelected] = useState(() => readPreferences(browserStorage(), userId, genres.map(([code]) => code)));
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  const [hour, setHour] = useState(() => Math.floor(Date.now() / 3_600_000));
  useEffect(() => {
    const timer = setInterval(() => setHour(Math.floor(Date.now() / 3_600_000)), 60_000);
    return () => clearInterval(timer);
  }, []);
  useEffect(() => {
    savePreferences(browserStorage(), userId, selected);
  }, [userId, selected]);
  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    let timeout;
    setLoading(true); setError(""); setResult(null);
    const debounce = setTimeout(async () => {
      timeout = setTimeout(() => controller.abort(), 20_000);
      try {
        const response = await api(recommendationQuery(selected), undefined, { signal: controller.signal });
        if (!Array.isArray(response?.items)) throw new Error("Invalid recommendation response");
        if (active) setResult(response);
      } catch {
        if (active) setError("Подборка временно недоступна. Можно повторить запрос или выбрать пластинку в каталоге.");
      } finally {
        clearTimeout(timeout);
        if (active) setLoading(false);
      }
    }, 200);
    return () => { active = false; clearTimeout(debounce); clearTimeout(timeout); controller.abort(); };
  }, [selected, records, revision, hour]);

  function toggle(code) {
    setSelected(previous => previous.includes(code) ? previous.filter(value => value !== code)
      : previous.length < MAX_GENRES ? [...previous, code] : previous);
  }

  return (
    <section className="collection-panel recommendations" id="for-you" aria-labelledby="recommendations-title">
      <div className="recommendations-heading">
        <div><span className="recommendations-eyebrow">SPIN · Музыкальный помощник</span><h2 id="recommendations-title">В вашем ритме</h2></div>
        <Link to="/catalog">Весь каталог ↗</Link>
      </div>
      <p className="recommendations-intro">Выберите любимые жанры — подберём пластинки по вашему вкусу. Без выбора предложим, с чего начать знакомство с коллекцией.</p>
      <fieldset className="recommendations-tastes">
        <legend>Что вам нравится? Можно выбрать до 8 жанров.</legend>
        <div>{genres.map(([code, label]) => <button key={code} type="button" aria-pressed={selected.includes(code)}
          disabled={selected.length >= MAX_GENRES && !selected.includes(code)} onClick={() => toggle(code)}>{label}</button>)}
          {selected.length > 0 && <button type="button" className="recommendations-reset" onClick={() => setSelected([])}>Сбросить вкус</button>}
        </div>
      </fieldset>
      <p className="recommendations-disclosure">Учебная нейросеть: обучена на вымышленных покупках. Подборка учитывает дату и время по Москве.</p>
      <div aria-live="polite" aria-busy={loading}>
        {loading && <p className="recommendations-status" role="status">Подбираем звучание для вас…</p>}
        {error && <div className="recommendations-status" role="status"><p>{error}</p><button type="button" className="button" onClick={() => setRevision(value => value + 1)}>Повторить подбор</button></div>}
        {result && !loading && <>
          {result.items.length ? <div className="record-grid featured">{result.items.map(pick => <div key={pick.record.id}>
            <AlbumCard record={recordModel(pick.record)} /><p className="recommendations-reason">{pick.reason}</p>
          </div>)}</div> : <p className="recommendations-status">Пока нет доступных пластинок для подборки.</p>}
          {result.promotion?.items?.length > 0 && <aside className="recommendations-promotion" aria-label="Подборка действующих скидок">
            <h3>{result.promotion.title}</h3><p>{result.promotion.explanation}</p>
            <ul>{result.promotion.items.map(({ record }) => <li key={record.id}>
              <Link to={`/albums/${record.id}`}><span>{record.artist} — {record.name}</span><strong>−{record.discountPercent}% · {money(Number(record.salePrice))}</strong></Link>
            </li>)}</ul>
          </aside>}
        </>}
      </div>
    </section>
  );
}
