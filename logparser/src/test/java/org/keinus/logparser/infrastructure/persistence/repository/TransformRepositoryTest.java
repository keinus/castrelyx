package org.keinus.logparser.infrastructure.persistence.repository;

import org.keinus.logparser.infrastructure.persistence.entity.TransformEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class TransformRepositoryTest {

    @Autowired
    private TransformRepository transformRepository;

    @Test
    void shouldFindByType() {
        TransformEntity transform = TransformEntity.builder()
                .type("filter")
                .messagetype("syslog")
                .build();
        transformRepository.save(transform);

        List<TransformEntity> result = transformRepository.findByType("filter");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo("filter");
    }

    @Test
    void shouldFindByMessagetype() {
        TransformEntity transform = TransformEntity.builder()
                .type("filter")
                .messagetype("syslog")
                .build();
        transformRepository.save(transform);

        List<TransformEntity> result = transformRepository.findByMessagetype("syslog");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMessagetype()).isEqualTo("syslog");
    }

    @Test
    void shouldFindByMessagetypeOrderByPriorityAsc() {
        transformRepository.save(TransformEntity.builder().type("t1").messagetype("m1").priority(2).build());
        transformRepository.save(TransformEntity.builder().type("t2").messagetype("m1").priority(1).build());

        List<TransformEntity> result = transformRepository.findByMessagetypeOrderByPriorityAsc("m1");
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getType()).isEqualTo("t2");
        assertThat(result.get(1).getType()).isEqualTo("t1");
    }

    @Test
    void shouldFindByEnabledTrue() {
        transformRepository.save(TransformEntity.builder().type("t1").messagetype("m1").enabled(true).build());
        transformRepository.save(TransformEntity.builder().type("t2").messagetype("m1").enabled(false).build());

        List<TransformEntity> result = transformRepository.findByEnabledTrue();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo("t1");
    }

    @Test
    void shouldFindAllWithPageable() {
        transformRepository.save(TransformEntity.builder().type("t1").messagetype("m1").build());
        transformRepository.save(TransformEntity.builder().type("t2").messagetype("m2").build());

        Page<TransformEntity> result = transformRepository.findAll(PageRequest.of(0, 1));
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }
}
