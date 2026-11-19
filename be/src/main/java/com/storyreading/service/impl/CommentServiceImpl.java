package com.storyreading.service.impl;

import com.storyreading.dto.CommentRequest;
import com.storyreading.dto.CommentResponse;
import com.storyreading.mapper.CommentMapper;
import com.storyreading.model.Chapter;
import com.storyreading.model.Comment;
import com.storyreading.model.Story;
import com.storyreading.model.User;
import com.storyreading.model.enums.UserRole;
import com.storyreading.repository.ChapterRepository;
import com.storyreading.repository.CommentRepository;
import com.storyreading.repository.StoryRepository;
import com.storyreading.repository.UserRepository;
import com.storyreading.service.CommentService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional
public class CommentServiceImpl implements CommentService {

    // ===== FR07 - Quy tắc ràng buộc chống spam bình luận (mục 2.2.3 báo cáo) =====
    private static final int MIN_CONTENT_LENGTH = 2;
    private static final int MAX_CONTENT_LENGTH = 1000;
    // Khoảng thời gian chờ (cooldown) tối thiểu giữa 2 lần gửi bình luận liên tiếp
    private static final long COOLDOWN_SECONDS = 8;
    // Số lần vi phạm (cooldown/spam link) trong "lượt" trước khi bị khóa bình luận tạm thời
    private static final int MAX_VIOLATIONS_BEFORE_BLOCK = 5;
    private static final long TEMP_BLOCK_MINUTES = 30;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    // Bộ lọc từ ngữ nhạy cảm/thô tục cơ bản - tự động thay bằng ký tự "*"
    private static final List<String> SENSITIVE_WORDS = List.of(
            "địt", "đéo", "đĩ", "lồn", "cặc", "buồi", "cc", "vcl", "vloz", "vl",
            "clgt", "đm", "dm", "đmm", "fuck", "shit", "bitch", "asshole"
    );

    // Chặn bình luận chứa liên kết ngoài (http/https/www.)
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://|www\\.)\\S+", Pattern.CASE_INSENSITIVE);

    private final CommentRepository commentRepository;
    private final ChapterRepository chapterRepository;
    private final StoryRepository storyRepository;
    private final UserRepository userRepository;
    private final CommentMapper commentMapper;

    public CommentServiceImpl(CommentRepository commentRepository,
                              ChapterRepository chapterRepository,
                              StoryRepository storyRepository,
                              UserRepository userRepository,
                              CommentMapper commentMapper) {
        this.commentRepository = commentRepository;
        this.chapterRepository = chapterRepository;
        this.storyRepository = storyRepository;
        this.userRepository = userRepository;
        this.commentMapper = commentMapper;
    }

    @Override
    public CommentResponse createComment(CommentRequest request, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Quy tắc: khóa quyền bình luận tạm thời sau nhiều lần vi phạm liên tiếp
        if (user.getCommentBlockedUntil() != null && user.getCommentBlockedUntil().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException(
                    "Bạn đang bị tạm khóa quyền bình luận do gửi spam nhiều lần. Vui lòng thử lại sau "
                            + user.getCommentBlockedUntil().format(TIME_FMT) + ".");
        }

        Chapter chapter = null;
        Story story = null;

        // Ưu tiên chapterId: nếu bình luận được gửi từ trang chương, frontend luôn gửi
        // kèm cả storyId (đã resolve riêng) lẫn chapterId. Phải kiểm tra chapterId
        // trước, nếu không mọi bình luận ở trang chương sẽ bị lưu nhầm thành bình luận
        // cấp truyện (chapter = null) do storyId luôn khác null trong trường hợp đó.
        if (request.getChapterId() != null) {
            chapter = chapterRepository.findById(request.getChapterId())
                    .orElseThrow(() -> new IllegalArgumentException("Chapter not found"));
        } else if (request.getStoryId() != null) {
            story = storyRepository.findById(request.getStoryId())
                    .orElseThrow(() -> new IllegalArgumentException("Story not found"));
        } else {
            throw new IllegalArgumentException("Either storyId or chapterId must be provided");
        }

        Comment parent = null;
        if (request.getParentId() != null) {
            parent = commentRepository.findById(request.getParentId())
                    .orElseThrow(() -> new IllegalArgumentException("Parent comment not found"));
        }

        // Ràng buộc độ dài: tối thiểu và tối đa
        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("Comment content cannot be empty");
        }
        String trimmedContent = request.getContent().trim();
        if (trimmedContent.length() < MIN_CONTENT_LENGTH) {
            throw new IllegalArgumentException("Bình luận quá ngắn (tối thiểu " + MIN_CONTENT_LENGTH + " ký tự).");
        }
        if (trimmedContent.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("Comment is too long (Max " + MAX_CONTENT_LENGTH + " characters)");
        }

        // Quy tắc cooldown: chặn spam gửi liên tục bằng công cụ tự động
        commentRepository.findTopByUserUserIdOrderByCreatedAtDesc(user.getUserId()).ifPresent(lastComment -> {
            long secondsSinceLast = Duration.between(lastComment.getCreatedAt(), LocalDateTime.now()).getSeconds();
            if (secondsSinceLast < COOLDOWN_SECONDS) {
                registerSpamViolation(user);
                long remaining = COOLDOWN_SECONDS - secondsSinceLast;
                throw new IllegalStateException(
                        "Bạn đang thao tác quá nhanh. Vui lòng chờ " + remaining + " giây trước khi gửi bình luận tiếp theo.");
            }
        });

        // Bộ lọc từ ngữ nhạy cảm: tự động thay thế bằng ký tự mã hóa phù hợp
        String filteredContent = filterSensitiveWords(trimmedContent);

        // Chặn liên kết ngoài: tự động ẩn (chờ Quản trị viên duyệt lại) thay vì hiển thị công khai ngay
        boolean containsExternalLink = URL_PATTERN.matcher(filteredContent).find();
        if (containsExternalLink) {
            registerSpamViolation(user);
        }

        Comment comment = Comment.builder()
                .chapter(chapter)
                .story(story)
                .user(user)
                .parent(parent)
                .content(filteredContent)
                .isHidden(containsExternalLink)
                .build();

        Comment saved = commentRepository.save(comment);

        // Gửi bình luận hợp lệ (không vi phạm) -> reset bộ đếm vi phạm của user
        if (!containsExternalLink && user.getSpamViolationCount() != null && user.getSpamViolationCount() > 0) {
            user.setSpamViolationCount(0);
            userRepository.save(user);
        }

        CommentResponse response = commentMapper.toDto(saved);
        if (containsExternalLink) {
            // Vẫn trả về để FE báo cho người dùng biết, nhưng nội dung thực đã bị ẩn khỏi công khai
            response.setContent(response.getContent() + " (Bình luận chứa liên kết ngoài nên đang chờ Quản trị viên duyệt lại.)");
        }
        return response;
    }

    /**
     * FR07 - Ghi nhận 1 lần vi phạm quy tắc chống spam (cooldown hoặc chèn link ngoài).
     * Đủ ngưỡng vi phạm liên tiếp thì khóa quyền bình luận tạm thời.
     */
    private void registerSpamViolation(User user) {
        int count = user.getSpamViolationCount() == null ? 0 : user.getSpamViolationCount();
        count++;
        if (count >= MAX_VIOLATIONS_BEFORE_BLOCK) {
            user.setCommentBlockedUntil(LocalDateTime.now().plusMinutes(TEMP_BLOCK_MINUTES));
            user.setSpamViolationCount(0);
        } else {
            user.setSpamViolationCount(count);
        }
        userRepository.save(user);
    }

    /**
     * FR07 - Tự động phát hiện và thay thế từ ngữ thô tục/nhạy cảm bằng ký tự "*"
     * trước khi lưu vào cơ sở dữ liệu.
     */
    private String filterSensitiveWords(String content) {
        String result = content;
        for (String word : SENSITIVE_WORDS) {
            Pattern pattern = Pattern.compile(Pattern.quote(word), Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(result);
            StringBuilder sb = new StringBuilder();
            int lastEnd = 0;
            while (matcher.find()) {
                sb.append(result, lastEnd, matcher.start());
                sb.append("*".repeat(matcher.end() - matcher.start()));
                lastEnd = matcher.end();
            }
            sb.append(result.substring(lastEnd));
            result = sb.toString();
        }
        return result;
    }

    @Override
    public void deleteComment(Long commentId, String username) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found"));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Only the owner or Admin can delete
        if (!comment.getUser().getUserId().equals(user.getUserId()) && user.getRole() != UserRole.ADMIN) {
            throw new IllegalStateException("You are not authorized to delete this comment");
        }

        commentRepository.delete(comment);
    }

    @Override
    public void hideComment(Long commentId, String adminUsername) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found"));

        User admin = userRepository.findByUsername(adminUsername)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (admin.getRole() != UserRole.ADMIN) {
            throw new IllegalStateException("Only admins can hide comments");
        }
        comment.setIsHidden(true);
        commentRepository.save(comment);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CommentResponse> getCommentsByChapter(Long chapterId, Pageable pageable) {
        Page<Comment> comments = commentRepository.findByChapterChapterIdAndParentIsNullAndIsHiddenFalseOrderByCreatedAtDesc(chapterId, pageable);
        return comments.map(comment -> {
            CommentResponse resp = commentMapper.toDto(comment);
            // Fetch nested replies if needed, done automatically in mapper if replies collection is populated
            return resp;
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CommentResponse> getCommentsByStory(Long storyId, Pageable pageable) {
        Page<Comment> comments = commentRepository.findByStoryStoryIdAndParentIsNullAndIsHiddenFalseOrderByCreatedAtDesc(storyId, pageable);
        return comments.map(commentMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CommentResponse> getAllComments(Pageable pageable) {
        return commentRepository.findByIsHiddenFalseOrderByCreatedAtDesc(pageable)
                .map(commentMapper::toDto);
    }
}
