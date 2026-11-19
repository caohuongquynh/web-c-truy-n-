package com.storyreading.repository;

import com.storyreading.model.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {
    Page<Comment> findByChapterChapterIdAndParentIsNullAndIsHiddenFalseOrderByCreatedAtDesc(Long chapterId, Pageable pageable);

    Page<Comment> findByStoryStoryIdAndParentIsNullAndIsHiddenFalseOrderByCreatedAtDesc(Long storyId, Pageable pageable);

    List<Comment> findByParentCommentIdAndIsHiddenFalseOrderByCreatedAtAsc(Long parentId);

    Page<Comment> findByIsHiddenFalseOrderByCreatedAtDesc(Pageable pageable);

    // FR07 - dùng để kiểm tra cooldown giữa 2 lần gửi bình luận liên tiếp của cùng 1 user
    Optional<Comment> findTopByUserUserIdOrderByCreatedAtDesc(Long userId);

    // FR11 - đếm tổng số bình luận (cả cấp Story lẫn cấp Chapter) của 1 tác phẩm, phục vụ Dashboard Creator
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(c) FROM Comment c WHERE c.isHidden = false AND (" +
                    "(c.story IS NOT NULL AND c.story.storyId = :storyId) OR " +
                    "(c.chapter IS NOT NULL AND c.chapter.story.storyId = :storyId))")
    long countByStoryIdIncludingChapterComments(@org.springframework.data.repository.query.Param("storyId") Long storyId);
}
