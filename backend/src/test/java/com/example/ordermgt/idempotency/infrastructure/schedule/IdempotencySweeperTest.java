package com.example.ordermgt.idempotency.infrastructure.schedule;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ordermgt.idempotency.domain.IdempotencyRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdempotencySweeperTest {

    @Mock private IdempotencyRepository repository;

    @Test
    void theSweeperDeletesExpiredKeysSoRetentionIsRealAndTheTableStaysBounded() {
        when(repository.deleteExpired(any(Instant.class))).thenReturn(3);

        new IdempotencySweeper(repository).deleteExpired();

        verify(repository).deleteExpired(any(Instant.class));
    }
}
