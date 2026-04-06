package de.olexiy.devopsplayground.demo.account_service.repository;

import de.olexiy.devopsplayground.demo.account_service.entity.Account;
import de.olexiy.devopsplayground.demo.account_service.entity.AccountStatus;
import de.olexiy.devopsplayground.demo.account_service.entity.AccountType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountNumber(String accountNumber);

    @Query("""
            SELECT a FROM Account a
            WHERE (:customerId IS NULL OR a.customerId = :customerId)
              AND (:status IS NULL OR a.status = :status)
              AND (:accountType IS NULL OR a.accountType = :accountType)
            """)
    Page<Account> findByFilters(
            @Param("customerId") Long customerId,
            @Param("status") AccountStatus status,
            @Param("accountType") AccountType accountType,
            Pageable pageable
    );

    Page<Account> findByCustomerId(Long customerId, Pageable pageable);

    List<Account> findByCustomerIdAndStatus(Long customerId, AccountStatus status);

    @Query("""
            SELECT AVG(a.balance) FROM Account a
            WHERE a.customerId = :customerId AND a.status = 'ACTIVE'
            """)
    Optional<BigDecimal> findAverageBalanceByCustomerId(@Param("customerId") Long customerId);

    @Query("""
            SELECT COUNT(a) FROM Account a
            WHERE a.customerId = :customerId AND a.status = 'ACTIVE'
            """)
    long countActiveByCustomerId(@Param("customerId") Long customerId);
}
