package com.storyreading.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * FR11 - Tổng quan số liệu (Dashboard Creator): tổng lượt xem, lượt theo dõi,
 * lượt bình luận trên toàn bộ tác phẩm của Creator, kèm chi tiết theo từng truyện.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatorDashboardStatsDto {
    private Long totalStories;
    private Long totalViews;
    private Long totalFollows;
    private Long totalComments;
    private List<CreatorStoryStatsDto> stories;
}
