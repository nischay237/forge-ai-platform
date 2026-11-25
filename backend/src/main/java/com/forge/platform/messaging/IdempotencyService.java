package com.forge.platform.messaging;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {

    private final ProcessedEventRepository processedEventRepository;

    public IdempotencyService(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaimEvent(String eventKey, String tenantId, String eventType) {
        if (processedEventRepository.existsById(eventKey)) {
            return false;
        }
        try {
            processedEventRepository.saveAndFlush(new ProcessedEvent(eventKey, tenantId, eventType));
            return true;
        } catch (DataIntegrityViolationException ex) {
            return false;
        }
    }
}
