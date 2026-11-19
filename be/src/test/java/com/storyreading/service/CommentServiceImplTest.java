package com.storyreading.service;

import com.storyreading.dto.CommentRequest;
import com.storyreading.dto.CommentResponse;
import com.storyreading.mapper.CommentMapper;
import com.storyreading.model.Chapter;
import com.storyreading.model.Comment;
import com.storyreading.model.User;
import com.storyreading.model.enums.UserRole;
import com.storyreading.repository.ChapterRepository;
import com.storyreading.repository.CommentRepository;
import com.storyreading.repository.StoryRepository;
import com.storyreading.repository.UserRepository;
import com.storyreading.service.impl.CommentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit test cho CommentServiceImpl - kiểm tra riêng logic nghiệp vụ (FR07:
 * chống spam bình luận) mà KHÔNG cần database thật. Mọi repository đều được
 * "giả lập" (mock) bằng Mockito.
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {

    @Mock
    private CommentRepository commentRepository;
    @Mock
    private ChapterRepository chapterRepository;
    @Mock
    private StoryRepository storyRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CommentMapper commentMapper;

    @InjectMocks
    private CommentServiceImpl commentService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .userId(1L)
                .username("reader")
                .role(UserRole.READER)
                .spamViolationCount(0)
                .build();
    }

    @Test
    @DisplayName("Tạo bình luận hợp lệ trên 1 chapter -> lưu thành công")
    void createComment_validChapterComment_success() {
        CommentRequest request = CommentRequest.builder()
                .chapterId(10L)
                .content("Truyện hay quá, cảm ơn tác giả!")
                .build();

        Chapter chapter = Chapter.builder().chapterId(10L).build();
        Comment savedComment = Comment.builder().commentId(100L).content(request.getContent()).build();

        when(userRepository.findByUsername("reader")).thenReturn(Optional.of(user));
        when(chapterRepository.findById(10L)).thenReturn(Optional.of(chapter));
        when(commentRepository.findTopByUserUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.empty());
        when(commentRepository.save(any(Comment.class))).thenReturn(savedComment);
        when(commentMapper.toDto(savedComment)).thenReturn(
                CommentResponse.builder().commentId(100L).content(request.getContent()).build());

        CommentResponse response = commentService.createComment(request, "reader");

        assertThat(response.getCommentId()).isEqualTo(100L);
        verify(commentRepository).save(any(Comment.class));
    }

    @Test
    @DisplayName("Bình luận rỗng -> ném IllegalArgumentException")
    void createComment_emptyContent_throwsException() {
        CommentRequest request = CommentRequest.builder()
                .chapterId(10L)
                .content("   ")
                .build();

        when(userRepository.findByUsername("reader")).thenReturn(Optional.of(user));
        when(chapterRepository.findById(10L)).thenReturn(Optional.of(Chapter.builder().chapterId(10L).build()));

        assertThatThrownBy(() -> commentService.createComment(request, "reader"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be empty");

        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Bình luận quá ngắn (dưới 2 ký tự) -> ném IllegalArgumentException")
    void createComment_tooShortContent_throwsException() {
        CommentRequest request = CommentRequest.builder()
                .chapterId(10L)
                .content("a")
                .build();

        when(userRepository.findByUsername("reader")).thenReturn(Optional.of(user));
        when(chapterRepository.findById(10L)).thenReturn(Optional.of(Chapter.builder().chapterId(10L).build()));

        assertThatThrownBy(() -> commentService.createComment(request, "reader"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quá ngắn");
    }

    @Test
    @DisplayName("Bình luận vượt quá 1000 ký tự -> ném IllegalArgumentException")
    void createComment_tooLongContent_throwsException() {
        String longContent = "a".repeat(1001);
        CommentRequest request = CommentRequest.builder()
                .chapterId(10L)
                .content(longContent)
                .build();

        when(userRepository.findByUsername("reader")).thenReturn(Optional.of(user));
        when(chapterRepository.findById(10L)).thenReturn(Optional.of(Chapter.builder().chapterId(10L).build()));

        assertThatThrownBy(() -> commentService.createComment(request, "reader"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
    }

    @Test
    @DisplayName("Người dùng đang bị khóa bình luận tạm thời -> ném IllegalStateException")
    void createComment_userCurrentlyBlocked_throwsException() {
        user.setCommentBlockedUntil(LocalDateTime.now().plusMinutes(10));

        CommentRequest request = CommentRequest.builder()
                .chapterId(10L)
                .content("Bình luận bình thường")
                .build();

        when(userRepository.findByUsername("reader")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> commentService.createComment(request, "reader"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tạm khóa");

        verify(chapterRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Gửi bình luận quá nhanh (dưới 8 giây từ lần trước) -> chặn và ghi nhận vi phạm")
    void createComment_cooldownViolation_throwsExceptionAndRegistersViolation() {
        CommentRequest request = CommentRequest.builder()
                .chapterId(10L)
                .content("Bình luận thứ hai gửi quá nhanh")
                .build();

        Comment lastComment = Comment.builder()
                .commentId(99L)
                .createdAt(LocalDateTime.now().minusSeconds(2)) // mới gửi 2 giây trước, dưới ngưỡng 8 giây
                .build();

        when(userRepository.findByUsername("reader")).thenReturn(Optional.of(user));
        when(chapterRepository.findById(10L)).thenReturn(Optional.of(Chapter.builder().chapterId(10L).build()));
        when(commentRepository.findTopByUserUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(lastComment));

        assertThatThrownBy(() -> commentService.createComment(request, "reader"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quá nhanh");

        // Vi phạm phải được ghi nhận (tăng spamViolationCount) dù request bị chặn
        verify(userRepository).save(argThat(u -> u.getSpamViolationCount() != null && u.getSpamViolationCount() == 1));
        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Đủ 5 lần vi phạm liên tiếp -> tự động khóa bình luận tạm thời 30 phút")
    void createComment_fifthViolation_triggersTemporaryBlock() {
        user.setSpamViolationCount(4); // đã vi phạm 4 lần trước đó

        CommentRequest request = CommentRequest.builder()
                .chapterId(10L)
                .content("Bình luận vi phạm lần thứ 5")
                .build();

        Comment lastComment = Comment.builder()
                .commentId(99L)
                .createdAt(LocalDateTime.now().minusSeconds(1))
                .build();

        when(userRepository.findByUsername("reader")).thenReturn(Optional.of(user));
        when(chapterRepository.findById(10L)).thenReturn(Optional.of(Chapter.builder().chapterId(10L).build()));
        when(commentRepository.findTopByUserUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(lastComment));

        assertThatThrownBy(() -> commentService.createComment(request, "reader"))
                .isInstanceOf(IllegalStateException.class);

        verify(userRepository).save(argThat(u ->
                u.getCommentBlockedUntil() != null
                        && u.getCommentBlockedUntil().isAfter(LocalDateTime.now())
                        && u.getSpamViolationCount() == 0 // bộ đếm được reset sau khi khóa
        ));
    }

    @Test
    @DisplayName("Bình luận chứa từ nhạy cảm -> tự động thay bằng dấu *")
    void createComment_containsSensitiveWord_getsMasked() {
        CommentRequest request = CommentRequest.builder()
                .chapterId(10L)
                .content("Truyện vl hay quá")
                .build();

        when(userRepository.findByUsername("reader")).thenReturn(Optional.of(user));
        when(chapterRepository.findById(10L)).thenReturn(Optional.of(Chapter.builder().chapterId(10L).build()));
        when(commentRepository.findTopByUserUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.empty());
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentMapper.toDto(any(Comment.class))).thenAnswer(invocation -> {
            Comment c = invocation.getArgument(0);
            return CommentResponse.builder().commentId(1L).content(c.getContent()).build();
        });

        commentService.createComment(request, "reader");

        verify(commentRepository).save(argThat(c ->
                !c.getContent().contains("vl") && c.getContent().contains("**")
        ));
    }

    @Test
    @DisplayName("Bình luận chứa link ngoài -> tự động ẩn (isHidden=true) và ghi nhận vi phạm")
    void createComment_containsExternalLink_getsHiddenAndRegistersViolation() {
        CommentRequest request = CommentRequest.builder()
                .chapterId(10L)
                .content("Xem thêm tại https://spam-site.example")
                .build();

        when(userRepository.findByUsername("reader")).thenReturn(Optional.of(user));
        when(chapterRepository.findById(10L)).thenReturn(Optional.of(Chapter.builder().chapterId(10L).build()));
        when(commentRepository.findTopByUserUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.empty());
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentMapper.toDto(any(Comment.class))).thenAnswer(invocation -> {
            Comment c = invocation.getArgument(0);
            return CommentResponse.builder().commentId(1L).content(c.getContent()).build();
        });

        CommentResponse response = commentService.createComment(request, "reader");

        verify(commentRepository).save(argThat(Comment::getIsHidden));
        verify(userRepository).save(argThat(u -> u.getSpamViolationCount() != null && u.getSpamViolationCount() == 1));
        assertThat(response.getContent()).contains("chờ Quản trị viên duyệt lại");
    }

    @Test
    @DisplayName("Không truyền cả storyId lẫn chapterId -> ném IllegalArgumentException")
    void createComment_missingStoryAndChapterId_throwsException() {
        CommentRequest request = CommentRequest.builder()
                .content("Bình luận không rõ gắn vào đâu")
                .build();

        when(userRepository.findByUsername("reader")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> commentService.createComment(request, "reader"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storyId or chapterId");
    }

    @Test
    @DisplayName("Xóa bình luận không phải của mình và không phải Admin -> bị từ chối")
    void deleteComment_notOwnerNotAdmin_throwsException() {
        User owner = User.builder().userId(2L).username("other-user").role(UserRole.READER).build();
        Comment comment = Comment.builder().commentId(5L).user(owner).build();

        when(commentRepository.findById(5L)).thenReturn(Optional.of(comment));
        when(userRepository.findByUsername("reader")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> commentService.deleteComment(5L, "reader"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not authorized");

        verify(commentRepository, never()).delete(any());
    }
}
