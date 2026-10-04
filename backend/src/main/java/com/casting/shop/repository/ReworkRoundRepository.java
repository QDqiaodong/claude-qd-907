package com.casting.shop.repository;

import com.casting.shop.domain.ReworkRound;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReworkRoundRepository extends JpaRepository<ReworkRound, Long> {

    List<ReworkRound> findByReworkOrderIdOrderByRoundNoAsc(Long reworkOrderId);

    Optional<ReworkRound> findFirstByReworkOrderIdOrderByRoundNoDesc(Long reworkOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReworkRound r where r.id = :id")
    Optional<ReworkRound> findLockedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReworkRound r where r.reworkOrderId = :orderId order by r.roundNo desc")
    List<ReworkRound> findLockedByOrderId(@Param("orderId") Long orderId);

    long countByStage(String stage);
}
