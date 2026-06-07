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
@Table(name = "input_adapters")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class InputAdapterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "type", nullable = false, length = 100)
    private String type;

    @Column(name = "messagetype", nullable = false, length = 100)
    private String messagetype;

    @Column(name = "host", length = 255)
    private String host;

    @Column(name = "port")
    private Integer port;

    @Column(name = "path", length = 500)
    private String path;

    @Column(name = "topicid", length = 255)
    private String topicid;

    @Column(name = "bootstrapservers", length = 500)
    private String bootstrapservers;

    @Column(name = "group_id", length = 255)
    private String groupId;

    @Column(name = "codec", length = 50)
    private String codec;

    @Column(name = "path_pattern", length = 500)
    private String pathPattern;

    @Column(name = "is_from_beginning")
    @Builder.Default
    private Boolean isFromBeginning = false;

    @Column(name = "buffer_size")
    private Integer bufferSize;

    @Column(name = "timeout_ms")
    private Integer timeoutMs;

    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "worker_threads")
    private Integer workerThreads;

    @Column(name = "queue_size")
    private Integer queueSize;

    @Column(name = "config_params", columnDefinition = "TEXT")
    private String configParams;

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
