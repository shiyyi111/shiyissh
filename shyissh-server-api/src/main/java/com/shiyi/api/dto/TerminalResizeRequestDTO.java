package com.shiyi.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 调整终端大小请求
 *
 * @author shiyi
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalResizeRequestDTO {

    /** 终端会话ID */
    private String sessionId;

    /** 新的列数 */
    private Integer cols;

    /** 新的行数 */
    private Integer rows;

    /** 可选：操作者用户 ID；传了才校验终端归属，不传则跳过（兼容老前端） */
    private String userId;

}
