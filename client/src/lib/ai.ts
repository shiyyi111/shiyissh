import { SSH_AGENT_ID } from "../api/chat";

export type AgentId = string;

export interface AgentDef {
  id: AgentId;
  name: string;
  model: string;
  hint: string;
}

/**
 * 后端 agent/ssh-agent.yml 只装配了一个 SSH 运维智能体（agent-id 100000），
 * 这里保持同样的口径；结构留成数组，方便后续接更多智能体。
 */
export const AGENTS: AgentDef[] = [
  {
    id: SSH_AGENT_ID,
    name: "SSH 运维助手",
    model: "deepseek-v4.1-flash",
    hint: "可直接在当前终端执行命令",
  },
];

export function findAgent(id: AgentId): AgentDef {
  return AGENTS.find((a) => a.id === id) ?? AGENTS[0];
}

/** 空态页的快捷问句：这些问句会真的触发 AI 在当前终端执行命令 */
export const QUICK_PROMPTS: string[] = [
  "检查一下服务器当前状态",
  "看看磁盘占用和最近的错误日志",
  "列出当前目录下有哪些文件",
];