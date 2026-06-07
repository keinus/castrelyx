package org.keinus.logparser.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "transforms")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class TransformEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "type", nullable = false, length = 100)
    private String type;

    @Column(name = "messagetype", nullable = false, length = 100)
    private String messagetype;

    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 0;

    @Column(name = "filter_pass", columnDefinition = "TEXT")
    private String filterPass;

    @Column(name = "filter_drop", columnDefinition = "TEXT")
    private String filterDrop;

    @Column(name = "add_properties", columnDefinition = "TEXT")
    private String addProperties;

    @Column(name = "remove_properties", columnDefinition = "TEXT")
    private String removeProperties;

    @Column(name = "config_params", columnDefinition = "TEXT")
    private String configParams;

    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version")
    private Integer version;
}
