package com.fitlife.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** P3：对话式修订请求。sessionKey 可空（空则服务端新建会话）。 */
@Data
public class RevisePlanRequest {

    @NotBlank(message = "消息内容不能为空")
    private String message;

    /** 会话标识（前端 UUID 或服务端会话 id 字符串），可空 */
    private String sessionKey;

    /** 指定模型（可空，走默认链） */
    private String model;

    /** 允许触发动作（生成/修订），默认 true */
    private Boolean allowAction = true;
}
