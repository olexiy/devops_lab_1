package de.olexiy.devopsplayground.demo.rating_service.dto;

import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;

@Value
@Builder
public class ErrorResponse {
    OffsetDateTime timestamp;
    int status;
    String error;
    String message;
    String path;
}
