package com.example.server.qa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoQaMemoryServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ListOperations<String, String> listOperations = mock(ListOperations.class);
    private final VideoQaMemoryService memoryService = new VideoQaMemoryService(redisTemplate, new ObjectMapper());

    @Test
    void loadsRecentMessagesFromRedisWindow() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range("video:qa:memory:1:9:conversation-1", 0, -1)).thenReturn(List.of(
                "{\"role\":\"USER\",\"content\":\"评分规则是什么？\"}",
                "{\"role\":\"ASSISTANT\",\"content\":\"作业占 40%。\"}"
        ));

        List<VideoQaMemoryMessage> messages = memoryService.loadRecent(1L, 9L, "conversation-1");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("USER");
        assertThat(messages.get(1).content()).contains("40");
    }

    @Test
    void appendsPairAndKeepsOnlyLatestTenRoundsForOneDay() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        memoryService.appendPair(1L, 9L, "conversation-1", "问题", "回答");

        String key = "video:qa:memory:1:9:conversation-1";
        verify(listOperations).rightPush(key, "{\"role\":\"USER\",\"content\":\"问题\"}");
        verify(listOperations).rightPush(key, "{\"role\":\"ASSISTANT\",\"content\":\"回答\"}");
        verify(listOperations).trim(key, -20, -1);
        verify(redisTemplate).expire(key, Duration.ofHours(24));
    }
}
