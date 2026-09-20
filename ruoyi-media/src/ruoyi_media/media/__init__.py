"""Local CPU media-processing primitives."""

from .processor import ACTIONS, process_action_board, remove_chroma_background, validate_action_package

__all__ = ("ACTIONS", "process_action_board", "remove_chroma_background", "validate_action_package")
