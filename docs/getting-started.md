# Getting started

## What it needs

- **To build:** a JDK 21 and Maven 3.6 or later.
- **An ELEPHANT server** for detection and training, reached over HTTP. `scripts/elephant/launch_local.sh`
  starts one on this machine ([Running ELEPHANT locally](elephant-local.md)); a server on a GPU machine works
  the same way.
- **A vision-language model:** a local [Ollama](https://ollama.com) server, the Anthropic API, or Claude through
  Amazon Bedrock. Choose it in *Plugins › Automahout › Automahout Settings…* ([Settings](settings.md)).
  The connection, credentials included, is kept in the user's Java preferences, not in the project.
- **Optional tool servers** that offer pretrained detectors and shape fitting over HTTP:
  [examples/remote-tools](https://github.com/automahout/mastodon-automahout/tree/main/examples/remote-tools)
  (Cellpose 3, StarDist),
  [examples/cellpose-sam](https://github.com/automahout/mastodon-automahout/tree/main/examples/cellpose-sam)
  and [examples/sam-fit](https://github.com/automahout/mastodon-automahout/tree/main/examples/sam-fit)
  (micro-SAM). Each is a [uv](https://docs.astral.sh/uv/) project.

## Building and running

```bash
mvn package
```

builds the plugin and runs the tests. To start Fiji with Mastodon and Automahout from the source, run the class
`org.mastodon.mamut.automahout.TestDrive` (in `src/test/java`) from an IDE, then open a Mastodon project.

## Using it

The plugin's items are under *Plugins › Automahout* in a Mastodon project window:

| Item | What it does |
|---|---|
| Run Autonomous Tracking… | a short set-up (including which timepoints to track), then the whole run: detection, training, tracking and correction |
| Stop Automahout | stops the run |
| Automahout Settings… | the language-model connection (VLM / LLM tab) and the correction parameters (Tracking tab) |
| Run a Tool… | runs one of Automahout's tools on its own |
| Advanced › | learns lineage constraints from ground truth or from the current spots and links, and builds a ground-truth annotation store |

The log window shows what Automahout measured, decided and corrected; the log is also written to a file in the
system's temporary folder.
