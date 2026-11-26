// Access JWT stays in memory; the browser manages the HttpOnly refresh cookie.
let accessToken = null;
let refreshPromise = null;

export function setSession(session) {
  accessToken = session?.accessToken || null;
  return userModel(session?.user);
}

export function userModel(user) {
  if (!user) return null;
  return {
    ...user,
    name: user.displayName,
    role: user.roles.includes("ADMIN") ? "admin" : user.roles.includes("MANAGER") ? "manager" : "customer",
  };
}

export async function restoreSession() {
  if (!refreshPromise) {
    refreshPromise = fetch("/api/auth/refresh", { method: "POST", credentials: "same-origin" })
      .then(async response => {
        if (response.status === 401) { setSession(null); return null; }
        const result = await response.json();
        if (!response.ok) throw new Error(result.message || "Не удалось обновить сессию");
        return setSession(result);
      })
      .finally(() => { refreshPromise = null; });
  }
  return refreshPromise;
}

export async function api(path, data, options = {}, retry = true) {
  const headers = new Headers(options.headers);
  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
  if (data !== undefined && !(data instanceof FormData)) headers.set("Content-Type", "application/json");
  const response = await fetch(`/api/${path.replace(/^\//, "")}`, {
    credentials: "same-origin",
    ...(data === undefined ? {} : { method: "POST", body: data instanceof FormData ? data : JSON.stringify(data) }),
    ...options,
    headers,
  });
  // Protected auth actions also need a fresh JWT. Login/code checks must never
  // retry: a wrong password or code is an authentication failure of its own.
  const endpoint = path.replace(/^\//, "").split("?")[0];
  const credentialEndpoints = ["auth/login", "auth/refresh", "auth/logout", "auth/email/request", "auth/email/verify"];
  if (response.status === 401 && retry && !credentialEndpoints.includes(endpoint)) {
    const user = await restoreSession();
    if (user) return api(path, data, options, false);
  }
  const result = response.status === 204 ? null : await response.json();
  if (!response.ok) {
    const fields = Object.values(result?.validationErrors || {});
    const error = new Error(fields.length ? `${result.message}: ${fields.join("; ")}` : result?.message || "Не удалось выполнить запрос");
    error.status = response.status;
    throw error;
  }
  return result;
}

const genreNames = { Rock: "Рок", Pop: "Поп", Indie: "Инди", Electronic: "Электроника", "Hip Hop": "Хип-хоп", Jazz: "Джаз", Classical: "Классика", Punk: "Панк", Country: "Кантри", Metal: "Метал", Blues: "Блюз", Alternative: "Альтернатива", Reggae: "Регги", Soul: "Соул", Funk: "Фанк", Disco: "Диско" };
export function recordModel(record) {
  return { ...record, title: record.name, year: record.releaseYear, genreCode: record.genre,
    genre: genreNames[record.genre] || record.genre || "Другое", cover_url: record.cover, originalPrice: Number(record.price), price: Number(record.salePrice ?? record.price) };
}
export async function loadCatalog() {
  const first = await api("catalog?size=100");
  const rest = await Promise.all(Array.from({ length: Math.max(0, first.totalPages - 1) }, (_, i) => api(`catalog?size=100&page=${i + 1}`)));
  return [...first.content, ...rest.flatMap(page => page.content)].map(recordModel);
}
export const money = value => new Intl.NumberFormat("ru-RU").format(value) + " ₽";
