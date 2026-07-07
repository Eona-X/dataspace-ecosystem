package org.eclipse.edc.dse.telemetry.repository;

import jakarta.persistence.EntityManager;
import org.eclipse.edc.dse.telemetry.model.ParticipantId;

public class ParticipantRepository extends GenericRepository<ParticipantId> {
    public ParticipantRepository(EntityManager em) {
        super(em, ParticipantId.class);
    }

    public ParticipantId findByParticipantName(String participantName) {
        return em.createQuery("SELECT p FROM ParticipantId p WHERE p.name = :participantName", ParticipantId.class)
                .setParameter("participantName", participantName)
                .getResultList().stream().findFirst().orElse(null);
    }
}
