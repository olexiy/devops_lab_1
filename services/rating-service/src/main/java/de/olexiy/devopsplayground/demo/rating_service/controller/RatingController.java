package de.olexiy.devopsplayground.demo.rating_service.controller;

import com.bank.rating.api.ApiApi;
import com.bank.rating.model.PageRatingResponse;
import com.bank.rating.model.RatingResponse;
import de.olexiy.devopsplayground.demo.rating_service.service.RatingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RatingController implements ApiApi {

    private final RatingService service;

    @Override
    public ResponseEntity<RatingResponse> getRatingByCustomerId(Long customerId) {
        return ResponseEntity.ok(service.getByCustomerId(customerId));
    }

    @Override
    public ResponseEntity<PageRatingResponse> listRatings(Integer page, Integer size) {
        return ResponseEntity.ok(service.getAll(page, size));
    }
}
