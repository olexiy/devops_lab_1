package de.olexiy.devopsplayground.demo.rating_service.mapper;

import de.olexiy.devopsplayground.demo.rating_service.dto.RatingResponse;
import de.olexiy.devopsplayground.demo.rating_service.entity.CustomerRating;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RatingMapper {

    RatingResponse toResponse(CustomerRating entity);
}
