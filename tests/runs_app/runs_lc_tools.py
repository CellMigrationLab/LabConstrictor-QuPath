"""Test tools for run identity (RUN-01..07), retention (HRET-01..06, RET-10, 11) and number limits (NUM-01..05).

A DELAYED tool blocks on a gate file that the test creates (a deterministic barrier: nothing here depends on timing), so the test can change the open image, the selection, the form or
the app while a run is going and then release it. `never_returns` ignores Cancel (the worker has to be killed), `dies` ends its own process."""
import os
import time
from typing import Annotated, Optional

from labconstrictor_tools import ApplyTo, ChoicesFrom, ClearAfterRun, Image, Max, MessageOut, Name, PointsOut, Scalars, TableOut, ToolError, check_cancel, tool

WAIT_LIMIT_S = 180


def _wait_for(gate: str, honour_cancel: bool) -> None:
    end = time.time() + WAIT_LIMIT_S
    while gate and not os.path.exists(gate) and time.time() < end:
        if honour_cancel:
            check_cancel()
        time.sleep(0.05)


@tool("Delayed points")
def delayed_points(
    image: Image, gate: str = "", y: float = 3.0, x: float = 4.0
) -> tuple[Annotated[PointsOut, Name("marks"), ApplyTo("image")], Annotated[Scalars, Name("seen")]]:
    """Wait for the gate file, then return one point at (y, x) of the image it received and the shape it received."""
    shape = [int(n) for n in image.shape]
    _wait_for(gate, True)
    return [{"y": float(y), "x": float(x)}], {"image_shape": shape, "tool": "delayed_points"}


@tool("Delayed values")
def delayed_values(gate: str = "") -> Annotated[Scalars, Name("seen")]:
    """Wait for the gate file, then return a value (a tool the test switches to while another one runs)."""
    _wait_for(gate, True)
    return {"tool": "delayed_values"}


@tool("Quick values")
def quick_values(n: Annotated[int, ClearAfterRun()] = 1) -> Annotated[Scalars, Name("seen")]:
    """Return at once (the other tool of the app). `n` is reset after a run: the test checks that the end of ANOTHER tool's run does not reset it."""
    return {"tool": "quick_values", "n": int(n)}


@tool("Writes a table")
def writes_table(gate: str = "") -> tuple[Annotated[TableOut, Name("report")], Annotated[MessageOut, Name("done")]]:
    """Wait for the gate file, then return a table: its file is in the results folder of the run (RET-10)."""
    _wait_for(gate, True)
    return {"a": [1, 2, 3], "b": ["x", "y", "z"]}, "report written"


@tool("Fails")
def fails() -> Annotated[Scalars, Name("seen")]:
    """The tool raises an error (the run ends FAILED)."""
    raise ToolError("boom", "deliberate failure")


@tool("Never returns")
def never_returns(gate: str = "") -> Annotated[Scalars, Name("seen")]:
    """Ignore Cancel for as long as the gate file does not exist: the host has to kill the worker (HRET-05)."""
    _wait_for(gate, False)
    return {"tool": "never_returns"}


@tool("Dies")
def dies() -> Annotated[Scalars, Name("seen")]:
    """End the worker process at once, with no Cancel pressed (HRET-06)."""
    os._exit(7)


@tool("Numbers")
def numbers(
    whole: int = 0,
    ratio: float = 0.0,
    capped: Annotated[int, Max(10)] = 1,
    maybe: Optional[int] = None,
) -> Annotated[Scalars, Name("seen")]:
    """Report the numbers it received, as text and with their Python types (NUM-01..05): the request must carry exactly what was typed."""
    return {
        "whole": str(whole), "whole_type": type(whole).__name__,
        "ratio": repr(ratio), "ratio_type": type(ratio).__name__,
        "capped": str(capped), "maybe": None if maybe is None else str(maybe),
    }


# ---- ChoicesFrom lookups (ASY-01..08): the source tools answer at once, wait for a gate file (the dependency IS the gate path) or fail after it
@tool("Source A")
def choices_a(gate: str = "") -> Annotated[Scalars, Name("answer")]:
    """The choices of tool A, named after the gate they were asked for: a1:<gate file name>, a2:<gate file name> (after the gate file exists)."""
    _wait_for(gate, True)
    name = os.path.basename(gate)
    return {"choices": ["a1:" + name, "a2:" + name]}


@tool("Source A that fails")
def choices_a_fails(gate: str = "") -> Annotated[Scalars, Name("answer")]:
    """After the gate file exists the source fails."""
    _wait_for(gate, True)
    raise ToolError("source_broken", "the source of A is broken")


@tool("Source B")
def choices_b(key: str = "") -> Annotated[Scalars, Name("answer")]:
    """The choices of tool B: b1, b2, b3 (at once)."""
    return {"choices": ["b1", "b2", "b3"]}


@tool("Source C")
def choices_c(key: str = "") -> Annotated[Scalars, Name("answer")]:
    """The choices of the second dropdown: c1, c2 (at once)."""
    return {"choices": ["c1", "c2"]}


@tool("Tool A")
def tool_a(mode: Annotated[str, ChoicesFrom("choices_a", depends=["gate"])] = "", gate: str = "") -> Annotated[Scalars, Name("seen")]:
    """A tool whose dropdown `mode` comes from the delayed source A (it depends on `gate`)."""
    return {"mode": mode}


@tool("Tool A that fails")
def tool_a_fails(mode: Annotated[str, ChoicesFrom("choices_a_fails", depends=["gate"])] = "", gate: str = "") -> Annotated[Scalars, Name("seen")]:
    """A tool whose dropdown comes from a source that fails after the gate."""
    return {"mode": mode}


@tool("Tool B")
def tool_b(mode: Annotated[str, ChoicesFrom("choices_b", depends=["key"])] = "", key: str = "k") -> Annotated[Scalars, Name("seen")]:
    """A tool whose dropdown `mode` comes from source B (it depends on `key`)."""
    return {"mode": mode}


@tool("Tool with two dropdowns")
def tool_two(
    first: Annotated[str, ChoicesFrom("choices_b", depends=["key"])] = "",
    second: Annotated[str, ChoicesFrom("choices_c", depends=["key"])] = "",
    key: str = "k",
) -> Annotated[Scalars, Name("seen")]:
    """Two dropdowns that depend on the same value."""
    return {"first": first, "second": second}
