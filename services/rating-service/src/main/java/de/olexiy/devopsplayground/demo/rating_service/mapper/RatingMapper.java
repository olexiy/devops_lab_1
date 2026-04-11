package de.olexiy.devopsplayground.demo.rating_service.mapper;

import com.bank.rating.model.RatingResponse;
import de.olexiy.devopsplayground.demo.rating_service.entity.CustomerRating;
import org.mapstruct.Mapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Mapper(componentModel = "spring")
public interface RatingMapper {

    RatingResponse toResponse(CustomerRating entity);

    default OffsetDateTime map(LocalDateTime value) {
        return value != null ? value.atOffset(ZoneOffset.UTC) : null;
    }
}
