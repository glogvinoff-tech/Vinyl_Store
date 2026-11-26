package ru.mirea.vinylstore.service;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.mirea.vinylstore.config.AppProperties;
import ru.mirea.vinylstore.domain.StoreOrder;
import ru.mirea.vinylstore.domain.OrderStatus;
import ru.mirea.vinylstore.domain.VinylRecord;
import ru.mirea.vinylstore.domain.UserAccount;
import ru.mirea.vinylstore.dto.ImportDtos.ImportMode;
import ru.mirea.vinylstore.dto.ImportDtos.ImportPreview;
import ru.mirea.vinylstore.dto.ImportDtos.ImportResult;
import ru.mirea.vinylstore.dto.ImportDtos.RowIssue;
import ru.mirea.vinylstore.dto.StoreOrderDtos.StoreOrderRequest;
import ru.mirea.vinylstore.dto.StoreOrderDtos.StoreOrderResponse;
import ru.mirea.vinylstore.error.BadRequestException;
import ru.mirea.vinylstore.filter.SmartFilterParser;
import ru.mirea.vinylstore.filter.StoreOrderSpecifications;
import ru.mirea.vinylstore.repository.StoreOrderRepository;
import ru.mirea.vinylstore.repository.VinylRecordRepository;
import ru.mirea.vinylstore.security.CurrentUserService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Защищённый CSV import/export.
 *
 * <p>Предварительная проверка отделена от импорта: ошибки видны до записи в базу.
 * При экспорте нейтрализуются значения, которые Excel может воспринять как формулы.</p>
 */
@Service
public class CsvStoreOrderService {
    private static final String[] HEADERS = {
            "externalId", "artist", "albumTitle", "customerName", "email", "phone",
            "orderedOn", "record", "status", "totalPrice", "paid", "tags", "quantity"
    };

    private final StoreOrderRepository orders;
    private final VinylRecordRepository records;
    private final StoreOrderService orderService;
    private final CurrentUserService currentUser;
    private final SmartFilterParser parser;
    private final StoreOrderMapper mapper;
    private final Validator validator;
    private final AppProperties properties;
    private final AuditService audit;

    public CsvStoreOrderService(StoreOrderRepository orders, VinylRecordRepository records,
                             StoreOrderService orderService, CurrentUserService currentUser,
                             SmartFilterParser parser, StoreOrderMapper mapper, Validator validator,
                             AppProperties properties, AuditService audit) {
        this.orders = orders;
        this.records = records;
        this.orderService = orderService;
        this.currentUser = currentUser;
        this.parser = parser;
        this.mapper = mapper;
        this.validator = validator;
        this.properties = properties;
        this.audit = audit;
    }

    /** Проверяет CSV без записи в базу. */
    @Transactional(readOnly = true)
    public ImportPreview preview(MultipartFile file, ImportMode mode) {
        ParsedFile parsed = parse(file, mode == null ? ImportMode.CREATE_ONLY : mode);
        return new ImportPreview(parsed.totalRows(), parsed.rows().size(), (int) parsed.issues().stream().map(RowIssue::row).distinct().count(), parsed.issues());
    }

    /** Применяет только валидные строки и возвращает детальный итог. */
    @Transactional
    public ImportResult apply(MultipartFile file, ImportMode mode) {
        ImportMode effectiveMode = mode == null ? ImportMode.CREATE_ONLY : mode;
        ParsedFile parsed = parse(file, effectiveMode);
        UserAccount actor = currentUser.requireEntity();
        int created = 0;
        int updated = 0;
        int skipped = (int) parsed.issues().stream().map(RowIssue::row).distinct().count();
        List<RowIssue> runtimeIssues = new ArrayList<>(parsed.issues());

        for (ParsedRow row : parsed.rows()) {
            try {
                StoreOrder existing = orders.findByExternalIdIgnoreCase(row.request().externalId()).orElse(null);
                if (existing != null && effectiveMode == ImportMode.CREATE_ONLY) {
                    runtimeIssues.add(new RowIssue(row.rowNumber(), "externalId", "Запись уже существует"));
                    skipped++;
                    continue;
                }
                orderService.upsertFromImport(existing, row.request(), actor);
                if (existing == null) created++; else updated++;
            } catch (RuntimeException ex) {
                runtimeIssues.add(new RowIssue(row.rowNumber(), "row", safeMessage(ex)));
                skipped++;
            }
        }
        audit.record("ORDER_CSV_IMPORTED", "ORDER", null, "Импортирован CSV со заказами",
                Map.of("created", created, "updated", updated, "skipped", skipped, "mode", effectiveMode.name()));
        return new ImportResult(parsed.totalRows(), created, updated, skipped, List.copyOf(runtimeIssues));
    }

    /** Потоково экспортирует выборку и сохраняет событие аудита после записи файла. */
    @Transactional
    public void export(String query, OutputStream output) throws IOException {
        // BOM записывается до создания CSVPrinter, иначе заголовок окажется перед BOM.
        output.write("\ufeff".getBytes(StandardCharsets.UTF_8));
        try (Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(HEADERS).get())) {
            int page = 0;
            while (true) {
                var result = orders.findAll(StoreOrderSpecifications.from(parser.parse(query)),
                        PageRequest.of(page, 500, Sort.by("artist").ascending().and(Sort.by("albumTitle").ascending())));
                for (StoreOrder order : result.getContent()) {
                    StoreOrderResponse dto = mapper.toResponse(order);
                    printer.printRecord(
                            safeCell(dto.externalId()), safeCell(dto.artist()), safeCell(dto.albumTitle()),
                            safeCell(dto.customerName()), safeCell(dto.email()), safeCell(dto.phone()),
                            dto.orderedOn(), safeCell(dto.recordCode()), dto.status(), dto.totalPrice(),
                            dto.paid(), safeCell(String.join("|", dto.tags())), dto.quantity());
                }
                if (result.isLast()) break;
                page++;
            }
            printer.flush();
        }
        audit.record("ORDER_CSV_EXPORTED", "ORDER", null, "Экспортирована выборка заказов", Map.of("query", query == null ? "" : query));
    }

    private java.io.PushbackReader csvReader(MultipartFile file) throws IOException {
        var reader = new java.io.PushbackReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8), 1);
        int first = reader.read();
        if (first != -1 && first != 0xFEFF) reader.unread(first);
        return reader;
    }

    private ParsedFile parse(MultipartFile file, ImportMode mode) {
        if (file == null || file.isEmpty()) throw new BadRequestException("Выберите непустой CSV-файл");
        List<ParsedRow> validRows = new ArrayList<>();
        List<RowIssue> issues = new ArrayList<>();
        Set<String> externalIdsInFile = new LinkedHashSet<>();
        int total = 0;

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();
        try (java.io.PushbackReader reader = csvReader(file);
             CSVParser csv = CSVParser.parse(reader, format)) {
            validateHeaders(csv.getHeaderMap());
            for (CSVRecord record : csv) {
                total++;
                long rowNumber = record.getRecordNumber() + 1;
                if (total > properties.importLimits().maxRows()) {
                    throw new BadRequestException("CSV содержит больше " + properties.importLimits().maxRows() + " строк");
                }
                try {
                    ParsedRow row = toRow(record, rowNumber);
                    Set<ConstraintViolation<StoreOrderRequest>> violations = validator.validate(row.request());
                    if (!violations.isEmpty()) {
                        for (ConstraintViolation<StoreOrderRequest> violation : violations) {
                            issues.add(new RowIssue(rowNumber, violation.getPropertyPath().toString(), violation.getMessage()));
                        }
                        continue;
                    }
                    String key = row.request().externalId().toLowerCase(Locale.ROOT);
                    if (!externalIdsInFile.add(key)) {
                        issues.add(new RowIssue(rowNumber, "externalId", "Дубликат externalId внутри файла"));
                        continue;
                    }
                    if (mode == ImportMode.CREATE_ONLY && orders.existsByExternalIdIgnoreCase(row.request().externalId())) {
                        issues.add(new RowIssue(rowNumber, "externalId", "Запись уже существует"));
                        continue;
                    }
                    validRows.add(row);
                } catch (RuntimeException ex) {
                    issues.add(new RowIssue(rowNumber, "row", safeMessage(ex)));
                }
            }
        } catch (IOException | UncheckedIOException ex) {
            // CSVParser wraps errors raised while advancing its iterator.
            // Reject the entire file before apply() starts writing any rows.
            throw new BadRequestException("Некорректная структура CSV или ошибка чтения файла");
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Некорректная структура CSV: " + ex.getMessage());
        }
        return new ParsedFile(total, List.copyOf(validRows), List.copyOf(issues));
    }

    private ParsedRow toRow(CSVRecord record, long rowNumber) {
        String externalId = required(record, "externalId");
        String recordCode = nullable(record, "record");
        VinylRecord vinyl = recordCode == null ? null : records.findByCodeIgnoreCase(recordCode)
                .orElseThrow(() -> new BadRequestException("Пластинка не найдена: " + recordCode));
        if (vinyl != null && !vinyl.isActive()) throw new BadRequestException("Пластинка неактивна: " + recordCode);

        StoreOrderRequest request = new StoreOrderRequest(
                externalId,
                required(record, "albumTitle"),
                required(record, "artist"),
                nullable(record, "customerName"),
                nullable(record, "email"),
                nullable(record, "phone"),
                parseDate(nullable(record, "orderedOn")),
                vinyl == null ? null : vinyl.getId(),
                parseStatus(required(record, "status")),
                parsePrice(nullable(record, "totalPrice")),
                parseBoolean(nullable(record, "paid")),
                parseTags(nullable(record, "tags")),
                Integer.valueOf(required(record,"quantity")), null);
        return new ParsedRow(rowNumber, request);
    }

    private static void validateHeaders(Map<String, Integer> actual) {
        for (String header : HEADERS) {
            if (!actual.containsKey(header)) throw new BadRequestException("Отсутствует колонка: " + header);
        }
    }

    private static String required(CSVRecord record, String field) {
        String value = nullable(record, field);
        if (value == null) throw new BadRequestException("Не заполнено поле " + field);
        return value;
    }

    private static String nullable(CSVRecord record, String field) {
        String value = record.get(field);
        if (value == null) return null;
        value = value.replace("\ufeff", "").trim();
        // Обратное преобразование защитного апострофа нашего экспорта.
        if (value.length() > 1 && value.charAt(0) == '\'' && "=+-@\t\r".indexOf(value.charAt(1)) >= 0) value=value.substring(1);
        return value.isEmpty() ? null : value;
    }

    private static LocalDate parseDate(String value) {
        if (value == null) return null;
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException ex) { throw new BadRequestException("Дата должна иметь формат YYYY-MM-DD"); }
    }

    private static BigDecimal parsePrice(String value) {
        if (value == null) return null;
        try { return new BigDecimal(value.replace(',', '.')); }
        catch (NumberFormatException ex) { throw new BadRequestException("Некорректный totalPrice"); }
    }

    private static boolean parseBoolean(String value) {
        if (value == null) return false;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "да" -> true;
            case "false", "0", "no", "нет" -> false;
            default -> throw new BadRequestException("paid принимает true/false или да/нет");
        };
    }

    private static OrderStatus parseStatus(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "new", "новый" -> OrderStatus.NEW;
            case "paid", "оплачен" -> OrderStatus.PAID;
            case "canceled", "отменен" -> OrderStatus.CANCELED;
            case "completed", "завершен" -> OrderStatus.COMPLETED;
            case "shipped", "отправлен" -> OrderStatus.SHIPPED;
            default -> throw new BadRequestException("Неизвестный status: " + value);
        };
    }

    private static Set<String> parseTags(String value) {
        if (value == null) return Set.of();
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        Arrays.stream(value.split("\\|")).map(String::trim).filter(v -> !v.isEmpty())
                .map(v -> v.toLowerCase(Locale.ROOT)).forEach(tags::add);
        return tags;
    }

    private static String safeCell(Object value) {
        if (value == null) return "";
        String cell = String.valueOf(value);
        if (!cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0) return "'" + cell;
        return cell;
    }

    private static String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "Некорректная строка" : message;
    }

    private record ParsedRow(long rowNumber, StoreOrderRequest request) { }
    private record ParsedFile(int totalRows, List<ParsedRow> rows, List<RowIssue> issues) { }
}
