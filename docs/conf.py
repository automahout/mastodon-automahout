"""Sphinx configuration for the Automahout documentation (Read the Docs).

Build locally from this folder:

    uv run sphinx-build -W -b html . _build/html
"""

project = "Automahout"
author = "Ko Sugawara, Shuichi Onami"
copyright = "2026, Ko Sugawara, Shuichi Onami"

extensions = [
    "myst_parser",
    "sphinxcontrib.mermaid",
]

source_suffix = {".md": "markdown"}
myst_enable_extensions = ["colon_fence", "deflist"]
myst_heading_anchors = 4
# Fenced ```mermaid blocks render as diagrams, as they do on GitHub.
myst_fence_as_directive = ["mermaid"]

exclude_patterns = [
    "_build",
    ".venv",
    "README.md",
    # Written by the pre-flight analysis (AnalyzeVideoTool); never documentation.
    "video_description*.md",
    # A run log kept as an example, not a page.
    "example_log.md",
    # On the private manuscript branch only.
    "manuscript_draft.md",
    "figure_plan.md",
    "benchmark_plan.md",
    "reporting_checklist.md",
]

html_theme = "furo"
html_title = "Automahout"
html_logo = "images/automahout.svg"
html_theme_options = {
    "source_repository": "https://github.com/automahout/mastodon-automahout/",
    "source_branch": "main",
    "source_directory": "docs/",
}
