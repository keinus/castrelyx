package org.keinus.logparser.infrastructure.persistence.repository;

import org.keinus.logparser.infrastructure.persistence.entity.OutputAdapterEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutputAdapterRepository extends JpaRepository<OutputAdapterEntity, Long> {

    List<OutputAdapterEntity> findByType(String type);

    List<OutputAdapterEntity> findByMessagetype(String messagetype);

    List<OutputAdapterEntity> findByEnabledTrue();

    Page<OutputAdapterEntity> findAll(Pageable pageable);
}
