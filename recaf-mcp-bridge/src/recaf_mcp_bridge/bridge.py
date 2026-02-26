"""Stdio-to-HTTP MCP bridge for Recaf MCP Server."""

import asyncio
import sys
import time
from typing import Any

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.client.streamable_http import streamablehttp_client
from mcp import ClientSession
from mcp.types import (
    Tool,
    Resource,
    TextContent,
    ImageContent,
    EmbeddedResource,
)


class RecafMcpBridge:
    """MCP Server that bridges stdio to Recaf's Streamable HTTP endpoint."""

    def __init__(self, host: str = "localhost", port: int = 8085):
        self.port = port
        self.host = host
        self.url = f"http://{host}:{port}/mcp"
        self.server = Server("recaf-mcp-bridge")
        self.backend: ClientSession | None = None
        self._metadata_cache_ttl_seconds = 30.0
        self._tools_cache: list[Tool] | None = None
        self._tools_cache_at = 0.0
        self._resources_cache: list[Resource] | None = None
        self._resources_cache_at = 0.0
        self._register_handlers()

    def _cache_valid(self, cache_at: float) -> bool:
        return cache_at > 0 and (time.monotonic() - cache_at) < self._metadata_cache_ttl_seconds

    def _clear_metadata_cache(self):
        self._tools_cache = None
        self._tools_cache_at = 0.0
        self._resources_cache = None
        self._resources_cache_at = 0.0

    def _set_backend(self, backend: ClientSession | None):
        if backend is not self.backend:
            self._clear_metadata_cache()
        self.backend = backend

    async def _list_tools(self) -> list[Tool]:
        if not self.backend:
            raise RuntimeError("Backend not connected")
        if self._tools_cache is not None and self._cache_valid(self._tools_cache_at):
            return self._tools_cache
        result = await self.backend.list_tools()
        self._tools_cache = result.tools
        self._tools_cache_at = time.monotonic()
        return result.tools

    async def _list_resources(self) -> list[Resource]:
        if not self.backend:
            raise RuntimeError("Backend not connected")
        if self._resources_cache is not None and self._cache_valid(self._resources_cache_at):
            return self._resources_cache
        result = await self.backend.list_resources()
        self._resources_cache = result.resources
        self._resources_cache_at = time.monotonic()
        return result.resources

    def _register_handlers(self):
        @self.server.list_tools()
        async def list_tools() -> list[Tool]:
            return await self._list_tools()

        @self.server.call_tool()
        async def call_tool(
            name: str, arguments: dict[str, Any]
        ) -> list[TextContent | ImageContent | EmbeddedResource]:
            if not self.backend:
                raise RuntimeError("Backend not connected")
            result = await self.backend.call_tool(name, arguments)
            return result.content

        @self.server.list_resources()
        async def list_resources() -> list[Resource]:
            return await self._list_resources()

        @self.server.read_resource()
        async def read_resource(uri: str) -> str | bytes:
            if not self.backend:
                raise RuntimeError("Backend not connected")
            result = await self.backend.read_resource(uri)
            if result.contents and len(result.contents) > 0:
                content = result.contents[0]
                if hasattr(content, "text") and content.text:
                    return content.text
                if hasattr(content, "blob") and content.blob:
                    return content.blob
            return ""

    async def run(self):
        print(f"Connecting to Recaf MCP at {self.url}...", file=sys.stderr)
        async with streamablehttp_client(
            self.url, timeout=300.0
        ) as (read_stream, write_stream, _):
            async with ClientSession(read_stream, write_stream) as session:
                self._set_backend(session)
                try:
                    init = await session.initialize()
                    print(
                        f"Connected to {init.serverInfo.name} v{init.serverInfo.version}",
                        file=sys.stderr,
                    )
                    async with stdio_server() as (read_s, write_s):
                        await self.server.run(
                            read_s, write_s, self.server.create_initialization_options()
                        )
                finally:
                    self._set_backend(None)


def main():
    import argparse

    parser = argparse.ArgumentParser(description="Recaf MCP stdio bridge")
    parser.add_argument("--host", type=str, default="localhost", help="Recaf MCP host")
    parser.add_argument("--port", type=int, default=8085, help="Recaf MCP port")
    args = parser.parse_args()

    bridge = RecafMcpBridge(host=args.host, port=args.port)
    asyncio.run(bridge.run())


if __name__ == "__main__":
    main()
