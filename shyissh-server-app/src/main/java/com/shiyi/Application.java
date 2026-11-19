package com.shiyi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用入口。
 *
 * <p>对话能力的装配分散在几处 {@code @Configuration} 里：</p>
 * <ul>
 *   <li>{@code GraphChatModelConfig} —— 按 yml 里声明的 api-key / base-url / model 装配 {@code ChatModel}；</li>
 *   <li>{@code AiAgentAutoConfig} —— 启动后打印装载了哪些配置表、每张表里有哪些智能体；</li>
 *   <li>{@code ThreadPoolConfig} / {@code GuavaConfig} —— 批量派发的线程池与本地缓存。</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling} 是给 {@code SshConnectionMonitor} 用的：它靠
 * {@code @Scheduled(fixedDelay = 30s)} 定期探活内存里已注册的 SSH 连接，
 * 把"Session 已断但库里还是 CONNECTED"的脏状态纠正过来。没有这个注解，
 * {@code @Scheduled} 只是个不会被调用的普通方法，看日志会以为"监测器在跑"。</p>
 */
@SpringBootApplication
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}