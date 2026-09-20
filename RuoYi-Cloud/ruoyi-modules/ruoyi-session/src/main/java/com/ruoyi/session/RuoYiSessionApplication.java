package com.ruoyi.session;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 会话服务的 M1 启动入口；业务 API、TTS 与消息消费在后续里程碑接入。 */
@SpringBootApplication
public class RuoYiSessionApplication {
    public static void main(String[] args) {
        SpringApplication.run(RuoYiSessionApplication.class, args);
    }
}
