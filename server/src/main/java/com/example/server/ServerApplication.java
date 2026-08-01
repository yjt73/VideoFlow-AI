package com.example.server;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 应用启动入口，负责加载 Spring 组件和 MyBatis Mapper。 */
@SpringBootApplication
@EnableScheduling
public class ServerApplication {

	/** 启动嵌入式 Web 服务并初始化 Spring 容器。 */
	public static void main(String[] args) {
		//使用 Builder 模式，强制指定为 SERVLET Web 应用
		//这能彻底防止它找不到 Web 服务器
		new SpringApplicationBuilder(ServerApplication.class)
				.web(WebApplicationType.SERVLET)
				.run(args);
	}
}
