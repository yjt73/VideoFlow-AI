package com.example.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 配置 Web 层的跨域访问规则。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {



    //新增全局跨域配置 (解决 Network Error)，放弃本地调用
    @Override
    /** 注册后端接口允许的跨域来源、方法和凭证规则。 */
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                //允许所有前端来源
                .allowedOriginPatterns("*")
                //允许 GET, POST, DELETE 等所有方法
                .allowedMethods("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
