# Automahout

Automahout is an autonomous image analysis system for cell tracking: a plugin for
[Mastodon](https://github.com/mastodon-sc/mastodon) in [Fiji](https://fiji.sc) that carries out the work a person
does with Mastodon and [ELEPHANT](https://elephant-track.github.io): it looks at the data, tries pretrained
detectors, annotates cells, trains a detection model of its own, tracks, and checks and corrects its result,
asking the person only when it cannot decide. Every annotation, detection and link it makes appears in
Mastodon's own views, where it can be inspected and edited.

This is the version the preprint "Automahout: an autonomous image analysis system for cell tracking" describes
(tag `preprint-v1`; [paper/README.md](paper/README.md) gives its figures, tables and runs). The run outputs and the
embryo's trained model are deposited at SSBD:repository, `ssbd-repos-000552`,
https://doi.org/10.24631/ssbd.repos.2026.11.552. Later versions continue on the repository's `main` branch.

## What it needs

- **To build:** a JDK 21 and Maven 3.6 or later.
- **An ELEPHANT server** for detection and training, reached over HTTP. `scripts/elephant/launch_local.sh`
  starts one on this machine ([docs/elephant-local.md](docs/elephant-local.md)); a server on a GPU machine works
  the same way.
- **A vision-language model:** a local [Ollama](https://ollama.com) server, the Anthropic API, or Claude through
  Amazon Bedrock. Choose it in *Plugins › Automahout › Automahout VLM/LLM Settings…*.
  The connection, credentials included, is kept in the user's Java preferences, not in the project.
- **Optional tool servers** that offer pretrained detectors and shape fitting over HTTP:
  [examples/remote-tools](examples/remote-tools) (Cellpose 3, StarDist), [examples/cellpose-sam](examples/cellpose-sam)
  and [examples/sam-fit](examples/sam-fit) (micro-SAM). Each is a [uv](https://docs.astral.sh/uv/) project.

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
| Run Autonomous Tracking… | a short set-up, then the whole run: detection, training, tracking and correction (the run the preprint describes) |
| Stop Automahout | stops the run |
| Automahout VLM/LLM Settings… | the language-model connection |
| Automahout Settings… | the tracking parameters ([docs/settings.md](docs/settings.md)) |
| Run a Tool… | runs one of Automahout's tools on its own |

The other items (Run Automahout, Run Automahout (Correct only), Analyze Video (VLM)…, the lineage-constraint and
ground-truth items, Track Single Window (GT-guided)… and Label Detections TP/FP by OK/NG Example…) are earlier
development tools, kept in this version as they were; later versions remove most of them.

The log window shows what Automahout measured, decided and corrected; the log is also written to a file in the
system's temporary folder.

## Documentation

| | |
|---|---|
| [docs/architecture.md](docs/architecture.md) | the components and the internal evaluation metrics |
| [docs/settings.md](docs/settings.md) | the fields of the Automahout Settings dialog |
| [docs/remote-tools.md](docs/remote-tools.md) | the HTTP protocol for tools in other languages |
| [docs/writing-a-tool.md](docs/writing-a-tool.md) | writing a tool in Java |
| [paper/README.md](paper/README.md) | the preprint's figures, tables and runs |
| [scripts/reproduce/README.md](scripts/reproduce/README.md) | rebuilding the benchmark from public data |

## Licence

BSD 2-Clause; see [LICENSE](LICENSE).
