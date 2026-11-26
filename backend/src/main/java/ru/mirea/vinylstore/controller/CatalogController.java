package ru.mirea.vinylstore.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import ru.mirea.vinylstore.domain.*;
import ru.mirea.vinylstore.dto.RecordDtos.RecordResponse;
import ru.mirea.vinylstore.dto.StoreOrderDtos.StoreOrderResponse;
import ru.mirea.vinylstore.dto.PageResponse;
import ru.mirea.vinylstore.error.*;
import ru.mirea.vinylstore.repository.*;
import ru.mirea.vinylstore.security.CurrentUserService;
import ru.mirea.vinylstore.service.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
public class CatalogController {
    private static final BigDecimal MAX_CHECKOUT_TOTAL = new BigDecimal("9999999999.99");

    private final VinylRecordRepository records;
    private final StoreOrderRepository orders;
    private final CurrentUserService current;
    private final StoreOrderMapper mapper;
    private final PriorityCalculator priority;
    private final AuditService audit;
    public CatalogController(VinylRecordRepository records,StoreOrderRepository orders,CurrentUserService current,
        StoreOrderMapper mapper, PriorityCalculator priority, AuditService audit) {
        this.records=records;this.orders=orders;this.current=current;this.mapper=mapper;
        this.priority=priority;this.audit=audit;
    }
    @GetMapping("/api/catalog")
    @Transactional(readOnly=true)
    public PageResponse<RecordResponse> catalog(@RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="") String genre,@RequestParam(defaultValue="false") boolean inStock,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="12") int size,
        @RequestParam(defaultValue="code,asc") String sort) {
        if(page<0 || size<1 || size>100 || q.length()>200) throw new BadRequestException("Некорректные параметры страницы");
        String[] parts=sort.split(",",2);
        if(!Set.of("code","name","price","artist","releaseYear").contains(parts[0])) throw new BadRequestException("Недопустимая сортировка");
        Sort order=Sort.by(parts.length>1 && parts[1].equals("desc")?Sort.Direction.DESC:Sort.Direction.ASC,parts[0]);
        Specification<VinylRecord> spec=(r,query,cb)-> {
            var list=new ArrayList<jakarta.persistence.criteria.Predicate>();list.add(cb.isTrue(r.get("active")));
            String like="%"+q.toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
            if(!q.isBlank())list.add(cb.or(cb.like(cb.lower(r.get("name")),like,'\\'),cb.like(cb.lower(r.get("categories")),like,'\\'),cb.like(cb.lower(r.get("description")),like,'\\'),cb.like(cb.lower(r.get("genre")),like,'\\'),cb.like(cb.lower(r.get("artist")),like,'\\')));
            if(!genre.isBlank())list.add(cb.equal(cb.lower(r.get("genre")),genre.toLowerCase(Locale.ROOT)));
            if(inStock)list.add(cb.greaterThan(r.get("stock"),0));
            return cb.and(list.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return PageResponse.from(records.findAll(spec,PageRequest.of(page,size,order)).map(CatalogController::dto));
    }
    @GetMapping("/api/catalog/{id}") @Transactional(readOnly=true)
    public RecordResponse one(@PathVariable UUID id) {
        return dto(records.findById(id).filter(VinylRecord::isActive).orElseThrow(()->new NotFoundException("Альбом не найден")));
    }
    public record CartLine(@NotNull UUID recordId, @Min(1) @Max(100) int quantity) { }

    public record Checkout(
            @NotEmpty @Size(max = 50) List<@NotNull @Valid CartLine> lines,
            @NotBlank @Size(max = 100) String customerName,
            @Pattern(regexp = "^\\+7\\d{10}$") String phone) { }

    @PostMapping("/api/shop/checkout")
    @Transactional
    public List<StoreOrderResponse> checkout(@Valid @RequestBody Checkout request) {
        UserAccount user = current.requireEntity();
        // Lock inventory in the same order for all carts.
        var quantities = new TreeMap<UUID, Integer>();
        for (CartLine line : request.lines()) {
            quantities.merge(line.recordId(), line.quantity(), Integer::sum);
        }

        var result = new ArrayList<StoreOrderResponse>(quantities.size());
        BigDecimal total = BigDecimal.ZERO;
        for (var line : quantities.entrySet()) {
            int quantity = line.getValue();
            if (quantity > 100) throw new BadRequestException("Не более 100 экземпляров одного альбома");
            VinylRecord record = records.lockById(line.getKey()).filter(VinylRecord::isActive)
                    .orElseThrow(() -> new NotFoundException("Альбом не найден"));
            if (record.getStock() < quantity) {
                throw new ConflictException("Недостаточно пластинок: " + record.getName());
            }
            BigDecimal lineTotal = record.getSalePrice().multiply(BigDecimal.valueOf(quantity));
            total = total.add(lineTotal);
            if (total.compareTo(MAX_CHECKOUT_TOTAL) > 0) {
                throw new BadRequestException("Сумма корзины превышает допустимый предел");
            }

            record.setStock(record.getStock() - quantity);
            StoreOrder order = new StoreOrder(UUID.randomUUID(), record.getName(), record.getArtist(), user);
            order.setExternalId("WEB-" + UUID.randomUUID());
            order.setRecord(record);
            order.setCustomerName(request.customerName().trim());
            order.setEmail(user.getEmail());
            order.setPhone(request.phone());
            order.setOrderedOn(LocalDate.now());
            order.setQuantity(quantity);
            order.setTotalPrice(lineTotal);
            order.setPriorityLevel(priority.calculate(order.getStatus(), lineTotal, false));
            orders.save(order);
            audit.record("ORDER_CHECKOUT", "ORDER", order.getId(), "Создан заказ на самовывоз",
                    Map.of("quantity", quantity));
            result.add(mapper.toResponse(order));
        }
        return result;
    }
    @GetMapping("/api/shop/orders") @Transactional(readOnly=true)
    public List<StoreOrderResponse> myOrders() {
        UUID id=current.requireEntity().getId();
        return orders.findByCreatedByIdOrderByCreatedAtDesc(id).stream().map(mapper::toResponse).toList();
    }
    static RecordResponse dto(VinylRecord r) {
        return RecordService.toResponse(r);
    }
}
