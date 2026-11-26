import { categories } from "../../catalog";
import { useState } from "react";
import { api } from "../../api/client";
import Modal from "../../components/Modal";
export default function RecordEditor({ record, covers, onClose, onSaved }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState("");
  const [images, setImages] = useState(record.images?.length ? record.images : record.cover ? [record.cover] : []);
  const [dragging, setDragging] = useState(false);
  async function upload(files) {
    if (busy) return;
    if (images.length + files.length > 8) { setError("Не больше 8 фотографий"); return; }
    setBusy(true); setError("");
    try {
      for (const file of files) {
        if (!["image/png", "image/jpeg"].includes(file.type) || file.size > 5 * 1024 * 1024) throw new Error("Выберите PNG или JPEG до 5 МБ");
        const data = new FormData(); data.append("file", file);
        const result = await api("images", data);
        setImages(previous => [...previous, result.url]);
      }
    } catch (e) { setError(e.message); } finally { setBusy(false); }
  }
  async function submit(event) {
    event.preventDefault(); setBusy(true); setError("");
    const form = new FormData(event.currentTarget);
    const data = Object.fromEntries(form);
    const payload = { code: data.code, name: data.name, artist: data.artist, genre: data.genre,
      price: Number(data.price), stock: Number(data.stock), releaseYear: data.releaseYear ? Number(data.releaseYear) : null,
      cover: images[0] || null, images, tags: form.getAll("tags"), discountPercent: Number(data.discountPercent), description: data.description, active: data.active === "on", version: record.version ?? null };
    try { await api(record.id ? `records/${record.id}` : "records", payload, { method: record.id ? "PUT" : "POST" }); await onSaved(); }
    catch (e) { setError(e.message); } finally { setBusy(false); }
  }
  return <Modal title={record.id ? "Изменение пластинки" : "Новая пластинка"} busy={busy} onClose={() => { if (!busy) onClose(); }}>
    <h2>{record.id ? "Изменение пластинки" : "Новая пластинка"}</h2>
    <form onSubmit={submit}><div className="form-grid">
      <label>Артикул<input name="code" required maxLength={50} defaultValue={record.code || ""} disabled={busy} /></label>
      <label>Название<input name="name" required maxLength={200} defaultValue={record.title || ""} disabled={busy} /></label>
      <label>Исполнитель<input name="artist" required maxLength={120} defaultValue={record.artist || ""} disabled={busy} /></label>
      <label>Жанр<input name="genre" maxLength={50} defaultValue={record.genreCode || "Rock"} disabled={busy} /></label>
      <label>Год выпуска<input name="releaseYear" type="number" min={1900} max={2100} defaultValue={record.year || ""} disabled={busy} /></label>
      <label>Цена, ₽<input name="price" type="number" min="0.01" max="99999999.99" step="0.01" required defaultValue={record.originalPrice ?? record.price ?? 4990} disabled={busy} /></label>
      <label>Остаток<input name="stock" type="number" min={0} max={2147483647} step={1} required defaultValue={record.stock ?? 10} disabled={busy} /></label>
      <label>Скидка, %<input name="discountPercent" type="number" min="0" max="90" step="1" defaultValue={record.discountPercent || 0} disabled={busy} /><small>0 — без скидки. Цена выше указана до скидки.</small></label>
    </div>
    <fieldset disabled={busy} className="edition-checkboxes"><legend>Вид издания — можно выбрать несколько</legend>{categories.filter(c => c !== "Скидки").map(category => <label key={category}><input type="checkbox" name="tags" value={category} defaultChecked={record.tags?.includes(category)} />{category}</label>)}</fieldset>
    <div className={`image-dropzone ${dragging ? "dragging" : ""}`} onDragOver={e => {e.preventDefault(); setDragging(true);}} onDragLeave={() => setDragging(false)} onDrop={e => {e.preventDefault(); setDragging(false); upload([...e.dataTransfer.files]);}}>
      <label>Перетащите фотографии сюда или выберите с компьютера<input type="file" multiple accept="image/png,image/jpeg" disabled={busy} onChange={e => {upload([...e.target.files]); e.target.value = "";}} /></label><small>PNG / JPEG, до 5 МБ, максимум 8. Первое фото — обложка.</small>
    </div>
    <div className="image-previews">{images.map((url, index) => <div key={`${url}-${index}`}><img src={url} alt={`Фото ${index + 1}`} /><button type="button" disabled={busy || index === 0} onClick={() => setImages([url, ...images.filter((_, i) => i !== index)])}>{index === 0 ? "Обложка" : "На обложку"}</button><button type="button" disabled={busy} onClick={() => setImages(images.filter((_, i) => i !== index))}>Удалить</button></div>)}</div>
    <label>Готовая обложка<select value="" disabled={busy || images.length >= 8} onChange={e => {if (e.target.value && !images.includes(e.target.value)) setImages([...images, e.target.value]);}}><option value="">Выбрать из каталога</option>{covers.filter(r => r.cover).map(r => <option key={r.id} value={r.cover}>{r.title}</option>)}</select></label>
    <label>Описание<textarea name="description" maxLength={2000} defaultValue={record.description || ""} disabled={busy} /></label>
      <label><span><input name="active" type="checkbox" defaultChecked={record.active !== false} disabled={busy} /> Активна</span></label>
      <div className="form-error" role="alert">{error}</div><button className="button dark" disabled={busy}>{busy ? "Сохраняем…" : "Сохранить"}</button>
    </form>
  </Modal>;
}
