package com.casting.shop.repository;

import com.casting.shop.domain.ReworkDefectSnapshot;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReworkDefectSnapshotRepository extends JpaRepository<ReworkDefectSnapshot, Long> {

    List<ReworkDefectSnapshot> findByReworkRoundIdOrderByIdAsc(Long roundId);
}
