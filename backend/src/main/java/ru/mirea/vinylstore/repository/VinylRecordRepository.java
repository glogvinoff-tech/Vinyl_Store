package ru.mirea.vinylstore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mirea.vinylstore.domain.VinylRecord;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence gateway for study records. */
public interface VinylRecordRepository extends JpaRepository<VinylRecord, UUID>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<VinylRecord> {
    Optional<VinylRecord> findByCodeIgnoreCase(String code);
    boolean existsByCodeIgnoreCase(String code);
    List<VinylRecord> findAllByOrderByCodeAsc();
    List<VinylRecord> findByActiveTrueOrderByCodeAsc();
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from VinylRecord r where r.id=:id")
    Optional<VinylRecord> lockById(@org.springframework.data.repository.query.Param("id") UUID id);
}

