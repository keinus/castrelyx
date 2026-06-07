package org.keinus.logparser.domain.transformation.service;

import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.infrastructure.persistence.repository.MappingRepository;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredTransformServiceTest {

    @Test
    void reloadClearsMappingAndExpressionCaches() {
        MappingRepository mappingRepository = mock(MappingRepository.class);
        ConditionEvaluator conditionEvaluator = mock(ConditionEvaluator.class);
        StructuredEventSerializer serializer = mock(StructuredEventSerializer.class);

        StructuredTransformService service = new StructuredTransformService(
                mappingRepository,
                conditionEvaluator,
                serializer
        );

        service.reload();

        verify(conditionEvaluator).clearCache();
    }

    @Test
    void invalidateCacheForcesRepositoryReload() {
        MappingRepository mappingRepository = mock(MappingRepository.class);
        ConditionEvaluator conditionEvaluator = mock(ConditionEvaluator.class);
        StructuredEventSerializer serializer = mock(StructuredEventSerializer.class);
        when(mappingRepository.findByMessageType("test"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());

        StructuredTransformService service = new StructuredTransformService(
                mappingRepository,
                conditionEvaluator,
                serializer
        );

        service.transform(new LogEvent("first", "localhost", "test"));
        service.invalidateCache("test");
        service.transform(new LogEvent("second", "localhost", "test"));

        verify(mappingRepository, times(2)).findByMessageType("test");
        verify(conditionEvaluator, times(1)).clearCache();
    }
}
