package de.olexiy.devopsplayground.demo.rating_service.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Value
@Builder
public class RatingResponse {
    Long customerId;
    BigDecimal ratingScore;
    String ratingClass;
    String riskLevel;
    LocalDateTime calculatedAt;
    String calculationVersion;
    BigDecimal avgBalance12m;
    Integer productCount;
    BigDecimal transactionVolume12m;
}
