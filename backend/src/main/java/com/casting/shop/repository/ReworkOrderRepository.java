package com.casting.shop.repository;

import com.casting.shop.domain.ReworkOrder;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReworkOrderRepository extends JpaRepository<ReworkOrder, Long> {

    List<ReworkOrder> findAllByOrderByIdDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ReworkOrder o where o.id = :id")
    Optional<ReworkOrder> findLockedById(@Param("id") Long id);

    Optional<ReworkOrder> findByInitialInspectionId(Long initialInspectionId);

    Optional<ReworkOrder> findByCastingIdAndStatus(Long castingId, String status);

    boolean existsByReworkNo(String reworkNo);
}
