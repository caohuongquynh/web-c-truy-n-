package com.storyreading.service;

import com.storyreading.dto.HistoryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface HistoryService {
    HistoryDto saveReadingHistory(Long storyId, Long chapterId, Integer scrollPosition, Boolean isPrompted, String username);

    Page<HistoryDto> getReadingHistory(String username, Pageable pageable);

    void clearReadingHistory(String username);

    void deleteHistoryItem(Long storyId, String username);

    // FR08 - lấy lịch sử đọc của đúng 1 bộ truyện để hiển thị banner "Đọc tiếp"
    HistoryDto getHistoryForStory(Long storyId, String username);

    // FR08 - đánh dấu đã hiển thị banner "Đọc tiếp" trong phiên truy cập hiện tại
    void markPrompted(Long storyId, String username);
}
