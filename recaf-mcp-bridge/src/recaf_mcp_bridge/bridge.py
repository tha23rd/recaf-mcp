"""Stdio-to-HTTP MCP bridge for Recaf MCP Server."""

import asyncio
import sys
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

    def __init__(self, port: int = 8085):
        self.port = port
        self.url = f"http://localhost:{port}/mcp"
        self.server = Server("recaf-mcp-bridge")
        self.backend: ClientSession | None = None
        self._register_handlers()

    def _register_handlers(self):
        @self.server.list_tools()
        async def list_tools() -> list[Tool]:
            if not self.backend:
                raise RuntimeError("Backend not connected")
            result = await self.backend.list_tools()
            return result.tools

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
            if not self.backend:
                raise RuntimeError("Backend not connected")
            result = await self.backend.list_resources()
            return result.resources

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
                self.backend = session
                init = await session.initialize()
                print(
                    f"Connected to {init.serverInfo.name} v{init.serverInfo.version}",
                    file=sys.stderr,
                )
                async with stdio_server() as (read_s, write_s):
                    await self.server.run(
                        read_s, write_s, self.server.create_initialization_options()
                    )


def main():
    import argparse

    parser = argparse.ArgumentParser(description="Recaf MCP stdio bridge")
    parser.add_argument("--port", type=int, default=8085, help="Recaf MCP port")
    args = parser.parse_args()

    bridge = RecafMcpBridge(port=args.port)
    asyncio.run(bridge.run())


if __name__ == "__main__":
    main()
