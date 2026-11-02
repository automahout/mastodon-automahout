#!/usr/bin/env python
"""Run the ELEPHANT server natively (no Docker, no RabbitMQ): the Flask API or its Celery worker.

usage: serve_local.py api|worker

Started by launch_local.sh, which sets up the environment. Without ELEPHANT_RABBITMQ set, log messages are not
published to RabbitMQ (there is none). The bundled elephant-core keeps its data under
/workspace (the container's mount point), which cannot be created on macOS; this points those directories at
$ELEPHANT_WORKSPACE before any server module reads them (they copy the values at import).
"""
import os
import sys

ws = os.environ["ELEPHANT_WORKSPACE"]
import elephant.config as config  # noqa: E402  (must be patched before the app imports it)

for name, sub in (("DATASETS_DIR", "datasets"), ("MODELS_DIR", "models"), ("LOGS_DIR", "logs"),
                  ("MEMMAPS_DIR", "memmaps")):
    path = os.path.join(ws, sub)
    os.makedirs(path, exist_ok=True)
    setattr(config, name, path)

# No RabbitMQ here. With RUN_ON_FLASK set, every log line is also published to RabbitMQ; when the connection
# fails pika logs the failure through the root logger, whose RabbitMQ handler publishes again -- an endless loop
# that hangs every request. The Mastodon client works without the queue (it only carries log/progress
# messages), so publishing is a no-op. Must be patched before the server modules import the function.
import elephant.logging as elephant_logging  # noqa: E402

if not os.environ.get("ELEPHANT_RABBITMQ"):
    elephant_logging.publish_mq = lambda queue, body: None

sys.path.insert(0, os.path.join(os.environ["ELEPHANT_SERVER_DIR"], "app"))
import main  # noqa: E402

# The tasks are @shared_task: each binds to the current Celery app, which is thread-local. Flask's threaded
# server handles a request on a new thread, where the current app is Celery's built-in default (broker
# amqp://localhost), not the one configured here (redis), so every predict failed with "Connection refused".
main.celery.set_default()

if sys.argv[1] == "api":
    main.app.run(host="127.0.0.1", port=int(os.environ.get("ELEPHANT_HTTP_PORT", "8080")), debug=False,
                 threaded=True)
elif sys.argv[1] == "worker":
    main.celery.worker_main(["worker", "-P", "threads", "--loglevel=WARNING"])
else:
    raise SystemExit("usage: serve_local.py api|worker")
