import { useEffect, useState } from "react";
import { api } from "../../api/client";
import { useStore } from "../../app/StoreContext";
import Modal from "../../components/Modal";

export default function AuthModal() {
  const store = useStore();
  const mandatory = !store.user || Boolean(store.user.passwordChangeRequired);
  const [mode, setMode] = useState(store.user?.passwordChangeRequired ? "initial" : "login");
  const [method, setMethod] = useState("code");
  const [challenge, setChallenge] = useState(null);
  const [request, setRequest] = useState(null);
  const [retryAt, setRetryAt] = useState(0);
  const [seconds, setSeconds] = useState(0);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!retryAt) return;
    const tick = () => setSeconds(Math.max(0, Math.ceil((retryAt - Date.now()) / 1000)));
    tick(); const timer = setInterval(tick, 1000); return () => clearInterval(timer);
  }, [retryAt]);
  function complete(session) {
    const user = store.signIn(session);
    setRequest(null);
    if (user.passwordChangeRequired) setMode("initial");
    else store.notify("Добро пожаловать в SPIN");
  }
  async function sendCode(payload) {
    const result = await api("auth/email/request", payload);
    setRequest(payload);
    setChallenge({ email: payload.email, purpose: payload.purpose, expiresInSeconds: result.expiresInSeconds });
    setRetryAt(Date.now() + result.retryAfterSeconds * 1000);
    setMode("verify");
    store.notify(result.message);
  }
  async function submit(event) {
    event.preventDefault(); setBusy(true); setError("");
    const data = Object.fromEntries(new FormData(event.currentTarget));
    try {
      if (mode === "initial") {
        await api("auth/change-initial-password", { newPassword: data.password });
        await store.refresh(); store.setAuthOpen(false);
        store.notify("Пароль обновлён. Добро пожаловать в SPIN");
      } else if (mode === "verify") {
        complete(await api("auth/email/verify", { email: challenge.email, purpose: challenge.purpose, code: data.code, clientType: "WEB" }));
      } else if (mode === "login" && method === "password") {
        complete(await api("auth/login", { email: data.email.trim(), password: data.password, clientType: "WEB" }));
      } else {
        await sendCode({ email: data.email.trim(), purpose: mode === "register" ? "REGISTER" : "LOGIN",
          ...(mode === "register" ? { displayName: data.name, password: data.password } : {}) });
      }
    } catch (e) { setError(e.message); } finally { setBusy(false); }
  }
  async function resendCode() {
    if (busy || seconds > 0 || !request) return;
    setBusy(true); setError("");
    try { await sendCode(request); } catch (e) { setError(e.message); } finally { setBusy(false); }
  }
  const close = () => { if (!busy) store.setAuthOpen(false); };
  return <Modal title="Вход и регистрация" onClose={close} busy={busy} dismissible={!mandatory}>
    <p className="eyebrow">ВАША МУЗЫКА. ВАШ КАБИНЕТ.</p>
    <h2>{mode === "verify" ? "Подтвердите почту" : mode === "register" ? "Начнём вашу коллекцию" : mode === "initial" ? "Новый пароль" : "С возвращением"}</h2>
    {["login", "register"].includes(mode) && <div className="tabs">
      {[ ["login", "Вход"], ["register", "Регистрация"] ].map(([value, label]) =>
        <button key={value} type="button" disabled={busy} className={mode === value ? "selected" : ""}
          onClick={() => { setMode(value); setError(""); }}>{label}</button>)}
    </div>}
    {mode === "login" && <div className="auth-methods" aria-label="Способ входа">
      <button type="button" className={method === "code" ? "selected" : ""} disabled={busy} onClick={() => { setMethod("code"); setError(""); }}>Код на Gmail</button>
      <button type="button" className={method === "password" ? "selected" : ""} disabled={busy} onClick={() => { setMethod("password"); setError(""); }}>По паролю</button>
    </div>}
    {mode === "verify" && <p>Если адрес подходит для выбранного действия, шестизначный код отправлен на <strong>{challenge.email}</strong>. Он действует {Math.floor(challenge.expiresInSeconds / 60)} минут.</p>}
    {mode === "initial" && <p>Замените временный пароль, чтобы продолжить работу с аккаунтом.</p>}
    {mode === "login" && method === "code" && <p className="auth-hint">Вход по коду доступен после регистрации. Если аккаунта ещё нет, откройте вкладку «Регистрация».</p>}
    {mode === "verify" && <p className="auth-hint">Проверьте папки «Спам» и «Вся почта». Для входа используйте адрес зарегистрированного аккаунта; для регистрации — новый адрес.</p>}
    <form onSubmit={submit} key={`${mode}-${method}`} aria-busy={busy}>
      {mode === "register" && <label>Ваше имя<input name="name" autoComplete="name" minLength={2} maxLength={100} required disabled={busy} /></label>}
      {mode === "verify" ? <>
        <label>Код из письма<input name="code" inputMode="numeric" pattern="[0-9]{6}" autoComplete="one-time-code" minLength={6} maxLength={6} required disabled={busy} autoFocus /></label>
        <button className="button dark" disabled={busy} type="submit">{busy ? "Проверяем…" : challenge.purpose === "REGISTER" ? "Подтвердить и завершить регистрацию" : "Подтвердить и войти"}</button>
        <button className="guest-button" disabled={busy || seconds > 0} onClick={resendCode} type="button">{seconds > 0 ? `Отправить повторно через ${seconds} с` : "Отправить код повторно"}</button>
        <button className="guest-button" disabled={busy} onClick={() => { setMode(challenge.purpose === "REGISTER" ? "register" : "login"); setRequest(null); setError(""); }} type="button">Изменить адрес</button>
      </> : <>
        {mode !== "initial" && <label>{mode === "register" || method === "code" ? "Gmail-почта" : "Email"}<input name="email" type="email" autoComplete="email" maxLength={254} placeholder="yourname@gmail.com" required disabled={busy} /></label>}
        {(mode === "register" || mode === "initial" || method === "password") && <label>{mode === "initial" ? "Новый пароль" : "Пароль"}<input name="password" type="password" autoComplete={mode === "login" ? "current-password" : "new-password"} minLength={mode === "login" ? 1 : 12} maxLength={128} required disabled={busy} /></label>}
        {(mode === "register" || mode === "initial") && <p className="auth-hint">От 12 символов: заглавная и строчная буквы, цифра и специальный символ. Не более 72 байт UTF-8.</p>}
        <button className="button dark" disabled={busy} type="submit">{busy ? "Подождите…" : mode === "initial" ? "Сохранить пароль" : mode === "login" && method === "password" ? "Войти" : "Получить код на почту"}</button>
      </>}
      <div className="form-error" role="alert">{error}</div>
    </form>
    {!mandatory && <button className="guest-button" onClick={close} disabled={busy} type="button">Закрыть</button>}
  </Modal>;
}
