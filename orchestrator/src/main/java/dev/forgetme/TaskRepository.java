package dev.forgetme;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    List<Task> findByRequestId(UUID requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Task> findLockedById(UUID id);
}
