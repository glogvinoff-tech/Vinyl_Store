export const categories = ["Цветной винил", "Подарочные издания", "Русское", "Новые поступления", "Редкий винтаж", "Скидки", "CD", "Эксклюзивы"];
const normalize = value => String(value || "").toLocaleLowerCase("ru").replaceAll("ё", "е");
export function matchesSearch(record, query) {
  const text = normalize([record.artist, record.title, record.genre, record.year, record.description, ...(record.tags || []), record.discountPercent > 0 ? "скидки скидка распродажа" : ""].join(" "));
  return normalize(query).trim().split(/\s+/).every(word => text.includes(word) || (word.length > 4 && text.includes(word.slice(0, -2))));
}
export function matchesCategory(record, category, records) {
  if (!category) return true;
  if (category === "Скидки") return record.discountPercent > 0;
  if (record.tags?.includes(category)) return true;
  if (category === "Новые поступления") return [...records].sort((a,b) => new Date(b.createdAt) - new Date(a.createdAt)).slice(0,12).some(r => r.id === record.id);
  const patterns = {"Цветной винил": /цветн|colou?r.*vinyl/i, "Подарочные издания": /подароч|box set/i, "Русское": /русск|russian/i, "Редкий винтаж": /винтаж|vintage/i, "CD": /\bcd\b|компакт.диск/i, "Эксклюзивы": /эксклюзив|exclusive/i};
  return patterns[category]?.test(record.description || "") || false;
}
