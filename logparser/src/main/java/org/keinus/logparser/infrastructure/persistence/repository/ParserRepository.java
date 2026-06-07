package org.keinus.logparser.infrastructure.persistence.repository;

import org.keinus.logparser.infrastructure.persistence.entity.ParserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ParserRepository extends JpaRepository<ParserEntity, Long> {

    List<ParserEntity> findByType(String type);

    List<ParserEntity> findByMessagetype(String messagetype);

    List<ParserEntity> findByMessagetypeOrderByPriorityAsc(String messagetype);

    List<ParserEntity> findByEnabledTrue();

    Page<ParserEntity> findAll(Pageable pageable);
}
