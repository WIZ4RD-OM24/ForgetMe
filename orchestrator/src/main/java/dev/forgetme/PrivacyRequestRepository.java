package dev.forgetme;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PrivacyRequestRepository extends JpaRepository<PrivacyRequest, UUID> {

    /** SELECT ... FOR UPDATE: parallel verify calls queue up instead of racing past the attempt limit. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PrivacyRequest> findLockedById(UUID id);

    @Query("select r.id from PrivacyRequest r where r.status = dev.forgetme.RequestStatus.WAITING and r.runAfter <= :now")
    List<UUID> findIdsReadyToStart(Instant now);

    @Query("select r.id from PrivacyRequest r where r.status = dev.forgetme.RequestStatus.RECEIVED and r.codeExpiresAt < :now")
    List<UUID> findIdsWithExpiredCode(Instant now);
}
