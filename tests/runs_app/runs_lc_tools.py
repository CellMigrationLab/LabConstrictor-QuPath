"""Test tools for run identity (RUN-01..07), retention (HRET-01..06, RET-10, 11) and number limits (NUM-01..05).

A DELAYED tool blocks on a gate file that the test creates (a deterministic barrier: nothing here depends on timing), so the test can change the open image, the selection, the form or
the app while a run is going and then release it. `never_returns` ignores Cancel (the worker has to be killed), `dies` ends its own process."""
import os
import time
from typing import Annotated, Optional

from labconstrictor_tools import ApplyTo, ClearAfterRun, Image, Max, MessageOut, Name, PointsOut, Scalars, TableOut, ToolError, check_cancel, tool

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
