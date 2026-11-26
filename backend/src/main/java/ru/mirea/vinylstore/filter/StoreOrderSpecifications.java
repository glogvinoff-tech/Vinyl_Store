package ru.mirea.vinylstore.filter;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import ru.mirea.vinylstore.domain.StoreOrder;
import ru.mirea.vinylstore.domain.VinylRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Создаёт параметризованную JPA Specification из разобранного фильтра. */
public final class StoreOrderSpecifications {
    private StoreOrderSpecifications() { }

    public static Specification<StoreOrder> from(StoreOrderFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            query.distinct(true);

            if (filter.archived() != null) predicates.add(cb.equal(root.get("archived"), filter.archived()));
            if (filter.status() != null) predicates.add(cb.equal(root.get("status"), filter.status()));
            if (filter.paid() != null) predicates.add(cb.equal(root.get("paid"), filter.paid()));
            if (filter.priority() != null) predicates.add(cb.equal(root.get("priorityLevel"), filter.priority()));

            Join<StoreOrder, VinylRecord> recordJoin = root.join("record", JoinType.LEFT);
            if (filter.record() != null) {
                predicates.add(cb.like(cb.lower(recordJoin.get("code")), "%" + escape(filter.record()) + "%", '\\'));
            }
            if (filter.tag() != null) {
                Join<StoreOrder, String> tagJoin = root.joinSet("tags", JoinType.LEFT);
                predicates.add(cb.equal(cb.lower(tagJoin), filter.tag().toLowerCase(Locale.ROOT)));
            }
            if (filter.freeText() != null && !filter.freeText().isBlank()) {
                String like = "%" + escape(filter.freeText()) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("artist")), like, '\\'),
                        cb.like(cb.lower(root.get("albumTitle")), like, '\\'),
                        cb.like(cb.lower(root.get("customerName")), like, '\\'),
                        cb.like(cb.lower(root.get("email")), like, '\\'),
                        cb.like(cb.lower(root.get("externalId")), like, '\\'),
                        cb.like(cb.lower(recordJoin.get("code")), like, '\\')));
            }
            filter.prices().forEach(constraint -> predicates.add(switch (constraint.operator()) {
                case LT -> cb.lessThan(root.get("totalPrice"), constraint.value());
                case LTE -> cb.lessThanOrEqualTo(root.get("totalPrice"), constraint.value());
                case EQ -> cb.equal(root.get("totalPrice"), constraint.value());
                case GTE -> cb.greaterThanOrEqualTo(root.get("totalPrice"), constraint.value());
                case GT -> cb.greaterThan(root.get("totalPrice"), constraint.value());
            }));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escape(String value) {
        return value.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
