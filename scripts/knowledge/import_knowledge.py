#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""将运维知识库 Markdown 源稿解析为 ops_knowledge 导入数据。

用法：
    python import_knowledge.py --format sql --output seed.sql
    python import_knowledge.py --format tsv --output seed.tsv
    python import_knowledge.py --dry-run

约定：
- 只解析 runbook.md / policies.md / incidents.md 中形如 "## KB-001 标题" 的小节；
- 每个小节即一条知识，kind 由编号前缀决定（KB=runbook、P=policy、INC=incident）；
- "命令:" 字段后续的缩进行合并为 commands，行间用 \\n 连接；
- content_hash = sha256(content) 的前 16 位十六进制字符；
- tsv 输出首行为列名，使用 LOAD DATA 时加 IGNORE 1 LINES。

仅依赖 Python 3 标准库，所有读写显式使用 UTF-8。
"""

import argparse
import hashlib
import os
import re
import sys
from datetime import date, datetime
from pathlib import Path

DOC_FILES = (
    ("runbook.md", "runbook"),
    ("policies.md", "policy"),
    ("incidents.md", "incident"),
)

HEADING_RE = re.compile(r"^##\s+((?:KB|P|INC)-\d+)\s+(.+?)\s*$")
FIELD_RE = re.compile(r"^-\s+([^:：]+)[:：]\s*(.*)$")

COLUMNS = (
    "id",
    "doc_id",
    "kind",
    "title",
    "content",
    "commands",
    "tags",
    "severity",
    "valid_from",
    "valid_to",
    "content_hash",
    "source",
    "created_at",
    "updated_at",
)


def read_text(path):
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def parse_sections(text, kind, source_file):
    """把一个 Markdown 文件切成条目列表。"""
    lines = text.splitlines()
    entries = []
    index = 0

    while index < len(lines):
        match = HEADING_RE.match(lines[index])
        if not match:
            index += 1
            continue

        doc_id, title = match.group(1), match.group(2).strip()
        body_lines = []
        index += 1
        while index < len(lines) and not lines[index].startswith("## "):
            body_lines.append(lines[index])
            index += 1

        content = "\n".join(body_lines).strip()
        fields = {}
        commands = []
        collecting_commands = False

        for raw_line in body_lines:
            if collecting_commands:
                if not raw_line.strip():
                    collecting_commands = False
                    continue
                if FIELD_RE.match(raw_line):
                    collecting_commands = False
                elif raw_line.startswith((" ", "\t")):
                    command = raw_line.strip()
                    if command:
                        commands.append(command)
                    continue
                else:
                    collecting_commands = False

            field_match = FIELD_RE.match(raw_line)
            if not field_match:
                continue

            name = field_match.group(1).strip()
            value = field_match.group(2).strip()
            fields[name] = value
            if name == "命令" and not value:
                collecting_commands = True

        tags = fields.get("标签", "")
        tags = ",".join(part.strip() for part in tags.split(",") if part.strip())

        entries.append(
            {
                "doc_id": doc_id,
                "kind": kind,
                "title": title,
                "content": content,
                "commands": "\n".join(commands),
                "tags": tags,
                "severity": fields.get("级别", ""),
                "source": source_file,
            }
        )

    return entries


def load_entries(docs_dir):
    entries = []
    seen = {}
    for filename, kind in DOC_FILES:
        path = docs_dir / filename
        if not path.is_file():
            raise FileNotFoundError("缺少知识文档: %s" % path)
        for entry in parse_sections(read_text(path), kind, filename):
            doc_id = entry["doc_id"]
            if doc_id in seen:
                raise ValueError(
                    "编号重复: %s 同时出现在 %s 和 %s"
                    % (doc_id, seen[doc_id], filename)
                )
            seen[doc_id] = filename
            entries.append(entry)
    return entries


def content_hash(content):
    return hashlib.sha256(content.encode("utf-8")).hexdigest()[:16]


def sql_quote(value):
    if value is None:
        return "NULL"
    text = str(value).replace("\\", "\\\\").replace("'", "''")
    text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n")
    return "'" + text + "'"


def tsv_escape(value):
    if value is None:
        return "\\N"
    return (
        str(value)
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
    )


def build_rows(entries):
    rows = []
    for number, entry in enumerate(entries, start=1):
        digest = content_hash(entry["content"])
        rows.append(
            {
                "id": number,
                "doc_id": entry["doc_id"],
                "kind": entry["kind"],
                "title": entry["title"],
                "content": entry["content"],
                "commands": entry["commands"],
                "tags": entry["tags"],
                "severity": entry["severity"] or None,
                "valid_from": date.today().isoformat(),
                "valid_to": None,
                "content_hash": digest,
                "source": entry["source"],
                "created_at": None,
                "updated_at": None,
            }
        )
    return rows


def render_sql(rows):
    column_list = ", ".join(COLUMNS)
    statements = [
        "-- 由 scripts/knowledge/import_knowledge.py 生成，导入前请确认 ops_knowledge 表已存在。",
        "-- 重复导入前先执行 TRUNCATE TABLE ops_knowledge; 或按 doc_id 做幂等更新。",
    ]
    for row in rows:
        values = [
            str(row["id"]),
            sql_quote(row["doc_id"]),
            sql_quote(row["kind"]),
            sql_quote(row["title"]),
            sql_quote(row["content"]),
            sql_quote(row["commands"]),
            sql_quote(row["tags"]),
            sql_quote(row["severity"]),
            "CURRENT_DATE",
            "NULL",
            sql_quote(row["content_hash"]),
            sql_quote(row["source"]),
            "NOW()",
            "NOW()",
        ]
        statements.append(
            "INSERT INTO ops_knowledge\n  (%s)\nVALUES\n  (%s);"
            % (column_list, ", ".join(values))
        )
    return "\n\n".join(statements) + "\n"


def render_tsv(rows):
    lines = ["\t".join(COLUMNS)]
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    for row in rows:
        values = []
        for column in COLUMNS:
            value = row[column]
            if column in ("created_at", "updated_at") and value is None:
                value = now
            values.append(tsv_escape(value))
        lines.append("\t".join(values))
    return "\n".join(lines) + "\n"


def print_stats(entries):
    by_kind = {}
    missing_commands = {}
    missing_tags = 0
    missing_titles = 0

    for entry in entries:
        kind = entry["kind"]
        by_kind[kind] = by_kind.get(kind, 0) + 1
        if not entry["commands"]:
            missing_commands[kind] = missing_commands.get(kind, 0) + 1
        if not entry["tags"]:
            missing_tags += 1
        if not entry["title"].strip():
            missing_titles += 1

    print("知识库导入预检（dry-run）")
    print("-" * 48)
    for filename, kind in DOC_FILES:
        count = by_kind.get(kind, 0)
        print("%-14s %3d 条" % (filename, count))
    print("-" * 48)
    print("合计: %d 条" % len(entries))
    print(
        "命令块缺失: runbook %d 条（异常数应为 0）；policy %d 条、incident %d 条（按规范无命令块）"
        % (
            missing_commands.get("runbook", 0),
            missing_commands.get("policy", 0),
            missing_commands.get("incident", 0),
        )
    )
    print("标签缺失: %d 条" % missing_tags)
    print("标题缺失: %d 条" % missing_titles)


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):
            pass

    default_docs = Path(__file__).resolve().parents[2] / "docs" / "knowledge-base"
    parser = argparse.ArgumentParser(
        description="解析运维知识库 Markdown，生成 ops_knowledge 导入数据。"
    )
    parser.add_argument(
        "--docs-dir",
        default=str(default_docs),
        help="Markdown 源稿目录，默认是仓库的 docs/knowledge-base",
    )
    parser.add_argument(
        "--format",
        choices=("sql", "tsv"),
        help="输出格式：sql 生成 INSERT，tsv 生成制表符分隔文本",
    )
    parser.add_argument("--output", help="输出文件路径；省略时打印到 stdout")
    parser.add_argument(
        "--dry-run", action="store_true", help="只打印统计，不输出导入数据"
    )
    args = parser.parse_args(argv)

    if not args.dry_run and not args.format:
        parser.error("需要 --format sql 或 --format tsv（或使用 --dry-run）")

    docs_dir = Path(args.docs_dir)
    try:
        entries = load_entries(docs_dir)
    except (FileNotFoundError, ValueError) as error:
        print("解析失败: %s" % error, file=sys.stderr)
        return 1

    if not entries:
        print("解析失败: 没有找到任何条目", file=sys.stderr)
        return 1

    if args.dry_run:
        print_stats(entries)
        return 0

    rows = build_rows(entries)
    text = render_sql(rows) if args.format == "sql" else render_tsv(rows)

    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with open(output_path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
        print(
            "已生成 %s：%d 条（%s）" % (output_path, len(rows), args.format),
            file=sys.stderr,
        )
    else:
        try:
            sys.stdout.write(text)
            sys.stdout.flush()
        except BrokenPipeError:
            # 管道被下游（如 head）提前关闭时静默退出，避免打印异常栈。
            try:
                os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())
            except OSError:
                pass
            return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())