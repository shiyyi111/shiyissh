package com.shiyi.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 打开终端会话请求
 *
 * @author shiyi
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalOpenRequestDTO {

    /** SSH连接ID */
    private String connectionId;

    /** 终端列数 */
    private Integer cols;

    /** 终端行数 */
    private Integer rows;

    /** 可选：操作者用户 ID；传了才校验该连接是否属于此用户，不传则跳过（兼容老前端） */
    private String userId;

}
