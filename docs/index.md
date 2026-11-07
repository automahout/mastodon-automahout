# Automahout

Automahout is an autonomous image analysis system for cell tracking: a plugin for
[Mastodon](https://github.com/mastodon-sc/mastodon) in [Fiji](https://fiji.sc) that carries out the work a person
does with Mastodon and [ELEPHANT](https://elephant-track.github.io): it looks at the data, tries pretrained
detectors, annotates cells, trains a detection model of its own, tracks, and checks and corrects its result,
asking the person only when it cannot decide. Every annotation, detection and link it makes appears in
Mastodon's own views, where it can be inspected and edited.

The preprint, ["Automahout: an autonomous image analysis system for cell tracking"](https://doi.org/10.64898/2026.11.04.764293)
(bioRxiv), describes the version at tag
[`preprint-v1`](https://github.com/automahout/mastodon-automahout/tree/preprint-v1). The source code is at
[github.com/automahout/mastodon-automahout](https://github.com/automahout/mastodon-automahout).

```{toctree}
:maxdepth: 2
:caption: User guide

getting-started
settings
elephant-local
```

```{toctree}
:maxdepth: 2
:caption: Developer guide

architecture
writing-a-tool
remote-tools
```

```{toctree}
:maxdepth: 1
:caption: The preprint

paper/benchmark-protocol
citation
```
