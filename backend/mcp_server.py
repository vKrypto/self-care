"""Authenticated MCP HTTP service and a credentialed stdio-to-HTTP proxy.

Run ``python -m backend.mcp_server --stdio`` for a local agent. The proxy uses
the running backend, avoiding a second database process or planning queue.
"""
import argparse
import base64
import binascii
import functools
import logging
import os
from typing import Annotated, Any, Literal
from urllib.parse import urlsplit

import anyio
import httpx
from fastapi import HTTPException
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
from mcp.server.fastmcp import Context, FastMCP
from mcp.server.lowlevel import Server
from mcp.server.stdio import stdio_server
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import ToolAnnotations
from pydantic import Field, StrictInt
from starlette.requests import Request
from starlette.responses import JSONResponse

from .mcp_auth import SCOPE, check_origin, public_url, resolve_mcp_token, resource_url

MAX_PHOTO_BYTES = 10 * 1024 * 1024
MAX_MCP_BODY_BYTES = 15 * 1024 * 1024
DateInput = Annotated[str, Field(pattern=r'^\d{4}-\d{2}-\d{2}$')]


class MCPAuthMiddleware:
    """Authenticate each HTTP request before the MCP transport reads its body."""
    def __init__(self, app, server):
        self.app, self.server = app, server

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http':
            return await self.app(scope,receive,send)
        request = Request(scope)
        try:
            check_origin(request)
            value = request.headers.get('authorization','')
            scheme, _, token = value.partition(' ')
            if scheme.lower() != 'bearer' or not token or ' ' in token:
                raise HTTPException(401,'An MCP bearer token is required.')
            account = await anyio.to_thread.run_sync(resolve_mcp_token,self.server,token)
        except HTTPException as error:
            headers = {'Cache-Control':'no-store'}
            if error.status_code == 401:
                headers['WWW-Authenticate'] = (f'Bearer resource_metadata="{public_url()}/.well-known/oauth-protected-resource/mcp", '
                                               f'scope="{SCOPE}", error="invalid_token"')
            return await JSONResponse({'detail':error.detail},status_code=error.status_code,headers=headers)(scope,receive,send)
        scope['mcp_account'] = account
        await self.app(scope,receive,send)


def request_account(ctx):
    request = ctx.request_context.request
    account = request.scope.get('mcp_account') if request else None
    if not account or account.get('role') != 'user':
        raise ValueError('Authenticated member context is required.')
    return account


async def invoke(function, *args, **kwargs):
    try:
        return await anyio.to_thread.run_sync(functools.partial(function,*args,**kwargs))
    except HTTPException as error:
        raise ValueError(str(error.detail)) from None


def create_mcp(server):
    hostname = urlsplit(public_url()).netloc
    mcp = FastMCP('Forma',
        instructions='Manage your personal meal and workout plan and track progress. Use dates in YYYY-MM-DD. '
                     'Current-day tools use the member profile timezone. Regeneration is asynchronous: poll get_planning_job. '
                     'Photo uploads return adherence feedback; analyze_progress_photos separately requests visual feedback.',
        stateless_http=True,json_response=True,streamable_http_path='/',max_request_body_size=MAX_MCP_BODY_BYTES,
        transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=True,
            allowed_hosts=[hostname,'127.0.0.1:*','localhost:*','[::1]:*'],
            allowed_origins=[public_url(),os.getenv('FRONTEND_URL','http://localhost:5173').rstrip('/')]))
    read = ToolAnnotations(readOnlyHint=True,destructiveHint=False,idempotentHint=True,openWorldHint=False)
    update = ToolAnnotations(readOnlyHint=False,destructiveHint=False,idempotentHint=True,openWorldHint=False)
    create = ToolAnnotations(readOnlyHint=False,destructiveHint=False,idempotentHint=False,openWorldHint=False)
    regenerate = ToolAnnotations(readOnlyHint=False,destructiveHint=True,idempotentHint=False,openWorldHint=False)

    @mcp.tool(annotations=regenerate)
    async def regenerate_plan(days: Annotated[StrictInt, Field(ge=1,le=28)], ctx: Context) -> dict[str, Any]:
        """Regenerate a reviewed personal plan starting today for exactly 1–28 days. Returns a planning job to poll."""
        return await invoke(server.regenerate_for_days,days,request_account(ctx))

    @mcp.tool(annotations=read)
    async def get_current_day_plan(ctx: Context) -> dict[str, Any]:
        """Get today's dated plan, task IDs, completion statuses, photos, and adherence feedback in your timezone."""
        return await invoke(server.day_tracking,account=request_account(ctx))

    @mcp.tool(annotations=read)
    async def get_progress_summary(ctx: Context, start_date: DateInput | None = None, end_date: DateInput | None = None) -> dict[str, Any]:
        """Summarize personal adherence and progress, optionally within inclusive YYYY-MM-DD dates."""
        return await invoke(server.progress_summary,account=request_account(ctx),start_date=start_date,end_date=end_date)

    @mcp.tool(annotations=update)
    async def mark_task(task_id: str, status: Literal['completed','skipped'], ctx: Context,
                        selected_date: DateInput | None = None) -> dict[str, Any]:
        """Mark a meal, workout, or care task completed/skipped and return updated tracking. Defaults to today."""
        account = request_account(ctx)
        selected = selected_date if selected_date is not None else server.today((server.load_profile(account['id']) or {}).get('timezone','Asia/Kolkata')).isoformat()
        data = server.TaskStatus(date=selected,task_id=task_id,status=status)
        return await invoke(server.task_status,data,account=account)

    @mcp.tool(annotations=read)
    async def get_date_tracking(selected_date: DateInput, ctx: Context) -> dict[str, Any]:
        """Get detailed personal tracking, task statuses, photos, and feedback for a YYYY-MM-DD date."""
        return await invoke(server.day_tracking,account=request_account(ctx),selected_date=selected_date)

    @mcp.tool(annotations=create)
    async def upload_progress_photo(image_base64: str, ctx: Context, selected_date: DateInput | None = None) -> dict[str, Any]:
        """Upload JPEG, PNG, or WebP progress-photo bytes as plain base64 (maximum 10 MiB decoded).

        Defaults to today in your timezone. Returns the saved photo and current adherence feedback.
        Photos are private, stripped of metadata, and re-encoded by the same backend upload path.
        Call analyze_progress_photos for optional visual comparison and feedback.
        """
        if len(image_base64) > 4 * ((MAX_PHOTO_BYTES + 2) // 3):
            raise ValueError('Images must be smaller than 10 MB.')
        try:
            raw = base64.b64decode(image_base64,validate=True)
        except (binascii.Error,ValueError):
            raise ValueError('image_base64 must contain valid plain base64 image bytes.') from None
        if not raw or len(raw) > MAX_PHOTO_BYTES:
            raise ValueError('Provide an image smaller than 10 MB.')
        return await invoke(server.upload_progress_bytes,raw,request_account(ctx),selected_date=selected_date)

    @mcp.tool(annotations=create)
    async def analyze_progress_photos(ctx: Context, selected_date: DateInput | None = None) -> dict[str, Any]:
        """Request visual feedback on a day's uploaded progress photos and your plan adherence.

        Defaults to today; uses the configured AI provider and returns a saved review and tracking.
        Visual observations are approximate and should not be treated as body measurements or diagnosis.
        """
        return await invoke(server.analyze_progress,server.ProgressPhotoAnalysis(date=selected_date),
                            account=request_account(ctx))

    @mcp.tool(annotations=read)
    async def get_planning_job(job_id: str, ctx: Context) -> dict[str, Any]:
        """Poll one of your planning jobs for queued, reviewing, completed, or failed status."""
        return await invoke(server.job,job_id,account=request_account(ctx))

    return mcp


async def run_stdio_proxy():
    """Relay the native protocol to the backend, keeping stdout exclusively MCP."""
    url = os.getenv('FORMA_MCP_URL','http://127.0.0.1:8000/mcp')
    token = os.getenv('FORMA_MCP_TOKEN','')
    if not token:
        raise ValueError('Set FORMA_MCP_TOKEN to a personal token from Forma Settings.')
    target = urlsplit(url)
    if (not target.hostname or target.username or target.password or target.query or target.fragment or
            (target.scheme != 'https' and not (target.scheme == 'http' and target.hostname in ('localhost','127.0.0.1','::1')))):
        raise ValueError('FORMA_MCP_URL must use HTTPS or HTTP loopback for local development.')
    proxy = Server('Forma')
    async with httpx.AsyncClient(headers={'Authorization':'Bearer '+token},timeout=120,follow_redirects=True) as http_client:
        async with streamable_http_client(url,http_client=http_client) as (remote_read,remote_write,_):
            async with ClientSession(remote_read,remote_write) as remote:
                await remote.initialize()

                @proxy.list_tools()
                async def list_tools():
                    return (await remote.list_tools()).tools

                @proxy.call_tool()
                async def call_tool(name,arguments):
                    return await remote.call_tool(name,arguments)

                async with stdio_server() as (read,write):
                    await proxy.run(read,write,proxy.create_initialization_options())


def main():
    parser = argparse.ArgumentParser(description='Forma MCP stdio proxy to the running backend.')
    parser.add_argument('--stdio',action='store_true',help='Serve MCP over stdio using FORMA_MCP_URL and FORMA_MCP_TOKEN.')
    parser.parse_args()
    logging.basicConfig(level=logging.WARNING)
    try:
        anyio.run(run_stdio_proxy)
    except (ValueError,httpx.HTTPError) as error:
        parser.exit(1, str(error)+'\n')


if __name__ == '__main__':
    main()
