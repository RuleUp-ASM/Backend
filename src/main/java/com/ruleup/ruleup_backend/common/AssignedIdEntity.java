package com.ruleup.ruleup_backend.common;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;
import java.util.UUID;

@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<UUID> {

    @Transient
    private boolean isNew = true;

    @Override public boolean isNew() { return isNew; }

    @PostPersist @PostLoad
    void markNotNew() { this.isNew = false; }
}