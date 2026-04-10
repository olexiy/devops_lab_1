package de.olexiy.devopsplayground.demo.rating_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_ratings")
@Getter
@Setter
public class CustomerRating {

    @Id
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "rating_score", nullable = false)
    private BigDecimal ratingScore;

    @Column(name = "rating_class", nullable = false, length = 5)
    private String ratingClass;

    @Column(name = "risk_level", nullable = false, length = 20)
    private String riskLevel;

    @Column(name = "calculated_at", nullable = false)
    private LocalDateTime calculatedAt;

    @Column(name = "calculation_version", nullable = false, length = 10)
    private String calculationVersion;

    @Column(name = "avg_balance_12m")
    private BigDecimal avgBalance12m;

    @Column(name = "product_count")
    private Integer productCount;

    @Column(name = "transaction_volume_12m")
    private BigDecimal transactionVolume12m;

    @Column(name = "external_score")
    private BigDecimal externalScore;

    @Column(name = "processing_pod", length = 100)
    private String processingPod;

    @Column(name = "processing_duration_ms")
    private Integer processingDurationMs;
}
