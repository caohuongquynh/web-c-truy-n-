package com.storyreading.repository;

import com.storyreading.model.ViewLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ViewLogRepository extends JpaRepository<ViewLog, Long> {
    long countByStoryStoryId(Long storyId);

    @Query("SELECT COUNT(v) FROM ViewLog v WHERE v.story.storyId = :storyId AND v.viewedAt >= :since")
    long countByStoryStoryIdAndViewedAtAfter(@Param("storyId") Long storyId, @Param("since") LocalDateTime since);

    @Query("SELECT v.story.storyId FROM ViewLog v " +
           "WHERE v.viewedAt >= :since AND v.story.status = com.storyreading.model.enums.ContentStatus.PUBLISHED " +
           "GROUP BY v.story.storyId ORDER BY COUNT(v) DESC")
    List<Long> findTrendingStoryIdsSince(@Param("since") LocalDateTime since, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT v.story.storyId) FROM ViewLog v " +
           "WHERE v.viewedAt >= :since AND v.story.status = com.storyreading.model.enums.ContentStatus.PUBLISHED")
    long countTrendingStoriesSince(@Param("since") LocalDateTime since);
}
