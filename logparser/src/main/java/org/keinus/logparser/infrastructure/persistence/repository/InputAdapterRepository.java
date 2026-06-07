package org.keinus.logparser.infrastructure.persistence.repository;

import org.keinus.logparser.infrastructure.persistence.entity.InputAdapterEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InputAdapterRepository extends JpaRepository<InputAdapterEntity, Long> {

    List<InputAdapterEntity> findByType(String type);

    Optional<InputAdapterEntity> findByMessagetype(String messagetype);

    List<InputAdapterEntity> findByEnabledTrue();

    Page<InputAdapterEntity> findAll(Pageable pageable);
}
