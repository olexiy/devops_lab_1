package de.olexiy.devopsplayground.demo.transaction_service.repository;

import de.olexiy.devopsplayground.demo.transaction_service.entity.Transaction;
import de.olexiy.devopsplayground.demo.transaction_service.entity.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByReferenceNumber(String referenceNumber);

    @Query("""
            SELECT t FROM Transaction t
            WHERE (:transactionType IS NULL OR t.transactionType = :transactionType)
              AND (:dateFrom IS NULL OR t.transactionDate >= :dateFrom)
              AND (:dateTo IS NULL OR t.transactionDate <= :dateTo)
            """)
    Page<Transaction> findByFilters(
            @Param("transactionType") TransactionType transactionType,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            Pageable pageable
    );

    @Query("""
            SELECT t FROM Transaction t
            WHERE t.accountId = :accountId
              AND (:transactionType IS NULL OR t.transactionType = :transactionType)
              AND (:dateFrom IS NULL OR t.transactionDate >= :dateFrom)
              AND (:dateTo IS NULL OR t.transactionDate <= :dateTo)
            """)
    Page<Transaction> findByAccountIdAndFilters(
            @Param("accountId") Long accountId,
            @Param("transactionType") TransactionType transactionType,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            Pageable pageable
    );

    @Query("""
            SELECT t FROM Transaction t
            WHERE t.customerId = :customerId
              AND (:dateFrom IS NULL OR t.transactionDate >= :dateFrom)
              AND (:dateTo IS NULL OR t.transactionDate <= :dateTo)
            """)
    Page<Transaction> findByCustomerIdAndFilters(
            @Param("customerId") Long customerId,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            Pageable pageable
    );

    @Query("""
            SELECT t FROM Transaction t
            WHERE t.customerId = :customerId
              AND t.transactionDate >= :since
            """)
    List<Transaction> findByCustomerIdSince(
            @Param("customerId") Long customerId,
            @Param("since") LocalDateTime since
    );
}
