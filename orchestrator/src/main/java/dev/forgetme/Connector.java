package dev.forgetme;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

/** A system that holds personal data and can delete it when asked. */
@Entity
public class Connector {

    @Id
    private UUID id;
    private String name;
    private String endpointUrl;
    private byte[] secretEnc;
    private int stage;
    private Instant createdAt;

    protected Connector() {}

    Connector(String name, String endpointUrl, byte[] secretEnc, int stage) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.endpointUrl = endpointUrl;
        this.secretEnc = secretEnc;
        this.stage = stage;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getEndpointUrl() { return endpointUrl; }
    public byte[] getSecretEnc() { return secretEnc; }
    public int getStage() { return stage; }
}
