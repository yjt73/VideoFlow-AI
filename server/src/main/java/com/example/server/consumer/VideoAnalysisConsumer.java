package com.example.server.consumer;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AiService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** 消费视频分析消息并更新媒体任务状态。 */
@Component
//监听 "video-analysis-topic" 主题，组名随便起
@RocketMQMessageListener(topic = "video-analysis-topic", consumerGroup = "video-group")
public class VideoAnalysisConsumer implements RocketMQListener<AnalysisTaskMsg> {

    @Autowired
    private AiService aiService;

    @Autowired
    private MediaFileMapper mediaFileMapper;

    //注入之前配置好的 IO 密集型线程池
    @Autowired
    private Executor aiTaskExecutor;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    /** 根据消息动作执行分析或转写，并维护任务最终状态。 */
    public void onMessage(AnalysisTaskMsg msg) {
        Long mediaId = msg.getMediaId();
        String contentHash = msg.getContentHash();
        if (contentHash == null || !contentHash.matches("([a-f0-9]{32}|media-\\d+)")) {
            contentHash = "media-" + mediaId;
        }
        String lockKey = "lock:analysis:" + contentHash;
        String activeKey = "analysis:active:" + contentHash;
        System.out.println("⚡ [MQ消费者] 收到任务 ID: " + mediaId + "，准备派发给线程池...");

        //CompletableFuture异步编排
        //即使MQ消费者线程很快，我们也不阻塞它，而是把重活扔给业务线程池
        CompletableFuture.runAsync(() -> {
            System.out.println("🧵 [线程池] 开始执行 DeepSeek 分析逻辑...");
            RLock lock = redissonClient.getLock(lockKey);
            boolean acquired = false;
            try {
                acquired = lock.tryLock(0, -1, TimeUnit.SECONDS);
                if (!acquired) {
                    System.out.println("相同视频正在处理中，跳过重复消息: " + mediaId);
                    return;
                }
                aiService.asyncAnalyze(mediaId, msg.getUserGoal());
            } catch (Exception e) {
                System.err.println("❌ 任务执行失败: " + e.getMessage());
                markAsFailed(mediaId, e.getMessage());
            } finally {
                if (acquired) {
                    redisTemplate.delete(activeKey);
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            }
        }, aiTaskExecutor);
    }

    /** 捕获消费异常后，将对应媒体任务标记为失败。 */
    private void markAsFailed(Long id, String error) {
        MediaFile file = mediaFileMapper.selectById(id);
        if (file != null) {
            file.setAiSummary("❌ 分析失败: " + error);
            mediaFileMapper.updateById(file);
        }
    }
}
