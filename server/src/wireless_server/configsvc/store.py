"""SQLite 持久化（stdlib sqlite3，WAL 模式）。

全库 schema 集中在此创建，其他模块（网关/API/文件/测试报告）后续复用同一连接。
所有阻塞调用经 asyncio.to_thread 包装为 async 接口；单连接串行访问，无需锁。
"""

from __future__ import annotations

import asyncio
import json
import sqlite3
from pathlib import Path
from typing import Any

_SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
    mac          TEXT PRIMARY KEY,
    device_id    TEXT NOT NULL,
    type         TEXT NOT NULL DEFAULT '',
    priority     INTEGER NOT NULL DEFAULT 0,
    persistent   INTEGER NOT NULL DEFAULT 0,
    transfer_channel TEXT NOT NULL DEFAULT 'ble',
    profile_json TEXT NOT NULL DEFAULT '{}',
    fields_json  TEXT NOT NULL DEFAULT '{}',
    polling_json TEXT NOT NULL DEFAULT '{}',
    rules_json   TEXT NOT NULL DEFAULT '[]'
);
CREATE TABLE IF NOT EXISTS global_params (
    id   INTEGER PRIMARY KEY CHECK (id = 1),
    json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS config_versions (
    agent_id TEXT PRIMARY KEY,
    version  INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS agents (
    agent_id      TEXT PRIMARY KEY,
    registered_ts INTEGER NOT NULL,
    info_json     TEXT NOT NULL DEFAULT '{}'
);
CREATE TABLE IF NOT EXISTS events (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    agent_id     TEXT NOT NULL,
    type         TEXT NOT NULL,
    ts           INTEGER NOT NULL,
    payload_json TEXT NOT NULL DEFAULT '{}'
);
CREATE TABLE IF NOT EXISTS files (
    file_id    TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    size       INTEGER NOT NULL,
    sha256     TEXT NOT NULL,
    path       TEXT NOT NULL,
    created_ts INTEGER NOT NULL,
    origin     TEXT NOT NULL DEFAULT 'upload',
    meta_json  TEXT NOT NULL DEFAULT '{}'
);
CREATE TABLE IF NOT EXISTS test_runs (
    run_id      TEXT PRIMARY KEY,
    scenario    TEXT NOT NULL,
    started_ts  INTEGER NOT NULL,
    finished_ts INTEGER,
    status      TEXT NOT NULL,
    report_json TEXT
);
CREATE TABLE IF NOT EXISTS test_results (
    run_id     TEXT NOT NULL,
    step_idx   INTEGER NOT NULL,
    name       TEXT NOT NULL,
    status     TEXT NOT NULL,
    detail     TEXT,
    elapsed_ms INTEGER,
    PRIMARY KEY (run_id, step_idx)
);
"""


class Store:
    """服务器 SQLite 存储。init() 后使用，close() 释放。"""

    def __init__(self, db_path: str | Path = ":memory:"):
        self._db_path = str(db_path)
        self._conn: sqlite3.Connection | None = None

    async def init(self) -> None:
        """打开连接（WAL 模式）并建表。"""
        await asyncio.to_thread(self._open)

    def _open(self) -> None:
        if self._db_path != ":memory:":
            Path(self._db_path).parent.mkdir(parents=True, exist_ok=True)
        # check_same_thread=False：所有调用经 asyncio.to_thread 分发到线程池，
        # 工作线程不固定；底层 SQLite 为 serialized 模式，单连接跨线程安全。
        conn = sqlite3.connect(self._db_path, check_same_thread=False)
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        conn.executescript(_SCHEMA)
        # 存量库迁移：config_versions 增加内容哈希列（配置未变化时复用版本号）。
        cols = {r[1] for r in conn.execute("PRAGMA table_info(config_versions)")}
        if "content_hash" not in cols:
            conn.execute("ALTER TABLE config_versions ADD COLUMN content_hash TEXT")
        # 存量库迁移：devices 增加传输通道列（SPP 加速通道，缺省 ble）。
        cols = {r[1] for r in conn.execute("PRAGMA table_info(devices)")}
        if "transfer_channel" not in cols:
            conn.execute(
                "ALTER TABLE devices ADD COLUMN transfer_channel TEXT NOT NULL DEFAULT 'ble'"
            )
        # 存量库迁移：files 增加来源列（设备导出 origin='device'）与元信息列。
        cols = {r[1] for r in conn.execute("PRAGMA table_info(files)")}
        if "origin" not in cols:
            conn.execute(
                "ALTER TABLE files ADD COLUMN origin TEXT NOT NULL DEFAULT 'upload'"
            )
        if "meta_json" not in cols:
            conn.execute(
                "ALTER TABLE files ADD COLUMN meta_json TEXT NOT NULL DEFAULT '{}'"
            )
        conn.commit()
        self._conn = conn

    def _require_conn(self) -> sqlite3.Connection:
        if self._conn is None:
            raise RuntimeError("Store 未初始化，请先 await init()")
        return self._conn

    async def close(self) -> None:
        conn, self._conn = self._conn, None
        if conn is not None:
            await asyncio.to_thread(conn.close)

    # ---------------- 设备 CRUD ----------------

    async def upsert_device(self, device: dict[str, Any]) -> None:
        """按 §16.4 devices[] 结构整项写入（存在即替换）。"""
        await asyncio.to_thread(self._upsert_device, device)

    def _upsert_device(self, device: dict[str, Any]) -> None:
        conn = self._require_conn()
        conn.execute(
            """
            INSERT INTO devices (mac, device_id, type, priority, persistent,
                                 transfer_channel,
                                 profile_json, fields_json, polling_json, rules_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(mac) DO UPDATE SET
                device_id=excluded.device_id, type=excluded.type,
                priority=excluded.priority, persistent=excluded.persistent,
                transfer_channel=excluded.transfer_channel,
                profile_json=excluded.profile_json, fields_json=excluded.fields_json,
                polling_json=excluded.polling_json, rules_json=excluded.rules_json
            """,
            (
                device["mac"],
                device["deviceId"],
                device.get("type", ""),
                int(device.get("priority", 0)),
                1 if device.get("persistent") else 0,
                device.get("transferChannel", "ble"),
                json.dumps(device.get("profile", {}), ensure_ascii=False),
                json.dumps(device.get("fields", {}), ensure_ascii=False),
                json.dumps(device.get("polling", {}), ensure_ascii=False),
                json.dumps(device.get("rules", []), ensure_ascii=False),
            ),
        )
        conn.commit()

    @staticmethod
    def _row_to_device(row: sqlite3.Row | tuple) -> dict[str, Any]:
        (mac, device_id, dtype, priority, persistent, transfer_channel,
         profile, fields, polling, rules) = row
        return {
            "deviceId": device_id,
            "mac": mac,
            "type": dtype,
            "priority": priority,
            "persistent": bool(persistent),
            "transferChannel": transfer_channel,
            "profile": json.loads(profile),
            "fields": json.loads(fields),
            "polling": json.loads(polling),
            "rules": json.loads(rules),
        }

    async def get_device(self, mac: str) -> dict[str, Any] | None:
        return await asyncio.to_thread(self._get_device, mac)

    def _get_device(self, mac: str) -> dict[str, Any] | None:
        row = self._require_conn().execute(
            "SELECT mac, device_id, type, priority, persistent, transfer_channel,"
            " profile_json, fields_json, polling_json, rules_json"
            " FROM devices WHERE mac = ?",
            (mac,),
        ).fetchone()
        return self._row_to_device(row) if row else None

    async def list_devices(self) -> list[dict[str, Any]]:
        return await asyncio.to_thread(self._list_devices)

    def _list_devices(self) -> list[dict[str, Any]]:
        rows = self._require_conn().execute(
            "SELECT mac, device_id, type, priority, persistent, transfer_channel,"
            " profile_json, fields_json, polling_json, rules_json"
            " FROM devices ORDER BY mac"
        ).fetchall()
        return [self._row_to_device(r) for r in rows]

    async def delete_device(self, mac: str) -> bool:
        """返回是否确有删除。"""
        return await asyncio.to_thread(self._delete_device, mac)

    def _delete_device(self, mac: str) -> bool:
        conn = self._require_conn()
        cur = conn.execute("DELETE FROM devices WHERE mac = ?", (mac,))
        conn.commit()
        return cur.rowcount > 0

    # ---------------- 全局参数（§16.4 顶层旋钮） ----------------

    async def get_global_params(self) -> dict[str, Any]:
        """无记录返回 {}。"""
        return await asyncio.to_thread(self._get_global_params)

    def _get_global_params(self) -> dict[str, Any]:
        row = self._require_conn().execute(
            "SELECT json FROM global_params WHERE id = 1"
        ).fetchone()
        return json.loads(row[0]) if row else {}

    async def set_global_params(self, params: dict[str, Any]) -> None:
        await asyncio.to_thread(self._set_global_params, params)

    def _set_global_params(self, params: dict[str, Any]) -> None:
        conn = self._require_conn()
        conn.execute(
            "INSERT INTO global_params (id, json) VALUES (1, ?)"
            " ON CONFLICT(id) DO UPDATE SET json=excluded.json",
            (json.dumps(params, ensure_ascii=False),),
        )
        conn.commit()

    # ---------------- 配置版本（§16.4 configVersion 单调递增） ----------------

    async def next_config_version(self, agent_id: str, content_hash: str | None = None) -> int:
        """原子自增并返回新版本号，首值为 1；同时记录配置内容哈希。"""
        return await asyncio.to_thread(self._next_config_version, agent_id, content_hash)

    def _next_config_version(self, agent_id: str, content_hash: str | None = None) -> int:
        conn = self._require_conn()
        conn.execute("BEGIN IMMEDIATE")
        try:
            conn.execute(
                "INSERT INTO config_versions (agent_id, version, content_hash) VALUES (?, 1, ?)"
                " ON CONFLICT(agent_id) DO UPDATE SET version = version + 1,"
                " content_hash = excluded.content_hash",
                (agent_id, content_hash),
            )
            version = conn.execute(
                "SELECT version FROM config_versions WHERE agent_id = ?", (agent_id,)
            ).fetchone()[0]
            conn.commit()
        except BaseException:
            conn.rollback()
            raise
        return int(version)

    async def config_fingerprint(self, agent_id: str) -> tuple[str | None, int]:
        """(上次配置内容哈希, 当前版本号)；无记录返回 (None, 0)。"""
        return await asyncio.to_thread(self._config_fingerprint, agent_id)

    def _config_fingerprint(self, agent_id: str) -> tuple[str | None, int]:
        row = self._require_conn().execute(
            "SELECT content_hash, version FROM config_versions WHERE agent_id = ?", (agent_id,)
        ).fetchone()
        if row is None:
            return None, 0
        return row[0], int(row[1])

    async def current_config_version(self, agent_id: str) -> int:
        """无记录返回 0。"""
        return await asyncio.to_thread(self._current_config_version, agent_id)

    def _current_config_version(self, agent_id: str) -> int:
        row = self._require_conn().execute(
            "SELECT version FROM config_versions WHERE agent_id = ?", (agent_id,)
        ).fetchone()
        return int(row[0]) if row else 0

    # ---------------- Agent 注册档案 ----------------

    async def upsert_agent(self, agent_id: str, info: dict[str, Any]) -> None:
        """REGISTER 成功时登记/更新 agent 档案（registered_ts 为墙钟毫秒）。"""
        await asyncio.to_thread(self._upsert_agent, agent_id, info)

    def _upsert_agent(self, agent_id: str, info: dict[str, Any]) -> None:
        import time

        conn = self._require_conn()
        conn.execute(
            "INSERT INTO agents (agent_id, registered_ts, info_json) VALUES (?, ?, ?)"
            " ON CONFLICT(agent_id) DO UPDATE SET"
            " registered_ts=excluded.registered_ts, info_json=excluded.info_json",
            (agent_id, int(time.time() * 1000),
             json.dumps(info, ensure_ascii=False)),
        )
        conn.commit()

    async def get_agent(self, agent_id: str) -> dict[str, Any] | None:
        return await asyncio.to_thread(self._get_agent, agent_id)

    def _get_agent(self, agent_id: str) -> dict[str, Any] | None:
        row = self._require_conn().execute(
            "SELECT agent_id, registered_ts, info_json FROM agents WHERE agent_id = ?",
            (agent_id,),
        ).fetchone()
        if not row:
            return None
        return {"agentId": row[0], "registeredTs": row[1], "info": json.loads(row[2])}

    # ---------------- 文件登记（§7.6 待下发文件台账） ----------------

    async def register_file(self, file_id: str, name: str, size: int,
                            sha256_b64: str, path: str,
                            origin: str = "upload",
                            meta: dict[str, Any] | None = None) -> None:
        """登记文件（sha256_b64 = base64 的 32 字节整体哈希，§16.3）。

        origin：'upload' = web 上传待下发；'device' = 设备导出（FILE_EXPORT 回传）。
        meta：来源元信息（如 exportId/deviceMac/agentId）。
        """
        await asyncio.to_thread(
            self._register_file, file_id, name, size, sha256_b64, path, origin, meta)

    def _register_file(self, file_id: str, name: str, size: int,
                       sha256_b64: str, path: str, origin: str,
                       meta: dict[str, Any] | None) -> None:
        import time

        conn = self._require_conn()
        conn.execute(
            "INSERT INTO files (file_id, name, size, sha256, path, created_ts,"
            " origin, meta_json)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            " ON CONFLICT(file_id) DO UPDATE SET"
            " name=excluded.name, size=excluded.size, sha256=excluded.sha256,"
            " path=excluded.path, created_ts=excluded.created_ts,"
            " origin=excluded.origin, meta_json=excluded.meta_json",
            (file_id, name, size, sha256_b64, path, int(time.time() * 1000),
             origin, json.dumps(meta or {}, ensure_ascii=False)),
        )
        conn.commit()

    @staticmethod
    def _row_to_file(row: sqlite3.Row | tuple) -> dict[str, Any]:
        return {
            "fileId": row[0], "name": row[1], "size": row[2],
            "sha256": row[3], "path": row[4], "createdTs": row[5],
            "origin": row[6], "meta": json.loads(row[7]),
        }

    async def get_file(self, file_id: str) -> dict[str, Any] | None:
        return await asyncio.to_thread(self._get_file, file_id)

    def _get_file(self, file_id: str) -> dict[str, Any] | None:
        row = self._require_conn().execute(
            "SELECT file_id, name, size, sha256, path, created_ts, origin, meta_json"
            " FROM files WHERE file_id = ?",
            (file_id,),
        ).fetchone()
        return self._row_to_file(row) if row else None

    async def list_files(self) -> list[dict[str, Any]]:
        return await asyncio.to_thread(self._list_files)

    def _list_files(self) -> list[dict[str, Any]]:
        rows = self._require_conn().execute(
            "SELECT file_id, name, size, sha256, path, created_ts, origin, meta_json"
            " FROM files ORDER BY created_ts"
        ).fetchall()
        return [self._row_to_file(r) for r in rows]

    async def delete_file(self, file_id: str) -> bool:
        """返回是否确有删除。"""
        return await asyncio.to_thread(self._delete_file, file_id)

    def _delete_file(self, file_id: str) -> bool:
        conn = self._require_conn()
        cur = conn.execute("DELETE FROM files WHERE file_id = ?", (file_id,))
        conn.commit()
        return cur.rowcount > 0

    # ---------------- 事件流水（A.3 全量入库，供排障与报告回放） ----------------

    async def insert_event(self, agent_id: str, type: str, ts: int,
                           payload: dict[str, Any]) -> None:
        await asyncio.to_thread(self._insert_event, agent_id, type, ts, payload)

    def _insert_event(self, agent_id: str, type: str, ts: int,
                      payload: dict[str, Any]) -> None:
        conn = self._require_conn()
        conn.execute(
            "INSERT INTO events (agent_id, type, ts, payload_json) VALUES (?, ?, ?, ?)",
            (agent_id, type, ts, json.dumps(payload, ensure_ascii=False)),
        )
        conn.commit()

    async def query_events(self, agent_id: str | None = None,
                           type: str | None = None,
                           limit: int = 200) -> list[dict[str, Any]]:
        """按 id 倒序取新；agent_id / type 可选过滤。"""
        return await asyncio.to_thread(self._query_events, agent_id, type, limit)

    def _query_events(self, agent_id: str | None, type: str | None,
                      limit: int) -> list[dict[str, Any]]:
        sql = "SELECT id, agent_id, type, ts, payload_json FROM events"
        conds: list[str] = []
        params: list[Any] = []
        if agent_id is not None:
            conds.append("agent_id = ?")
            params.append(agent_id)
        if type is not None:
            conds.append("type = ?")
            params.append(type)
        if conds:
            sql += " WHERE " + " AND ".join(conds)
        sql += " ORDER BY id DESC LIMIT ?"
        params.append(limit)
        rows = self._require_conn().execute(sql, params).fetchall()
        return [
            {"id": r[0], "agentId": r[1], "type": r[2], "ts": r[3],
             "payload": json.loads(r[4])}
            for r in rows
        ]

    # ---------------- 测试编排报告（§14，engine 落库） ----------------

    async def insert_test_run(self, run_id: str, scenario: str,
                              started_ts: int) -> None:
        await asyncio.to_thread(self._insert_test_run, run_id, scenario,
                                started_ts)

    def _insert_test_run(self, run_id: str, scenario: str,
                         started_ts: int) -> None:
        conn = self._require_conn()
        conn.execute(
            "INSERT INTO test_runs (run_id, scenario, started_ts, status)"
            " VALUES (?, ?, ?, 'RUNNING')",
            (run_id, scenario, started_ts),
        )
        conn.commit()

    async def finish_test_run(self, run_id: str, finished_ts: int,
                              status: str, report: dict[str, Any]) -> None:
        await asyncio.to_thread(self._finish_test_run, run_id, finished_ts,
                                status, report)

    def _finish_test_run(self, run_id: str, finished_ts: int, status: str,
                         report: dict[str, Any]) -> None:
        conn = self._require_conn()
        conn.execute(
            "UPDATE test_runs SET finished_ts = ?, status = ?, report_json = ?"
            " WHERE run_id = ?",
            (finished_ts, status,
             json.dumps(report, ensure_ascii=False), run_id),
        )
        conn.commit()

    async def insert_test_result(self, run_id: str, step_idx: int, name: str,
                                 status: str, detail: str | None,
                                 elapsed_ms: int | None) -> None:
        await asyncio.to_thread(self._insert_test_result, run_id, step_idx,
                                name, status, detail, elapsed_ms)

    def _insert_test_result(self, run_id: str, step_idx: int, name: str,
                            status: str, detail: str | None,
                            elapsed_ms: int | None) -> None:
        conn = self._require_conn()
        conn.execute(
            "INSERT OR REPLACE INTO test_results"
            " (run_id, step_idx, name, status, detail, elapsed_ms)"
            " VALUES (?, ?, ?, ?, ?, ?)",
            (run_id, step_idx, name, status, detail, elapsed_ms),
        )
        conn.commit()

    async def list_test_runs(self, limit: int = 50) -> list[dict[str, Any]]:
        """按开始时间倒序取新。"""
        return await asyncio.to_thread(self._list_test_runs, limit)

    def _list_test_runs(self, limit: int) -> list[dict[str, Any]]:
        rows = self._require_conn().execute(
            "SELECT run_id, scenario, started_ts, finished_ts, status"
            " FROM test_runs ORDER BY started_ts DESC LIMIT ?",
            (limit,),
        ).fetchall()
        return [
            {"runId": r[0], "scenario": r[1], "startedTs": r[2],
             "finishedTs": r[3], "status": r[4]}
            for r in rows
        ]

    async def get_test_run(self, run_id: str) -> dict[str, Any] | None:
        """单条 run（含 report 与逐步 results）；无记录返回 None。"""
        return await asyncio.to_thread(self._get_test_run, run_id)

    def _get_test_run(self, run_id: str) -> dict[str, Any] | None:
        conn = self._require_conn()
        row = conn.execute(
            "SELECT run_id, scenario, started_ts, finished_ts, status, report_json"
            " FROM test_runs WHERE run_id = ?",
            (run_id,),
        ).fetchone()
        if not row:
            return None
        results = conn.execute(
            "SELECT step_idx, name, status, detail, elapsed_ms"
            " FROM test_results WHERE run_id = ? ORDER BY step_idx",
            (run_id,),
        ).fetchall()
        return {
            "runId": row[0], "scenario": row[1], "startedTs": row[2],
            "finishedTs": row[3], "status": row[4],
            "report": json.loads(row[5]) if row[5] else None,
            "results": [
                {"stepIdx": r[0], "name": r[1], "status": r[2],
                 "detail": r[3], "elapsedMs": r[4]}
                for r in results
            ],
        }
