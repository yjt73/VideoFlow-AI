package com.example.server.qa.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.qa.api.LibraryQaRequest;
import com.example.server.qa.api.LibraryQaResponse;
import com.example.server.rag.service.RagIndexService;
import com.example.server.rag.service.RagSearchResult;
import com.example.server.rag.service.RagSearchService;
import com.example.server.utils.DeepSeekUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibraryQaServiceTest {

    @Test
    void answersQuestionAcrossSelectedVideosWithMediaCitations() {
        MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
        RagIndexService ragIndexService = mock(RagIndexService.class);
        RagSearchService ragSearchService = mock(RagSearchService.class);
        DeepSeekUtils deepSeekUtils = mock(DeepSeekUtils.class);
        LibraryQaService service = new LibraryQaService(
                mediaFileMapper,
                ragIndexService,
                ragSearchService,
                deepSeekUtils,
                new ObjectMapper()
        );
        MediaFile first = media(11L, 7L, "redis.mp4");
        MediaFile second = media(12L, 7L, "minio.mp4");
        when(mediaFileMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(first, second));
        when(ragSearchService.search(eq(7L), eq(List.of(11L, 12L)), eq("Redis 挂了怎么办"), eq(5)))
                .thenReturn(List.of(
                        new RagSearchResult(1L, 11L, 0, 0L, 5000L, "TRANSCRIPT", "Redis 挂了会暂停上传", 1.2, 0.0, 0.03),
                        new RagSearchResult(2L, 12L, 0, null, null, "SUMMARY", "MinIO 恢复后可继续", 0.8, 0.0, 0.02)
                ));
        when(deepSeekUtils.answerQuestion(eq("Redis 挂了怎么办"), any(String.class), eq("[]")))
                .thenReturn("Redis 短暂不可用时暂停，恢复后继续。");

        LibraryQaResponse response = service.answer(
                7L,
                new LibraryQaRequest(null, "Redis 挂了怎么办", List.of(11L, 12L))
        );

        assertThat(response.answer()).isEqualTo("Redis 短暂不可用时暂停，恢复后继续。");
        assertThat(response.citations()).hasSize(2);
        assertThat(response.citations().get(0).mediaId()).isEqualTo(11L);
        assertThat(response.citations().get(0).filename()).isEqualTo("redis.mp4");
        verify(ragIndexService).ensureIndexed(7L, 11L);
        verify(ragIndexService).ensureIndexed(7L, 12L);
    }

    private MediaFile media(Long id, Long userId, String filename) {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setId(id);
        mediaFile.setUserId(userId);
        mediaFile.setFilename(filename);
        return mediaFile;
    }
}
