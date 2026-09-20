import os
import unittest
from unittest.mock import patch

from ruoyi_media.worker.rabbit_consumer import RabbitSettings, RETRY_LIMIT, _retry_count


class RabbitConsumerTest(unittest.TestCase):
    def test_builds_escaped_connection_url(self):
        environment = {
            "RABBITMQ_HOST": "127.0.0.1",
            "RABBITMQ_PORT": "5672",
            "RABBITMQ_USERNAME": "dev",
            "RABBITMQ_PASSWORD": "pass:word",
            "RABBITMQ_VHOST": "/dev_vhost",
        }
        with patch.dict(os.environ, environment, clear=True):
            self.assertEqual(RabbitSettings.from_environment().url, "amqp://dev:pass%3Aword@127.0.0.1:5672/%2Fdev_vhost")

    def test_rejects_invalid_retry_header(self):
        self.assertEqual(_retry_count({}), 0)
        self.assertEqual(_retry_count({"x-generation-retry": RETRY_LIMIT}), RETRY_LIMIT)
        with self.assertRaises(ValueError):
            _retry_count({"x-generation-retry": RETRY_LIMIT + 1})
