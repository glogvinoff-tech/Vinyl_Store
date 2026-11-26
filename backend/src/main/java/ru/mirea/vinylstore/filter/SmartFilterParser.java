package ru.mirea.vinylstore.filter;

import org.springframework.stereotype.Component;
import ru.mirea.vinylstore.domain.PriorityLevel;
import ru.mirea.vinylstore.domain.OrderStatus;
import ru.mirea.vinylstore.error.BadRequestException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбирает человекочитаемый фильтр в типобезопасную модель.
 *
 * <p>Исходная строка фильтра не объединяется с SQL/JPQL. Парсер принимает только
 * известные команды, а Criteria API передаёт значения через параметры.</p>
 */
@Component
public class SmartFilterParser {
    private static final Pattern TOKEN = Pattern.compile("\\\"([^\\\"]*)\\\"|(\\S+)");
    private static final Pattern PRICE = Pattern.compile("^(?:сумма|price|total)(<=|>=|=|<|>)([0-9]+(?:[.,][0-9]{1,2})?)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Разбирает строку; по умолчанию исключает архивные записи. */
    public StoreOrderFilter parse(String source) {
        List<String> free = new ArrayList<>();
        String record = null;
        OrderStatus status = null;
        Boolean paid = null;
        PriorityLevel priority = null;
        String tag = null;
        Boolean archived = false;
        List<StoreOrderFilter.PriceConstraint> prices = new ArrayList<>();

        if (source == null || source.isBlank()) {
            return new StoreOrderFilter("", null, null, null, null, null, false, List.of());
        }
        if (source.length() > 500) throw new BadRequestException("Строка фильтра слишком длинная");

        Matcher matcher = TOKEN.matcher(source.trim());
        while (matcher.find()) {
            String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String token = raw.trim();
            if (token.isEmpty()) continue;
            String lower = token.toLowerCase(Locale.ROOT);

            Matcher priceMatcher = PRICE.matcher(lower);
            if (priceMatcher.matches()) {
                BigDecimal value = new BigDecimal(priceMatcher.group(2).replace(',', '.'));
                if (value.compareTo(new BigDecimal("0.00")) < 0 || value.compareTo(new BigDecimal("9999999999.99")) > 0) {
                    throw new BadRequestException("Значение суммы заказа должно быть от 0 до 9999999999.99");
                }
                prices.add(new StoreOrderFilter.PriceConstraint(operator(priceMatcher.group(1)), value));
                continue;
            }

            int colon = token.indexOf(':');
            if (colon > 0) {
                String key = lower.substring(0, colon);
                String value = stripQuotes(token.substring(colon + 1).trim());
                String normalized = value.toLowerCase(Locale.ROOT);
                switch (key) {
                    case "пластинка", "record" -> record = value;
                    case "статус", "status" -> status = status(normalized);
                    case "оплата", "paid" -> paid = booleanValue(normalized, "оплата");
                    case "приоритет", "priority" -> priority = priority(normalized);
                    case "тег", "tag" -> tag = value;
                    case "архив", "archived" -> archived = archivedValue(normalized);
                    default -> free.add(token);
                }
            } else {
                free.add(token);
            }
        }
        return new StoreOrderFilter(String.join(" ", free), blankToNull(record), status, paid, priority,
                blankToNull(tag), archived, List.copyOf(prices));
    }

    private static StoreOrderFilter.Operator operator(String value) {
        return switch (value) {
            case "<" -> StoreOrderFilter.Operator.LT;
            case "<=" -> StoreOrderFilter.Operator.LTE;
            case "=" -> StoreOrderFilter.Operator.EQ;
            case ">=" -> StoreOrderFilter.Operator.GTE;
            case ">" -> StoreOrderFilter.Operator.GT;
            default -> throw new BadRequestException("Неизвестный оператор суммы заказа");
        };
    }

    private static OrderStatus status(String value) {
        return switch (value.replace('-', '_')) {
            case "новый", "new" -> OrderStatus.NEW;
            case "оплачен", "paid" -> OrderStatus.PAID;
            case "отменен", "canceled" -> OrderStatus.CANCELED;
            case "завершен", "completed" -> OrderStatus.COMPLETED;
            case "отправлен", "shipped" -> OrderStatus.SHIPPED;
            default -> throw new BadRequestException("Неизвестный статус: " + value);
        };
    }

    private static PriorityLevel priority(String value) {
        return switch (value) {
            case "не_применяется", "неприменимо", "not_applicable", "n/a" -> PriorityLevel.NOT_APPLICABLE;
            case "низкий", "low" -> PriorityLevel.LOW;
            case "средний", "medium" -> PriorityLevel.MEDIUM;
            case "высокий", "high" -> PriorityLevel.HIGH;
            case "критический", "critical" -> PriorityLevel.CRITICAL;
            default -> throw new BadRequestException("Неизвестный приоритет: " + value);
        };
    }

    private static Boolean booleanValue(String value, String field) {
        return switch (value) {
            case "да", "yes", "true", "1" -> true;
            case "нет", "no", "false", "0" -> false;
            default -> throw new BadRequestException("Поле «" + field + "» принимает да/нет");
        };
    }

    private static Boolean archivedValue(String value) {
        if (value.equals("все") || value.equals("all")) return null;
        return booleanValue(value, "архив");
    }

    private static String stripQuotes(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
