package com.example.server.rag.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface RagSegmentRepository extends BaseMapper<RagSegment> {

    default List<RagSegment> selectByUserAndMediaId(Long userId, Long mediaId) {
        return selectList(new QueryWrapper<RagSegment>()
                .eq("user_id", userId)
                .eq("media_id", mediaId)
                .orderByAsc("segment_index"));
    }

    default List<RagSegment> selectByUserAndMediaIds(Long userId, Collection<Long> mediaIds) {
        QueryWrapper<RagSegment> query = new QueryWrapper<RagSegment>()
                .eq("user_id", userId)
                .orderByAsc("media_id")
                .orderByAsc("segment_index");
        if (mediaIds != null && !mediaIds.isEmpty()) {
            query.in("media_id", mediaIds);
        }
        return selectList(query);
    }

    default boolean existsForMedia(Long userId, Long mediaId) {
        return selectCount(new QueryWrapper<RagSegment>()
                .eq("user_id", userId)
                .eq("media_id", mediaId)) > 0;
    }

    default void deleteByMedia(Long userId, Long mediaId) {
        delete(new QueryWrapper<RagSegment>()
                .eq("user_id", userId)
                .eq("media_id", mediaId));
    }
}
