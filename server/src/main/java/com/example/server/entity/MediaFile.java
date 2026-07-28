package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 媒体文件及其处理状态，对应 media_files 数据表。 */
@Data
@TableName("media_files")
public class MediaFile {

    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 上传该媒体的用户 ID。 */
    private Long userId;          // 核心：记录是谁传的

    /** 用户上传时的原始文件名。 */
    private String filename;
    /** 上传或异步分析任务的当前状态。 */
    private String status;        //UPLOADED, COMPLETED
    /** MinIO 中媒体对象的访问地址。 */
    private String filePath;
    /** 媒体内容的 32 位小写 MD5，用于同一用户内去重。 */
    private String contentHash;

    //下面这几个是新加的
    /** AI 生成的视频分析或摘要。 */
    private String aiSummary;
    /** 视频语音识别得到的完整文本。 */
    private String transcriptText;
    /** 视频封面图片地址。 */
    private String coverUrl;

    //【修改点】删掉了 @TableField(fill = ...) 注解
    //上传时间由数据库自动记录，Java 不插手，防止报错
    /** 媒体上传时间。 */
    private LocalDateTime uploadTime;
}
