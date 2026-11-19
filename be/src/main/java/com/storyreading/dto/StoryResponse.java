package com.storyreading.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryResponse {
    private Long storyId;
    private Long creatorId;
    private String creatorName;
    private String title;
    private String slug;
    private String author;
    private String description;
    private String coverImage;
    private Integer ageRating;
    // True when the CURRENT viewer (based on login/age) is blocked from reading this story's
    // content. Always computed and returned instead of throwing, so the story still shows up
    // in card lists / search / homepage with its age badge; only the detail/reader UI gates on it.
    private Boolean ageRestricted;
    private String contentType;
    private String status;
    private String rejectionReason;
    private LocalDateTime createdAt;
    private List<CategoryDto> categories;
    private Double averageRating;
    private Long ratingCount;
    private Long followCount;
    private Long viewCount;
}
