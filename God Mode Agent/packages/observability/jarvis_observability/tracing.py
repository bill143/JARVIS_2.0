"""OpenTelemetry tracing with a no-op fallback when OTel isn't installed.

Exporter is configurable (console / otlp). When opentelemetry packages are
absent, trace_span becomes a lightweight logging span so instrumentation calls
remain valid everywhere (API requests, model calls, tool execution, queue jobs).
"""

from __future__ import annotations

from contextlib import contextmanager

from jarvis_shared.logging import get_logger, log_event

logger = get_logger("jarvis.tracing")

_TRACER = None
_ENABLED = False


def setup_tracing(enabled: bool = True, exporter: str = "console", endpoint: str = "", service: str = "jarvis-api") -> bool:
    """Initialize tracing. Returns True if real OTel tracing is active."""
    global _TRACER, _ENABLED
    if not enabled:
        _ENABLED = False
        return False
    try:
        from opentelemetry import trace
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor, ConsoleSpanExporter

        resource = Resource.create({"service.name": service})
        provider = TracerProvider(resource=resource)
        if exporter == "otlp" and endpoint:
            from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

            span_exporter = OTLPSpanExporter(endpoint=endpoint)
        else:
            span_exporter = ConsoleSpanExporter()
        provider.add_span_processor(BatchSpanProcessor(span_exporter))
        trace.set_tracer_provider(provider)
        _TRACER = trace.get_tracer(service)
        _ENABLED = True
        log_event(logger, "tracing.enabled", exporter=exporter)
        return True
    except ImportError:
        _ENABLED = False
        log_event(logger, "tracing.fallback", reason="opentelemetry not installed")
        return False


def get_tracer():
    return _TRACER


@contextmanager
def trace_span(name: str, **attributes):
    """Start a span (real OTel span if available, else a logged fallback span)."""
    if _ENABLED and _TRACER is not None:
        with _TRACER.start_as_current_span(name) as span:
            for key, value in attributes.items():
                try:
                    span.set_attribute(key, value)
                except Exception:
                    pass
            yield span
    else:
        log_event(logger, "trace.span", span=name, **attributes)
        yield None
