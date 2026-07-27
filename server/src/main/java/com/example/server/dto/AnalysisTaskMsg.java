package com.example.server.dto;

import java.io.Serializable;

//必须实现Serializable接口，否则不能在网络上传输
/** RocketMQ 中传递的视频分析任务消息。 */
public class AnalysisTaskMsg implements Serializable {
    /** 待处理媒体的数据库 ID。 */
    private Long mediaId;
    /** 消费者要执行的动作类型。 */
    private String action; //例如"START_ANALYSIS"
    /** 完整媒体内容哈希，用于任务识别。 */
    private String contentHash;
    /** 用户希望模型重点回答的问题。 */
    private String userGoal;

    /** 序列化框架反序列化消息时使用。 */
    public AnalysisTaskMsg() {}

    /** 创建包含完整任务参数的消息。 */
    public AnalysisTaskMsg(Long mediaId, String action, String contentHash, String userGoal) {
        this.mediaId = mediaId;
        this.action = action;
        this.contentHash = contentHash;
        this.userGoal = userGoal;
    }

    public Long getMediaId() { return mediaId; }
    public void setMediaId(Long mediaId) { this.mediaId = mediaId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getUserGoal() { return userGoal; }
    public void setUserGoal(String userGoal) { this.userGoal = userGoal; }
}
