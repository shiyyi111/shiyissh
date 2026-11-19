package com.shiyi.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 执行命令请求
 *
 * @author shiyi
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalExecRequestDTO {

    /** 终端会话ID */
    private String sessionId;

    /** 命令内容 */
    private String command;

    /** 可选：操作者用户 ID；传了才校验终端归属，不传则跳过（兼容老前端） */
    private String userId;

}
