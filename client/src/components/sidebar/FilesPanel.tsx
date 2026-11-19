import { useMemo, useState } from "react";
import { Icon } from "../../lib/icons";
import { useConnect } from "../../lib/useConnect";
import { useConnectionStore } from "../../stores/connectionStore";

interface Node {
  name: string;
  dir: boolean;
  children?: Node[];
}

const TREE: Node[] = [
  {
    name: "opt",
    dir: true,
    children: [
      {
        name: "app",
        dir: true,
        children: [
          { name: "dist", dir: true, children: [{ name: "server.js", dir: false }] },
          { name: "src", dir: true, children: [{ name: "server.ts", dir: false }] },
          { name: "package.json", dir: false },
          { name: "ecosystem.config.js", dir: false },
        ],
      },
      { name: "backup", dir: true, children: [{ name: "db-2026-10-28.sql.gz", dir: false }] },
    ],
  },
  {
    name: "var",
    dir: true,
    children: [
      {
        name: "log",
        dir: true,
        children: [
          { name: "nginx", dir: true, children: [{ name: "access.log", dir: false }, { name: "error.log", dir: false }] },
          { name: "auth.log", dir: false },
          { name: "syslog", dir: false },
        ],
      },
      { name: "www", dir: true, children: [{ name: "index.html", dir: false }] },
    ],
  },
  {
    name: "etc",
    dir: true,
    children: [
      { name: "nginx", dir: true, children: [{ name: "nginx.conf", dir: false }] },
      { name: "hosts", dir: false },
      { name: "os-release", dir: false },
    ],
  },
  { name: "home", dir: true, children: [{ name: "deploy", dir: true, children: [{ name: ".ssh", dir: true }, { name: "deploy.sh", dir: false }] }] },
];

function nodeKey(parent: string, node: Node): string {
  return parent ? `${parent}/${node.name}` : node.name;
}

function TreeRow({
  node,
  path,
  depth,
  expanded,
  toggle,
  onFile,
}: {
  node: Node;
  path: string;
  depth: number;
  expanded: Record<string, boolean>;
  toggle: (key: string) => void;
  onFile: (path: string) => void;
}) {
  const key = nodeKey(path, node);
  const open = expanded[key];
  return (
    <>
      <div
        className="tree-node"
        style={{ paddingLeft: 8 + depth * 14 }}
        onClick={() => (node.dir ? toggle(key) : onFile(key))}
        title={key}
      >
        <span className="caret">
          {node.dir ? (
            <Icon name={open ? "chevron-down" : "chevron-right"} size={12} />
          ) : null}
        </span>
        <Icon
          name={node.dir ? "folder" : "file"}
          size={14}
          style={{ color: node.dir ? "var(--accent)" : "var(--text-muted)", flex: "none" }}
        />
        <span className="fname">{node.name}</span>
      </div>
      {node.dir && open &&
        node.children?.map((child) => (
          <TreeRow
            key={nodeKey(key, child)}
            node={child}
            path={key}
            depth={depth + 1}
            expanded={expanded}
            toggle={toggle}
            onFile={onFile}
          />
        ))}
    </>
  );
}

export function FilesPanel() {
  const [expanded, setExpanded] = useState<Record<string, boolean>>({
    opt: true,
    "opt/app": true,
  });
  const activeId = useConnectionStore((s) => s.currentConnectionId);
  const connections = useConnectionStore((s) => s.connections);
  const active = connections.find((s) => s.id === activeId) ?? null;
  const connect = useConnect();

  const filtered = useMemo(() => TREE, []);

  const toggle = (key: string) =>
    setExpanded((prev) => ({ ...prev, [key]: !prev[key] }));

  if (!active) {
    return (
      <div className="empty-state">
        <span className="empty-icon">
          <Icon name="folder-tree" size={20} />
        </span>
        <p>
          未连接服务器。
          <br />
          连接后可以浏览远程目录结构。
        </p>
        {connections[0] && (
          <button className="btn primary sm" onClick={() => connect(connections[0])}>
            <Icon name="play" size={13} />
            连接 {connections[0].name}
          </button>
        )}
      </div>
    );
  }

  return (
    <div className="sidebar-body" style={{ height: "100%" }}>
      <div className="sidebar-section" style={{ paddingBottom: 2 }}>
        {active.host} · 根目录
      </div>
      <div className="tree">
        {filtered.map((node) => (
          <TreeRow
            key={nodeKey("", node)}
            node={node}
            path=""
            depth={0}
            expanded={expanded}
            toggle={toggle}
            onFile={(path) => console.info("open file", path)}
          />
        ))}
      </div>
    </div>
  );
}