package de.olexiy.devopsplayground.demo.rating_service.service;

import de.olexiy.devopsplayground.demo.rating_service.dto.RatingResponse;
import de.olexiy.devopsplayground.demo.rating_service.exception.RatingNotFoundException;
import de.olexiy.devopsplayground.demo.rating_service.mapper.RatingMapper;
import de.olexiy.devopsplayground.demo.rating_service.repository.CustomerRatingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RatingService {

    private final CustomerRatingRepository repository;
    private final RatingMapper mapper;

    public RatingResponse getByCustomerId(Long customerId) {
        return repository.findById(customerId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new RatingNotFoundException(customerId));
    }

    public Page<RatingResponse> getAll(Pageable pageable) {
        return repository.findAll(pageable).map(mapper::toResponse);
    }
}
