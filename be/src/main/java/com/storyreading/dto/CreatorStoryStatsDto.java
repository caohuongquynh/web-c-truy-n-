package com.storyreading.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FR11 - Thống kê hiệu suất tương tác cho từng tác phẩm thuộc sở hữu của Creator
 * (mục Bảng 2.2, FR11 trong báo cáo đồ án).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatorStoryStatsDto {
    private Long storyId;
    private String title;
    private String coverImage;
    private String status;
    private Long viewCount;
    private Long followCount;
    private Long commentCount;
    private Double averageRating;
    private Integer chapterCount;
}
