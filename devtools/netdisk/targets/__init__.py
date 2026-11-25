"""The netdisks the pipeline publishes to. A new one is a new module here and a line in the registry."""
from .quark import QuarkTarget

REGISTRY = {
    'quark': QuarkTarget,
}


def names():
    """The netdisks there are."""
    return list(REGISTRY)


def build(name, **options):
    """The netdisk of that name."""
    if name not in REGISTRY:
        raise KeyError(f'there is no netdisk called {name} (there are: {", ".join(names())})')
    return REGISTRY[name](**options)
