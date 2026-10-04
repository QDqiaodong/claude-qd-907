package com.casting.shop.repository;

import com.casting.shop.domain.ReworkCorrection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReworkCorrectionRepository extends JpaRepository<ReworkCorrection, Long> {

    List<ReworkCorrection> findByReworkRoundIdOrderByIdAsc(Long roundId);
}
