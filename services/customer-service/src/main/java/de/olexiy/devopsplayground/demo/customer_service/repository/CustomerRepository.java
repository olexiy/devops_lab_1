package de.olexiy.devopsplayground.demo.customer_service.repository;

import de.olexiy.devopsplayground.demo.customer_service.entity.Customer;
import de.olexiy.devopsplayground.demo.customer_service.entity.CustomerStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<Customer> findByEmailAndIdNot(String email, Long id);

    @Query("""
            SELECT c FROM Customer c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:lastName IS NULL OR LOWER(c.lastName) LIKE LOWER(CONCAT('%', :lastName, '%')))
            """)
    Page<Customer> findByFilters(
            @Param("status") CustomerStatus status,
            @Param("lastName") String lastName,
            Pageable pageable
    );
}
