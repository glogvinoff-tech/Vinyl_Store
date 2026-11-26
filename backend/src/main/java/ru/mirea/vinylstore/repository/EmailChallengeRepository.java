package ru.mirea.vinylstore.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import ru.mirea.vinylstore.domain.EmailChallenge;
import java.util.Optional;
public interface EmailChallengeRepository extends JpaRepository<EmailChallenge,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailChallenge> findByEmail(String email);
}
