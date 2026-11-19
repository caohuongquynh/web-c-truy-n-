package com.storyreading.service.impl;

import com.storyreading.dto.CreatorDashboardStatsDto;
import com.storyreading.dto.CreatorStoryStatsDto;
import com.storyreading.dto.StoryCardDto;
import com.storyreading.dto.StoryCreateRequest;
import com.storyreading.dto.StoryResponse;
import com.storyreading.dto.StoryUpdateRequest;
import com.storyreading.mapper.StoryMapper;
import com.storyreading.util.SlugUtils;
import com.storyreading.model.Category;
import com.storyreading.model.Story;
import com.storyreading.model.User;
import com.storyreading.model.enums.ContentStatus;
import com.storyreading.model.enums.ContentType;
import com.storyreading.model.enums.UserRole;
import com.storyreading.repository.*;
import com.storyreading.service.StoryService;
import com.storyreading.service.NotificationService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Transactional
public class StoryServiceImpl implements StoryService {

    private final StoryRepository storyRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final RatingRepository ratingRepository;
    private final FollowRepository followRepository;
    private final ViewLogRepository viewLogRepository;
    private final CommentRepository commentRepository;
    private final StoryMapper storyMapper;
    private final NotificationService notificationService;

    public StoryServiceImpl(StoryRepository storyRepository,
                            UserRepository userRepository,
                            CategoryRepository categoryRepository,
                            RatingRepository ratingRepository,
                            FollowRepository followRepository,
                            ViewLogRepository viewLogRepository,
                            CommentRepository commentRepository,
                            StoryMapper storyMapper,
                            NotificationService notificationService) {
        this.storyRepository = storyRepository;
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.ratingRepository = ratingRepository;
        this.followRepository = followRepository;
        this.viewLogRepository = viewLogRepository;
        this.commentRepository = commentRepository;
        this.storyMapper = storyMapper;
        this.notificationService = notificationService;
    }

    private String generateUniqueSlug(String title, Long excludeStoryId) {
        String baseSlug = SlugUtils.toSlug(title);
        if (baseSlug.isEmpty()) {
            baseSlug = "story";
        }
        String slug = baseSlug;
        int count = 1;
        while (true) {
            final String currentSlug = slug;
            java.util.Optional<Story> existing = storyRepository.findBySlug(currentSlug);
            if (existing.isPresent()) {
                if (excludeStoryId != null && existing.get().getStoryId().equals(excludeStoryId)) {
                    break;
                }
                slug = baseSlug + "-" + count;
                count++;
            } else {
                break;
            }
        }
        return slug;
    }

    @Override
    public StoryResponse createStory(StoryCreateRequest request, String username) {
        User creator = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Creator user not found"));

        List<Category> categories = new ArrayList<>();
        if (request.getCategoryIds() != null) {
            categories = categoryRepository.findAllById(request.getCategoryIds());
        }

        Story story = Story.builder()
                .creator(creator)
                .title(request.getTitle())
                .slug(generateUniqueSlug(request.getTitle(), null))
                .author(request.getAuthor() != null ? request.getAuthor() : creator.getUsername())
                .description(request.getDescription())
                .coverImage(request.getCoverImage())
                .ageRating(request.getAgeRating() != null ? request.getAgeRating() : 0)
                .contentType(ContentType.valueOf(request.getContentType().toUpperCase()))
                .status(ContentStatus.PENDING)
                .categories(categories)
                .build();

        Story savedStory = storyRepository.save(story);
        return storyMapper.toDto(savedStory, 0.0, 0L, 0L, 0L);
    }


    @Override
    public StoryResponse updateStory(Long storyId, StoryUpdateRequest request, String username) {
        Story story = storyRepository.findById(storyId)
                .orElseThrow(() -> new IllegalArgumentException("Story not found"));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Only creator or ADMIN/MOD can edit
        if (!story.getCreator().getUserId().equals(user.getUserId()) && user.getRole() != UserRole.ADMIN) {
            throw new IllegalStateException("You are not authorized to update this story");
        }

        if (request.getTitle() != null) {
            if (!request.getTitle().equals(story.getTitle()) || story.getSlug() == null) {
                story.setTitle(request.getTitle());
                story.setSlug(generateUniqueSlug(request.getTitle(), story.getStoryId()));
            }
        } else if (story.getSlug() == null) {
            story.setSlug(generateUniqueSlug(story.getTitle(), story.getStoryId()));
        }

        if (request.getAuthor() != null) {
            story.setAuthor(request.getAuthor());
        }
        if (request.getDescription() != null) {
            story.setDescription(request.getDescription());
        }
        if (request.getCoverImage() != null) {
            story.setCoverImage(request.getCoverImage());
        }
        if (request.getAgeRating() != null) {
            story.setAgeRating(request.getAgeRating());
        }
        if (request.getContentType() != null) {
            story.setContentType(ContentType.valueOf(request.getContentType().toUpperCase()));
        }
        if (request.getCategoryIds() != null) {
            List<Category> categories = categoryRepository.findAllById(request.getCategoryIds());
            story.setCategories(categories);
        }

        // Creator sửa -> phải gửi lại cho Admin duyệt (reset về DRAFT).
        // Admin tự sửa -> không cần duyệt lại, giữ nguyên status hiện tại.
        // Keep the current publication state when metadata is edited. A published
        // story must remain visible on public discovery pages after a cover change.

        Story updatedStory = storyRepository.save(story);
        return getStoryResponse(updatedStory);
    }

    @Override
    public void deleteStory(Long storyId, String username) {
        Story story = storyRepository.findById(storyId)
                .orElseThrow(() -> new IllegalArgumentException("Story not found"));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!story.getCreator().getUserId().equals(user.getUserId()) && user.getRole() != UserRole.ADMIN) {
            throw new IllegalStateException("You are not authorized to delete this story");
        }

        // Check rule: "xóa tác phẩm chưa được xuất bản" -> Only non-published or only draft/rejected can be deleted.
        if (story.getStatus() == ContentStatus.PUBLISHED && user.getRole() != UserRole.ADMIN) {
            throw new IllegalStateException("Cannot delete a published story");
        }

        storyRepository.delete(story);
    }

    @Override
    public StoryResponse approveStory(Long storyId, String adminUsername) {
        Story story = storyRepository.findById(storyId)
                .orElseThrow(() -> new IllegalArgumentException("Story not found"));

        requirePending(story.getStatus(), "Story");
        story.setStatus(ContentStatus.PUBLISHED);
        story.setRejectionReason(null);
        Story saved = storyRepository.save(story);
        notificationService.createNotificationForFollowers(storyId,
                "Truyện đã được duyệt", "Truyện " + saved.getTitle() + " đã được duyệt và xuất bản.",
                "STORY_APPROVED");
        return getStoryResponse(saved);
    }

    @Override
    public StoryResponse rejectStory(Long storyId, String rejectionReason, String adminUsername) {
        Story story = storyRepository.findById(storyId)
                .orElseThrow(() -> new IllegalArgumentException("Story not found"));

        requirePending(story.getStatus(), "Story");
        story.setStatus(ContentStatus.REJECTED);
        story.setRejectionReason(rejectionReason);
        Story saved = storyRepository.save(story);
        return getStoryResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public StoryResponse getStoryById(Long storyId, String username) {
        Story story = storyRepository.findById(storyId)
                .orElseThrow(() -> new IllegalArgumentException("Story not found"));

        StoryResponse response = getStoryResponse(story);
        response.setAgeRestricted(isAgeRestrictedForViewer(story, username));
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public StoryResponse getStoryBySlug(String slug, String username) {
        Story story = storyRepository.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Story not found"));

        StoryResponse response = getStoryResponse(story);
        response.setAgeRestricted(isAgeRestrictedForViewer(story, username));
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StoryCardDto> searchStories(String keyword, Pageable pageable) {
        return storyRepository.searchStories(keyword, ContentStatus.PUBLISHED, pageable)
                .map(this::toStoryCardDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StoryCardDto> latestStories(Pageable pageable) {
        return storyRepository.findLatestStories(ContentStatus.PUBLISHED, pageable)
                .map(this::toStoryCardDto);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "trending", key = "#period + '-' + #pageable.pageNumber + '-' + #pageable.pageSize")
    public Page<StoryCardDto> trendingStories(String period, Pageable pageable) {
        // Simple trending score based on view logs over a period
        LocalDateTime since = LocalDateTime.now();
        if ("day".equalsIgnoreCase(period)) {
            since = since.minusDays(1);
        } else if ("week".equalsIgnoreCase(period)) {
            since = since.minusWeeks(1);
        } else {
            since = since.minusMonths(1);
        }

        final LocalDateTime finalSince = since;

        List<Long> trendingIds = viewLogRepository.findTrendingStoryIdsSince(finalSince, pageable);

        if (trendingIds.isEmpty()) {
            // Chưa có lượt xem nào trong khoảng thời gian này -> fallback về truyện mới nhất
            return storyRepository.findByStatus(ContentStatus.PUBLISHED, pageable).map(this::toStoryCardDto);
        }

        long total = viewLogRepository.countTrendingStoriesSince(finalSince);

        Map<Long, Story> storyMap = storyRepository.findAllById(trendingIds).stream()
                .collect(Collectors.toMap(Story::getStoryId, s -> s));

        List<StoryCardDto> content = trendingIds.stream()
                .map(storyMap::get)
                .filter(Objects::nonNull)
                .map(this::toStoryCardDto)
                .collect(Collectors.toList());

        return new PageImpl<>(content, pageable, total);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StoryCardDto> recommendedStories(String username, Pageable pageable) {
        // Recommendations: "Gợi ý theo Mối quan tâm cá nhân"
        // Return latest stories or categories based on history if username is present.
        return storyRepository.findByStatus(ContentStatus.PUBLISHED, pageable)
                .map(this::toStoryCardDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StoryCardDto> getStoriesByCategory(Long categoryId, Pageable pageable) {
        return storyRepository.findByCategoryIdAndStatus(categoryId, ContentStatus.PUBLISHED, pageable)
                .map(this::toStoryCardDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StoryCardDto> getStoriesByCollection(Long collectionId, Pageable pageable) {
        return storyRepository.findByCollectionIdAndStatus(collectionId, ContentStatus.PUBLISHED, pageable)
                .map(this::toStoryCardDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StoryCardDto> managementStories(String username, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.getRole() == UserRole.ADMIN) {
            // Admin thấy tất cả truyện, mọi trạng thái
            return storyRepository.findAll(pageable).map(this::toStoryCardDto);
        }

        // Creator chỉ thấy truyện của chính mình, mọi trạng thái
        return storyRepository.findByCreatorUserId(user.getUserId(), pageable)
                .map(this::toStoryCardDto);
    }

    @Override
    @Transactional(readOnly = true)
    public CreatorDashboardStatsDto getCreatorDashboardStats(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Admin xem tổng quan toàn hệ thống; Creator chỉ xem tác phẩm của chính mình
        List<Story> myStories = user.getRole() == UserRole.ADMIN
                ? storyRepository.findAll()
                : storyRepository.findByCreatorUserId(user.getUserId());

        List<CreatorStoryStatsDto> storyStats = new ArrayList<>();
        long totalViews = 0;
        long totalFollows = 0;
        long totalComments = 0;

        for (Story story : myStories) {
            long views = viewLogRepository.countByStoryStoryId(story.getStoryId());
            long follows = followRepository.countByStoryStoryId(story.getStoryId());
            long comments = commentRepository.countByStoryIdIncludingChapterComments(story.getStoryId());
            Double avgRating = ratingRepository.getAverageRatingForStory(story.getStoryId());
            int chapterCount = story.getChapters() != null ? story.getChapters().size() : 0;

            totalViews += views;
            totalFollows += follows;
            totalComments += comments;

            storyStats.add(CreatorStoryStatsDto.builder()
                    .storyId(story.getStoryId())
                    .title(story.getTitle())
                    .coverImage(story.getCoverImage())
                    .status(story.getStatus() != null ? story.getStatus().name() : null)
                    .viewCount(views)
                    .followCount(follows)
                    .commentCount(comments)
                    .averageRating(avgRating)
                    .chapterCount(chapterCount)
                    .build());
        }

        // Sắp xếp theo lượt xem giảm dần để nổi bật tác phẩm đang được quan tâm nhất
        storyStats.sort((a, b) -> Long.compare(b.getViewCount(), a.getViewCount()));

        return CreatorDashboardStatsDto.builder()
                .totalStories((long) myStories.size())
                .totalViews(totalViews)
                .totalFollows(totalFollows)
                .totalComments(totalComments)
                .stories(storyStats)
                .build();
    }

    private StoryResponse getStoryResponse(Story story) {
        Double avgRating = ratingRepository.getAverageRatingForStory(story.getStoryId());
        Long follows = followRepository.countByStoryStoryId(story.getStoryId());
        Long views = viewLogRepository.countByStoryStoryId(story.getStoryId());
        Long ratingCount = ratingRepository.countByStoryStoryId(story.getStoryId());
        return storyMapper.toDto(story, avgRating, follows, views, ratingCount);
    }

    /**
     * Whether the current viewer should be blocked from reading this story's content,
     * based on age rating + login/birth date. Never throws — the caller decides what
     * to do with the flag (metadata endpoints keep returning the story either way, so
     * age-restricted stories still show up in every list/search/homepage with their badge;
     * only the detail/reader screens gate on this flag client-side).
     */
    private boolean isAgeRestrictedForViewer(Story story, String username) {
        if (story.getAgeRating() == null || story.getAgeRating() <= 0) {
            return false;
        }
        if (username == null) {
            return true;
        }
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || user.getBirthDate() == null) {
            return true;
        }
        LocalDate birthDate = user.getBirthDate();
        if (birthDate.isAfter(LocalDate.now())) {
            return true;
        }
        int age = Period.between(birthDate, LocalDate.now()).getYears();
        return age < story.getAgeRating();
    }

    private void requirePending(ContentStatus status, String contentName) {
        if (status != ContentStatus.PENDING) {
            throw new IllegalStateException(contentName + " must be PENDING before it can be reviewed");
        }
    }

    private StoryCardDto toStoryCardDto(Story story) {
        Double avgRating = ratingRepository.getAverageRatingForStory(story.getStoryId());
        return storyMapper.toCardDto(story, avgRating);
    }
}
