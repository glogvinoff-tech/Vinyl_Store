import BuyControl, { Price } from "./BuyControl";
import { useState } from "react";
import { DesignIcon, homeAssets } from "./Design";
import { Link } from "react-router-dom";
import { useStore } from "../app/StoreContext";
import { money } from "../api/client";

export function Cover({ record, ...props }) {
  const [failed, setFailed] = useState(false);
  if (!record.cover_url || failed) return <div className="cover-fallback" role="img" aria-label={`Обложка ${record.artist} — ${record.title}`}><span>LP</span><strong>{record.title}</strong></div>;
  return <img {...props} src={record.cover_url} alt={`Обложка ${record.artist} — ${record.title}`} onError={() => setFailed(true)} />;
}

export default function AlbumCard({ record }) {
  const store = useStore();
  const saved = store.favorites.includes(record.id);
  return (
    <article className="album">
      <div className="cover-wrap">
        <Link
          className="cover-open"
          to={`/albums/${record.id}`}
          aria-label={`Подробнее: ${record.artist} — ${record.title}`}
        >
          <Cover record={record} loading="lazy" />
        </Link>
        <button
          className={`heart ${saved ? "saved" : ""}`}
          aria-pressed={saved}
          aria-label={saved ? "Убрать из избранного" : "В избранное"}
          onClick={() => store.favorite(record.id)}
        >
          <DesignIcon src={saved ? "/design/heart-saved.svg" : homeAssets.imgGroup1} />
        </button>
      </div>
      <Link className="album-title" to={`/albums/${record.id}`}>
        {record.title}
      </Link>
      <div className="album-name">{record.artist}</div>
      <div className="album-bottom">
        <Price record={record} money={money} /><BuyControl record={record} />
      </div>
    </article>
  );
}
