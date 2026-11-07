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

# A process spawned for CPU training (see the end of this file) must not reset the server's state. Importing
# elephant.redis_util sets it to IDLE, which is right for the server starting up; in a training process it is the
# signal that the training was aborted, so the API answered every CPU /seg/train with completed=false a moment
# after the processes started, and the model was never trained. A GPU server trains in one process and never
# met it. The training still reads the state (to pause or stop on request); only that one write is skipped.
if __name__ != "__main__":
    import redis

    _set = redis.Redis.set

    def _set_except_state_reset(self, name, value, *args, **kwargs):
        if name == "state" and str(value) == "0":
            return True
        return _set(self, name, value, *args, **kwargs)

    redis.Redis.set = _set_except_state_reset
    import elephant.redis_util  # noqa: E402,F401  (its import-time reset now skipped)

    redis.Redis.set = _set

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

# Only in the process started from the command line. Without a GPU, ELEPHANT trains on two CPU processes
# (torch.multiprocessing.spawn), and a spawned process imports this file again, as __mp_main__, with the parent's
# sys.argv: unguarded, each one started a second Celery worker instead of training, the server saw the training
# end, and every /seg/train answered completed=false with the model untouched. The patches above still run there.
if __name__ == "__main__":
    if sys.argv[1] == "api":
        main.app.run(host="127.0.0.1", port=int(os.environ.get("ELEPHANT_HTTP_PORT", "8080")), debug=False,
                     threaded=True)
    elif sys.argv[1] == "worker":
        main.celery.worker_main(["worker", "-P", "threads", "--loglevel=WARNING"])
    else:
        raise SystemExit("usage: serve_local.py api|worker")
