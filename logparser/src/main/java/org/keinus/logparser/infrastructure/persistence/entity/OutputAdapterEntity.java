package org.keinus.logparser.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.keinus.logparser.infrastructure.persistence.converter.CryptoConverter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "output_adapters")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class OutputAdapterEntity {

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

    @Column(name = "url", length = 1000)
    private String url;

    @Column(name = "method", length = 20)
    private String method;

    @Column(name = "headers", columnDefinition = "TEXT")
    private String headers;

    @Column(name = "topicid", length = 255)
    private String topicid;

    @Column(name = "bootstrapservers", length = 500)
    private String bootstrapservers;

    @Column(name = "\"key\"", length = 255)
    private String key;

    @Column(name = "index_template", length = 255)
    private String indexTemplate;

    @Column(name = "os_username", length = 255)
    private String osUsername;

    @Column(name = "os_password", length = 500)
    @Convert(converter = CryptoConverter.class)
    private String osPassword;

    @Column(name = "action", length = 50)
    private String action;

    @Column(name = "routingkey", length = 255)
    private String routingkey;

    @Column(name = "exchange", length = 255)
    private String exchange;

    @Column(name = "rmq_username", length = 255)
    private String rmqUsername;

    @Column(name = "rmq_password", length = 500)
    @Convert(converter = CryptoConverter.class)
    private String rmqPassword;

    @Column(name = "rmq_port")
    private Integer rmqPort;

    @Column(name = "tagpass", columnDefinition = "TEXT")
    private String tagpass;

    @Column(name = "batch_size")
    private Integer batchSize;

    @Column(name = "flush_interval_ms")
    private Integer flushIntervalMs;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "retry_delay_ms")
    private Integer retryDelayMs;

    @Column(name = "add_origin_text")
    @Builder.Default
    private Boolean addOriginText = false;

    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "timeout_ms")
    private Integer timeoutMs;

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
