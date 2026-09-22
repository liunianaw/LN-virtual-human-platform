"""RabbitMQ wake-up consumer; generation state remains owned by system."""

from __future__ import annotations

import asyncio
import json
import os
from collections.abc import Mapping
from dataclasses import dataclass
from urllib.parse import quote

import aio_pika

from .generation import AvatarGenerationRequested, WorkerOutcome
from .platform_http import PlatformTransportError, PreflightClaimFailure


EVENTS_EXCHANGE = "ln.platform.events"
RETRY_EXCHANGE = "ln.platform.retry"
DEAD_LETTER_EXCHANGE = "ln.platform.dlx"
ROUTING_KEY = "avatar.generation.requested.v1"
RETRY_ROUTING_KEY = "avatar.generation.requested.retry.10s.v1"
DEAD_LETTER_ROUTING_KEY = "avatar.generation.requested.dlq.v1"
QUEUE = "ln.media.avatar-generation.v1"
RETRY_QUEUE = "ln.media.avatar-generation.retry.10s.v1"
DEAD_LETTER_QUEUE = "ln.media.avatar-generation.dlq.v1"
RETRY_HEADER = "x-generation-retry"
RETRY_LIMIT = 36


@dataclass(frozen=True)
class RabbitSettings:
    url: str

    @classmethod
    def from_environment(cls) -> "RabbitSettings":
        host = os.environ.get("RABBITMQ_HOST", "")
        username = os.environ.get("RABBITMQ_USERNAME", "")
        password = os.environ.get("RABBITMQ_PASSWORD", "")
        vhost = os.environ.get("RABBITMQ_VHOST", "")
        try:
            port = int(os.environ.get("RABBITMQ_PORT", "5672"))
        except ValueError as error:
            raise ValueError("RABBITMQ_PORT is invalid") from error
        if not host or not username or not password or not vhost or not 1 <= port <= 65535:
            raise ValueError("RabbitMQ connection is not configured")
        return cls(f"amqp://{quote(username, safe='')}:{quote(password, safe='')}@{host}:{port}/{quote(vhost, safe='')}")


class GenerationRabbitConsumer:
    def __init__(self, worker, platform, emit, heartbeat_seconds: float) -> None:
        self._worker = worker
        self._platform = platform
        self._emit = emit
        self._heartbeat_seconds = heartbeat_seconds

    async def run(self) -> None:
        connection = await aio_pika.connect_robust(RabbitSettings.from_environment().url)
        heartbeat = asyncio.create_task(self._emit_heartbeats())
        try:
            channel = await connection.channel(publisher_confirms=True)
            await channel.set_qos(prefetch_count=1)
            queue, retry_exchange = await self._declare_topology(channel)
            async with queue.iterator() as messages:
                async for message in messages:
                    await self._consume(message, retry_exchange)
        finally:
            heartbeat.cancel()
            await asyncio.gather(heartbeat, return_exceptions=True)
            await connection.close()

    async def _declare_topology(self, channel):
        events = await channel.declare_exchange(EVENTS_EXCHANGE, aio_pika.ExchangeType.DIRECT, durable=True)
        retry = await channel.declare_exchange(RETRY_EXCHANGE, aio_pika.ExchangeType.DIRECT, durable=True)
        dead_letter = await channel.declare_exchange(DEAD_LETTER_EXCHANGE, aio_pika.ExchangeType.DIRECT, durable=True)
        queue = await channel.declare_queue(QUEUE, durable=True, arguments={
            "x-dead-letter-exchange": DEAD_LETTER_EXCHANGE,
            "x-dead-letter-routing-key": DEAD_LETTER_ROUTING_KEY,
        })
        retry_queue = await channel.declare_queue(RETRY_QUEUE, durable=True, arguments={
            "x-message-ttl": 10_000,
            "x-dead-letter-exchange": EVENTS_EXCHANGE,
            "x-dead-letter-routing-key": ROUTING_KEY,
        })
        dead_queue = await channel.declare_queue(DEAD_LETTER_QUEUE, durable=True)
        await queue.bind(events, ROUTING_KEY)
        await retry_queue.bind(retry, RETRY_ROUTING_KEY)
        await dead_queue.bind(dead_letter, DEAD_LETTER_ROUTING_KEY)
        return queue, retry

    async def _consume(self, message: aio_pika.abc.AbstractIncomingMessage, retry_exchange) -> None:
        try:
            retry_count = _retry_count(message.headers)
            payload = _message_payload(message.body)
            outcome = await asyncio.to_thread(_drain_task, self._worker, self._emit, payload)
            if outcome is WorkerOutcome.WAITING:
                await self._retry_or_dead_letter(message, retry_exchange, retry_count)
                return
            if retry_count and outcome in {WorkerOutcome.NO_WORK, WorkerOutcome.IGNORED_STALE}:
                event = AvatarGenerationRequested.from_message(payload)
                if await asyncio.to_thread(self._platform.has_active_steps, event):
                    await self._retry_or_dead_letter(message, retry_exchange, retry_count)
                    return
            await message.ack()
        except ValueError as error:
            # Do not log the body (it may later carry provider metadata), but
            # retain the bounded validation reason needed to distinguish a
            # protocol mismatch from a provider or transport failure.
            self._emit("rabbit_message_rejected:" + _rejection_reason(error))
            await message.reject(requeue=False)
        except (PreflightClaimFailure, PlatformTransportError, OSError, TimeoutError) as error:
            self._emit("rabbit_retry_" + type(error).__name__.lower())
            await self._retry_or_dead_letter(message, retry_exchange, retry_count)

    async def _retry_or_dead_letter(self, message, retry_exchange, retry_count: int) -> None:
        if retry_count >= RETRY_LIMIT:
            self._emit("rabbit_retry_exhausted")
            await message.reject(requeue=False)
            return
        headers = dict(message.headers or {})
        headers[RETRY_HEADER] = retry_count + 1
        retry = aio_pika.Message(message.body, content_type="application/json",
            delivery_mode=aio_pika.DeliveryMode.PERSISTENT, message_id=message.message_id, headers=headers)
        await retry_exchange.publish(retry, RETRY_ROUTING_KEY)
        await message.ack()

    async def _emit_heartbeats(self) -> None:
        while True:
            await asyncio.sleep(self._heartbeat_seconds)
            self._emit("heartbeat")


def _drain_task(worker, emit, message: dict[str, object]) -> WorkerOutcome:
    while True:
        outcome = worker.handle(message)
        emit("generation_" + outcome.value.lower())
        if outcome in {WorkerOutcome.REPORTED_SUCCEEDED, WorkerOutcome.REPORTED_FAILED, WorkerOutcome.REPORTED_UNKNOWN}:
            continue
        return outcome


def _message_payload(body: bytes) -> dict[str, object]:
    try:
        value = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("RabbitMQ message is not JSON") from error
    if not isinstance(value, Mapping):
        raise ValueError("RabbitMQ message is not an object")
    return dict(value)


def _retry_count(headers: Mapping[str, object] | None) -> int:
    value = (headers or {}).get(RETRY_HEADER, 0)
    try:
        parsed = int(value)
    except (TypeError, ValueError) as error:
        raise ValueError("RabbitMQ retry header is invalid") from error
    if not 0 <= parsed <= RETRY_LIMIT:
        raise ValueError("RabbitMQ retry header is invalid")
    return parsed


def _rejection_reason(error: ValueError) -> str:
    reason = str(error)
    if not reason or len(reason) > 120 or any(character.isspace() for character in reason):
        return "invalid_message"
    return reason
