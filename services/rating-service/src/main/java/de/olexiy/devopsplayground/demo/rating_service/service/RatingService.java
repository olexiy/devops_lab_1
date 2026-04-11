package de.olexiy.devopsplayground.demo.rating_service.service;

import com.bank.rating.model.PageMetadata;
import com.bank.rating.model.PageRatingResponse;
import com.bank.rating.model.RatingResponse;
import de.olexiy.devopsplayground.demo.rating_service.exception.RatingNotFoundException;
import de.olexiy.devopsplayground.demo.rating_service.mapper.RatingMapper;
import de.olexiy.devopsplayground.demo.rating_service.repository.CustomerRatingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RatingService {

    private final CustomerRatingRepository repository;
    private final RatingMapper mapper;

    public RatingResponse getByCustomerId(Long customerId) {
        return repository.findById(customerId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new RatingNotFoundException(customerId));
    }

    public PageRatingResponse getAll(Integer page, Integer size) {
        var pageable = PageRequest.of(page, size, Sort.by("customerId").ascending());
        var result = repository.findAll(pageable);

        var meta = new PageMetadata()
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast());

        return new PageRatingResponse()
                .content(result.getContent().stream().map(mapper::toResponse).toList())
                .page(meta);
    }
}
