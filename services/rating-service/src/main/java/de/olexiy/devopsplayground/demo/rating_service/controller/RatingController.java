package de.olexiy.devopsplayground.demo.rating_service.controller;

import de.olexiy.devopsplayground.demo.rating_service.dto.RatingResponse;
import de.olexiy.devopsplayground.demo.rating_service.service.RatingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ratings")
@RequiredArgsConstructor
public class RatingController {

    private final RatingService service;

    @GetMapping("/{customerId}")
    ResponseEntity<RatingResponse> getByCustomerId(@PathVariable Long customerId) {
        return ResponseEntity.ok(service.getByCustomerId(customerId));
    }

    @GetMapping
    ResponseEntity<Page<RatingResponse>> getAll(
            @PageableDefault(size = 20, sort = "customerId", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(service.getAll(pageable));
    }
}
