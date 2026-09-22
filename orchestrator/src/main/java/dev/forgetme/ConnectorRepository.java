package dev.forgetme;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConnectorRepository extends JpaRepository<Connector, UUID> {

    List<Connector> findByStage(int stage);

    /** The next stage number after this one, or null when there are no more stages. */
    @Query("select min(c.stage) from Connector c where c.stage > :after")
    Integer findNextStage(int after);
}
