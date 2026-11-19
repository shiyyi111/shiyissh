package com.shiyi.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 终端写入请求（原始输入，逐字节发送到 Shell）
 *
 * @author shiyi
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalWriteRequestDTO {

    /** 终端会话ID */
    private String sessionId;

    /** 输入内容（原始按键数据） */
    private String input;

    /** 可选：操作者用户 ID；传了才校验终端归属，不传则跳过（兼容老前端） */
    private String userId;

}
