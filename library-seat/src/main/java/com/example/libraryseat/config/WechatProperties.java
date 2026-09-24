package com.example.libraryseat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信小程序配置。
 *
 * <p>AppID 可以进仓库（它本来就是公开的），<b>AppSecret 只能通过环境变量
 * {@code WECHAT_APP_SECRET} 注入</b>，不要写进任何文件、更不要出现在前端仓库里。
 * 编码规范 §35 的同类要求：凭证不落代码。
 */
@Data
@Component
@ConfigurationProperties(prefix = "wechat")
public class WechatProperties {

    private String appId = "";

    private String appSecret = "";

    /** code2Session 接口地址，正常情况不用改 */
    private String code2SessionUrl = "https://api.weixin.qq.com/sns/jscode2session";

    public boolean isConfigured() {
        return notBlank(appId) && notBlank(appSecret);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
