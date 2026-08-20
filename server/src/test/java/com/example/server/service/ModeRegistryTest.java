package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.service.mode.ModeRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModeRegistryTest {

    @Test
    void registersEveryReferenceMode() {
        ModeRegistry registry = new ModeRegistry();

        assertThat(registry.of(AnalysisMode.GENERAL).requiredSectionKeys()).isEmpty();
        assertThat(registry.of(AnalysisMode.LEARNING).requiredSectionKeys())
                .containsExactly("outline", "keypoints", "quiz", "pitfalls");
        assertThat(registry.of(AnalysisMode.REVIEW).requiredSectionKeys())
                .containsExactly("fallacies", "exaggerations", "omissions", "doubtful");
        assertThat(registry.of(AnalysisMode.CREATION).requiredSectionKeys())
                .containsExactly("highlights", "titles", "intro", "script");
    }

    @Test
    void unknownModeFallsBackToGeneral() {
        assertThat(AnalysisMode.fromNullable("not-a-mode")).isEqualTo(AnalysisMode.GENERAL);
        assertThat(AnalysisMode.fromNullable(null)).isEqualTo(AnalysisMode.GENERAL);
        assertThatThrownBy(() -> AnalysisMode.fromRequest("not-a-mode"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
