package org.keinus.logparser.infrastructure.persistence.repository;

import org.keinus.logparser.infrastructure.persistence.entity.ParserEntity;
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
class ParserRepositoryTest {

    @Autowired
    private ParserRepository parserRepository;

    @Test
    void shouldFindByType() {
        ParserEntity parser = ParserEntity.builder()
                .type("grok")
                .messagetype("syslog")
                .build();
        parserRepository.save(parser);

        List<ParserEntity> result = parserRepository.findByType("grok");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo("grok");
    }

    @Test
    void shouldFindByMessagetype() {
        ParserEntity parser = ParserEntity.builder()
                .type("grok")
                .messagetype("syslog")
                .build();
        parserRepository.save(parser);

        List<ParserEntity> result = parserRepository.findByMessagetype("syslog");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMessagetype()).isEqualTo("syslog");
    }

    @Test
    void shouldFindByMessagetypeOrderByPriorityAsc() {
        parserRepository.save(ParserEntity.builder().type("p1").messagetype("m1").priority(2).build());
        parserRepository.save(ParserEntity.builder().type("p2").messagetype("m1").priority(1).build());

        List<ParserEntity> result = parserRepository.findByMessagetypeOrderByPriorityAsc("m1");
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getType()).isEqualTo("p2");
        assertThat(result.get(1).getType()).isEqualTo("p1");
    }

    @Test
    void shouldFindByEnabledTrue() {
        parserRepository.save(ParserEntity.builder().type("p1").messagetype("m1").enabled(true).build());
        parserRepository.save(ParserEntity.builder().type("p2").messagetype("m1").enabled(false).build());

        List<ParserEntity> result = parserRepository.findByEnabledTrue();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo("p1");
    }

    @Test
    void shouldFindAllWithPageable() {
        parserRepository.save(ParserEntity.builder().type("p1").messagetype("m1").build());
        parserRepository.save(ParserEntity.builder().type("p2").messagetype("m2").build());

        Page<ParserEntity> result = parserRepository.findAll(PageRequest.of(0, 1));
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }
}
