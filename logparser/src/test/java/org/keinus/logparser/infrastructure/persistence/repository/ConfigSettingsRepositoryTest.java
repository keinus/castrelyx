package org.keinus.logparser.infrastructure.persistence.repository;

import org.keinus.logparser.infrastructure.persistence.entity.ConfigSettingsEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class ConfigSettingsRepositoryTest {

    @Autowired
    private ConfigSettingsRepository configSettingsRepository;

    @Test
    void shouldFindByConfigKey() {
        ConfigSettingsEntity setting = ConfigSettingsEntity.builder()
                .configKey("app.name")
                .configValue("LogParser")
                .build();
        configSettingsRepository.save(setting);

        Optional<ConfigSettingsEntity> result = configSettingsRepository.findByConfigKey("app.name");
        assertThat(result).isPresent();
        assertThat(result.get().getConfigValue()).isEqualTo("LogParser");
    }

    @Test
    void shouldDeleteByConfigKey() {
        ConfigSettingsEntity setting = ConfigSettingsEntity.builder()
                .configKey("app.temp")
                .configValue("value")
                .build();
        configSettingsRepository.save(setting);
        assertThat(configSettingsRepository.findByConfigKey("app.temp")).isPresent();

        configSettingsRepository.deleteByConfigKey("app.temp");
        assertThat(configSettingsRepository.findByConfigKey("app.temp")).isEmpty();
    }
}
