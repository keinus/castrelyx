package org.keinus.logparser.infrastructure.persistence.repository;

import org.keinus.logparser.infrastructure.persistence.entity.TransformEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransformRepository extends JpaRepository<TransformEntity, Long> {

    List<TransformEntity> findByType(String type);

    List<TransformEntity> findByMessagetype(String messagetype);

    List<TransformEntity> findByMessagetypeOrderByPriorityAsc(String messagetype);

    List<TransformEntity> findByEnabledTrue();

    Page<TransformEntity> findAll(Pageable pageable);
}
