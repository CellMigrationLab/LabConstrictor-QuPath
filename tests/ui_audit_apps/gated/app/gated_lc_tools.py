"""Test app of the UI audit (docs/UI_AUDIT.md): parameters that are only enabled while another one has a value (EnabledWhen), the shape of NucleiSky's `relocalize`.

`segmentation` drives two optional 'set' parameters (enabled for "cellpose" or "instanseg"); `use_seed` (a yes/no) drives `seed`, an optional number; `gate_b` is enabled by an
optional parameter that is itself enabled by `use_seed` (a chain). The tool reports what it received, so the audit's run row can check it."""
from typing import Annotated, Literal, Optional

from labconstrictor_tools import EnabledWhen, Name, Scalars, tool


@tool("Gated parameters")
def gated(
    segmentation: Literal["threshold", "cellpose", "instanseg"] = "threshold",
    cellpose_diameter: Annotated[Optional[float], EnabledWhen("segmentation", "cellpose")] = None,
    model_name: Annotated[Optional[str], EnabledWhen("segmentation", "cellpose", "instanseg")] = None,
    use_seed: bool = False,
    seed: Annotated[Optional[int], EnabledWhen("use_seed")] = None,
    gate_a: Annotated[Optional[int], EnabledWhen("use_seed")] = None,
    gate_b: Annotated[Optional[int], EnabledWhen("gate_a")] = None,
) -> Annotated[Scalars, Name("seen")]:
    """Report the parameters it received; a parameter that is not enabled arrives as None."""
    return {"segmentation": segmentation, "cellpose_diameter": cellpose_diameter, "model_name": model_name, "use_seed": use_seed, "seed": seed, "gate_a": gate_a, "gate_b": gate_b}
