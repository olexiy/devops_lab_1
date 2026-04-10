package de.olexiy.devopsplayground.demo.rating_service.repository;

import de.olexiy.devopsplayground.demo.rating_service.entity.CustomerRating;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRatingRepository extends JpaRepository<CustomerRating, Long> {
}
