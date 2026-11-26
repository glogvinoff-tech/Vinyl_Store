import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useStore } from "../app/StoreContext";
import { api } from "../api/client";
import Modal from "../components/Modal";
export default function AccountPage() {
  const store = useStore(), navigate = useNavigate();
  const [changing, setChanging] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState("");
  async function changePassword(event) {
    event.preventDefault(); setBusy(true); setError("");
    try {
      await api("auth/change-password", Object.fromEntries(new FormData(event.currentTarget)));
      await store.logout(); setChanging(false); store.setAuthOpen(true); store.notify("Пароль изменён. Войдите заново.");
    } catch (e) { setError(e.message); } finally { setBusy(false); }
  }
  if (!store.user) return <section className="page"><h2>Личный кабинет</h2><p>Войдите через стартовое окно, чтобы открыть личный кабинет.</p></section>;
  return <section className="page narrow">
    <p className="eyebrow">{store.user.role === "admin" ? "АДМИНИСТРАТОР" : store.user.role === "manager" ? "МЕНЕДЖЕР" : "ЛИЧНЫЙ КАБИНЕТ"}</p>
    <h2>Привет, {store.user.name}</h2><p>{store.user.email}</p>
    <div className="profile-actions">
      {store.user.passwordChangeRequired && <button className="button dark" onClick={() => store.setAuthOpen(true)}>Заменить временный пароль</button>}
      <Link className="button dark" to="/orders">Мои заказы</Link><Link className="button" to="/favorites">Моё избранное</Link>
      {store.user.role === "admin" && <Link className="button" to="/admin">Управление каталогом</Link>}
      {store.user.role !== "customer" && <a className="button" href="/admin.html">Панель управления магазином ↗</a>}
      <button className="button" onClick={() => { setError(""); setChanging(true); }}>Сменить пароль</button>
      <button className="button" onClick={async () => { try { await store.logout(); navigate("/"); } catch (e) { store.notify(e.message); } }}>Выйти из аккаунта</button>
    </div>
    {changing && <Modal title="Смена пароля" busy={busy} onClose={() => { if (!busy) setChanging(false); }}>
      <h2>Смена пароля</h2><form onSubmit={changePassword}>
        <label>Текущий пароль<input name="currentPassword" type="password" autoComplete="current-password" required disabled={busy} /></label>
        <label>Новый пароль<input name="newPassword" type="password" autoComplete="new-password" minLength={12} maxLength={128} required disabled={busy} /></label>
        <p className="auth-hint">От 12 символов: заглавная и строчная буквы, цифра и специальный символ.</p>
        <div className="form-error" role="alert">{error}</div><button className="button dark" disabled={busy}>{busy ? "Сохраняем…" : "Сохранить"}</button>
      </form>
    </Modal>}
  </section>;
}
