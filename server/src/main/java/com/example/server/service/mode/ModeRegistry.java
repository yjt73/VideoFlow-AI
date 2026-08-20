package com.example.server.service.mode;

import com.example.server.dto.AnalysisMode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class ModeRegistry {

    private final Map<AnalysisMode, ModeProfile> profiles = new EnumMap<>(AnalysisMode.class);

    public ModeRegistry() {
        register(new ModeProfile(AnalysisMode.GENERAL, "通用分析",
                "", "", "", List.of()));
        register(new ModeProfile(AnalysisMode.LEARNING, "学习复习",
                "按知识主题而非时间顺序拆解任务，覆盖核心概念、原理与易混点。",
                "在结论之外，额外产出 outline 知识点大纲、keypoints 重点难点、quiz 自测题、pitfalls 易错点。",
                "额外检查知识点是否成体系、讲解是否有跳步、自测题是否覆盖核心概念。",
                List.of("outline", "keypoints", "quiz", "pitfalls")));
        register(new ModeProfile(AnalysisMode.REVIEW, "内容审查",
                "把目标拆成对每个主要论点的可验证审查项。",
                "在结论之外，额外产出 fallacies 逻辑漏洞、exaggerations 夸大表述、omissions 遗漏点、doubtful 存疑结论。",
                "严格检查论据是否充分、有无偷换概念、结论是否被证据支持；证据不足时必须判定不通过。",
                List.of("fallacies", "exaggerations", "omissions", "doubtful")));
        register(new ModeProfile(AnalysisMode.CREATION, "内容创作",
                "围绕可发布资产拆解：定位爆点、可切片段落与传播钩子。",
                "在结论之外，额外产出 highlights 爆点片段、titles 备选标题、intro 简介文案、script 口播脚本要点。",
                "检查每个爆点是否有真实时间戳支撑，文案是否贴合视频实际内容，不得虚构。",
                List.of("highlights", "titles", "intro", "script")));
        for (AnalysisMode mode : AnalysisMode.values()) {
            if (!profiles.containsKey(mode)) {
                throw new IllegalStateException("AnalysisMode 未注册 ModeProfile: " + mode);
            }
        }
    }

    private void register(ModeProfile profile) {
        profiles.put(profile.mode(), profile);
    }

    public ModeProfile of(AnalysisMode mode) {
        return profiles.getOrDefault(mode, profiles.get(AnalysisMode.GENERAL));
    }
}
