package org.keinus.logparser.infrastructure.persistence.repository;

import org.keinus.logparser.infrastructure.persistence.entity.ConfigSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConfigSettingsRepository extends JpaRepository<ConfigSettingsEntity, Long> {

    Optional<ConfigSettingsEntity> findByConfigKey(String configKey);

    void deleteByConfigKey(String configKey);
}
