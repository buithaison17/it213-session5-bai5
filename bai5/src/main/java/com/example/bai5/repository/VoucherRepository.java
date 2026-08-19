package com.example.bai5.repository;

import com.example.bai5.model.entity.Voucher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VoucherRepository extends JpaRepository<Voucher, Long> {
    List<Voucher> findByCustomerIdAndIsExpired(String customerId, Boolean isExpired);

    Optional<Voucher> findByCode(String code);
}
