package com.example.server.dto;

import java.util.List;

/**
 * 固定 Agent 产物结构，避免模型只返回一段无法继续处理的自由文本。
 */
/** AI 分析结论及支撑结论的视频证据。 */
public record AnalysisResult(
        String title,
        List<String> conclusions,
        List<Evidence> evidence,
        List<String> suggestions
) {
    public record Evidence(
            long timestampMs,
            String source,
            String content
    ) {
    }

//    ## 篮球比赛 AI 分析报告
//
//## 核心结论
//- 球队A在第二节进攻效率明显下降
//- 球队B通过快速反击获得更多得分机会
//- 球队A最后5分钟防守出现明显漏洞
//
//## 视频证据
//- [02:05] camera-1：球队A连续三次失误，被球队B完成快攻得分
//- [05:02] camera-2：球队B核心球员完成关键三分
//
//## 建议
//- 加强第二节轮换阵容的进攻组织
//- 增加最后阶段防守沟通训练
    /** 将结构化分析结果格式化为前端可展示的 Markdown。 */
    public String toMarkdown() {
        StringBuilder result = new StringBuilder("## ").append(title).append("\n\n## 核心结论\n");
        if (conclusions != null) {
            conclusions.forEach(item -> result.append("- ").append(item).append('\n'));
        }
        result.append("\n## 视频证据\n");
        if (evidence != null) {
            evidence.forEach(item -> result.append("- [")
                    .append(formatTime(item.timestampMs()))
                    .append("] ")
                    .append(item.source())
                    .append("：")
                    .append(item.content())
                    .append('\n'));
        }
        result.append("\n## 建议\n");
        if (suggestions != null) {
            suggestions.forEach(item -> result.append("- ").append(item).append('\n'));
        }
        return result.toString();
    }

    /** 将毫秒时间戳格式化为视频时间。 */
    private static String formatTime(long timestampMs) {
        long seconds = timestampMs / 1000;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
