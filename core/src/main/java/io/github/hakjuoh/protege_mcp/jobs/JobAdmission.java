package io.github.hakjuoh.protege_mcp.jobs;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded pre-capture admission reservation.
 *
 * <p>The live adapter obtains this permit before allocating a detached ontology or reading a
 * secondary document. Closing an unconsumed permit releases its quota immediately.</p>
 */
public final class JobAdmission implements AutoCloseable {
    final JobService service;
    final long token;
    final JobOwner owner;
    final JobType type;
    final String idempotencyKey;
    final JobRuntimeConfig config;
    final boolean quotaReserved;
    final AtomicBoolean finished = new AtomicBoolean();

    JobAdmission(JobService service, long token, JobOwner owner, JobType type,
            String idempotencyKey, JobRuntimeConfig config, boolean quotaReserved) {
        this.service = Objects.requireNonNull(service, "service");
        this.token = token;
        this.owner = Objects.requireNonNull(owner, "owner");
        this.type = Objects.requireNonNull(type, "type");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.config = Objects.requireNonNull(config, "config");
        this.quotaReserved = quotaReserved;
    }

    @Override
    public void close() {
        service.releaseAdmission(this);
    }
}
