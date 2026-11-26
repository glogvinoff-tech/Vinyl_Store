export const MAX_GENRES = 8;
export const preferenceKey = userId => `spin-recommendations:v1:${userId || "guest"}`;

export function readPreferences(storage, userId, availableGenres) {
  try {
    const value = JSON.parse(storage.getItem(preferenceKey(userId)) || "[]");
    return Array.isArray(value)
      ? [...new Set(value.filter(genre => typeof genre === "string" && availableGenres.includes(genre)))].slice(0, MAX_GENRES)
      : [];
  } catch { return []; }
}

export function savePreferences(storage, userId, genres) {
  try { storage.setItem(preferenceKey(userId), JSON.stringify(genres.slice(0, MAX_GENRES))); } catch { /* Memory-only preferences still work. */ }
}

export function recommendationQuery(genres) {
  const query = new URLSearchParams({ limit: "3" });
  for (const genre of genres.slice(0, MAX_GENRES)) query.append("genres", genre);
  return `catalog/recommendations?${query}`;
}
