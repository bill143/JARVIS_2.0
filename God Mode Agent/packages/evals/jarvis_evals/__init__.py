"""Evaluation platform: scenario suites, offline/online runs, score history, gates."""

from jarvis_evals.platform import EvalsPlatform
from jarvis_evals.runner import EvalRunner
from jarvis_evals.scenarios import SUITES

__all__ = ["EvalRunner", "EvalsPlatform", "SUITES"]
