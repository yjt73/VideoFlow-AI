package com.example.server.rag.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rag_segments")
public class RagSegment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long mediaId;

    private Integer segmentIndex;

    private Long startMs;

    private Long endMs;

    private String source;

    private String content;

    private String tokenText;

    private String embeddingJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
